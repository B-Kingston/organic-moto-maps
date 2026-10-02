package com.organicmoto.maps.region.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimal HTTP/1.1 server for the downloader tests. The Android unit-test
 * classpath has no `com.sun.net.httpserver`, so this implements just enough of
 * the protocol (request line, headers, Content-Length bodies, keep-alive off)
 * to exercise the real client over a real socket.
 */
class TestHttpServer(private val handler: (Request) -> Response) : AutoCloseable {

    data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    data class Response(
        val status: Int,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap(),
        /** When set, the Content-Length header lies (truncation tests). */
        val declaredLength: Long? = null,
    ) {
        constructor(status: Int, body: String, headers: Map<String, String> = emptyMap()) :
            this(status, body.encodeToByteArray(), headers)
    }

    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

    private val worker = thread(name = "test-http", isDaemon = true) { acceptLoop() }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (e: Exception) {
                return
            }
            try {
                socket.use { handle(it) }
            } catch (_: Exception) {
                // A client disconnecting mid-response is normal in the
                // cancellation tests.
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 3) return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val count = input.read(body, offset, contentLength - offset)
            if (count < 0) break
            offset += count
        }
        val response = handler(Request(parts[0], parts[1], headers, body.copyOfRange(0, offset)))
        val declared = response.declaredLength ?: response.body.size.toLong()
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
            append("Content-Length: $declared\r\n")
            append("Connection: close\r\n")
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(head.encodeToByteArray())
        output.write(response.body)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (builder.isEmpty()) null else builder.toString()
            if (byte == '\n'.code) return builder.toString().trimEnd('\r')
            builder.append(byte.toChar())
        }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        429 -> "Too Many Requests"
        else -> "Status"
    }

    override fun close() {
        serverSocket.close()
        worker.interrupt()
    }
}
