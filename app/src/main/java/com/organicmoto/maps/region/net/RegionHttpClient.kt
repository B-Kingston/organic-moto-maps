package com.organicmoto.maps.region.net

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Raised when the server or network rejects a request.
 *
 * [transient] marks failures worth retrying unchanged (connectivity drops,
 * timeouts, 5xx, 408/429, truncated bodies); a permanent failure (bad address,
 * TLS trust, 4xx, redirects) needs the user to change something first.
 * [retryAfterMs] carries a server `Retry-After` hint when one was sent.
 */
class RegionHttpException(
    message: String,
    val statusCode: Int = 0,
    cause: Throwable? = null,
    val transient: Boolean = false,
    val retryAfterMs: Long? = null,
) : Exception(message, cause)

/** Raised when a download is cancelled by the user or the app. */
class DownloadCancelledException : Exception("Download cancelled")

/** Result of a resumable download. */
data class DownloadOutcome(
    val bytes: Long,
    val etag: String?,
    val resumed: Boolean,
)

/**
 * The only place in the app that performs network I/O. It is deliberately a
 * plain JVM class (no Android imports) so the resumable-download, Range, ETag,
 * cancellation, and error paths are unit-tested on the desktop.
 *
 * Scheme policy: HTTPS only, unless the caller explicitly allows insecure
 * addresses (debug builds pointed at a LAN dev server). The Android manifest
 * keeps cleartext disabled in release builds regardless.
 */
class RegionHttpClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
    private val userAgent: String = "curveMaps-region/1.0",
    private val allowInsecure: (String) -> Boolean = { false },
    /** Total attempts for idempotent API GETs; transient failures only. */
    private val getAttempts: Int = 2,
    private val retryDelayMs: Long = 750,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    @Volatile
    private var active: HttpURLConnection? = null

    /** Aborts the in-flight request, if any (used for cancel). */
    fun cancelActive() {
        active?.disconnect()
    }

    /**
     * Fetches a small text document (API responses, manifests). GETs are
     * idempotent, so one transient failure (a dropped Wi-Fi packet, a proxy
     * 502, a rate-limit blip) is retried before it reaches the user.
     */
    fun fetchText(url: String, maxBytes: Int = 1 shl 20): String {
        var attempt = 1
        while (true) {
            try {
                return request("GET", url, body = null, csrfToken = null, maxBytes = maxBytes)
            } catch (e: RegionHttpException) {
                if (!e.transient || attempt >= getAttempts) throw e
                sleep((e.retryAfterMs ?: retryDelayMs * attempt).coerceAtMost(MAX_GET_RETRY_DELAY_MS))
                attempt++
            }
        }
    }

    fun fetchJson(url: String, maxBytes: Int = 1 shl 20): JSONObject = parseJson(
        fetchText(url, maxBytes),
    )

    /** POSTs a JSON document (build requests, cancels). */
    fun postJson(url: String, body: JSONObject, csrfToken: String?, maxBytes: Int = 1 shl 20): JSONObject =
        parseJson(request("POST", url, body.toString().encodeToByteArray(), csrfToken, maxBytes))

    /** POSTs an empty body (cancels). */
    fun post(url: String, csrfToken: String?, maxBytes: Int = 1 shl 20): JSONObject =
        parseJson(request("POST", url, ByteArray(0), csrfToken, maxBytes))

    private fun parseJson(text: String): JSONObject =
        try {
            JSONObject(text)
        } catch (e: org.json.JSONException) {
            throw RegionHttpException("The server returned an unexpected response", cause = e)
        }

    private fun request(method: String, url: String, body: ByteArray?, csrfToken: String?, maxBytes: Int): String {
        val connection = open(url)
        try {
            connection.requestMethod = method
            if (csrfToken != null) connection.setRequestProperty("X-CSRF-Token", csrfToken)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { output -> output.write(body) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = runCatching {
                    connection.errorStream?.use { it.readAtMost(4096).decodeToString() }
                }.getOrNull()
                val serverMessage = detail?.takeIf { it.isNotBlank() }?.let {
                    runCatching { JSONObject(it).optString("error") }.getOrNull()
                }
                throw httpFailure(connection, code, serverMessage)
            }
            val response = connection.inputStream?.use { it.readAtMost(maxBytes + 1) } ?: ByteArray(0)
            if (response.size > maxBytes) {
                throw RegionHttpException("The server response was too large")
            }
            return response.decodeToString()
        } catch (e: RegionHttpException) {
            throw e
        } catch (e: IOException) {
            throw networkFailure(e, url)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads [url] to [destination], resuming a partial `*.part` file.
     *
     * Package URLs are content-addressed (the fingerprint is in the path), so
     * a partial is always resumed with `Range`; `If-Range` is added when the
     * entity tag is known, and the caller verifies the finished file's SHA-256
     * either way. [onResponse] fires as soon as the server accepts the
     * request, before any body byte is read, so the caller can persist the
     * ETag and size: an interruption one second later must still resume
     * instead of restarting from zero.
     */
    fun download(
        url: String,
        destination: File,
        etag: String?,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
        isCancelled: () -> Boolean,
        onResponse: (etag: String?, total: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, destination.name + ".part")
        var existing = if (part.isFile) part.length() else 0L
        val resumedFrom = existing
        val connection = open(url)
        active = connection
        try {
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (existing > 0) {
                connection.setRequestProperty("Range", "bytes=$existing-")
                if (!etag.isNullOrBlank()) connection.setRequestProperty("If-Range", etag)
            }
            val code = connection.responseCode
            when (code) {
                HttpURLConnection.HTTP_OK -> {
                    // The server sent the whole entity: restart the partial file.
                    part.delete()
                    existing = 0
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    val start = startFromContentRange(connection.getHeaderField("Content-Range"))
                    if (existing == 0L || start != existing) {
                        // A range we did not ask for cannot be appended safely.
                        part.delete()
                        throw RegionHttpException(
                            "The server sent an unexpected partial response",
                            code,
                            transient = true,
                        )
                    }
                }
                416 -> { // Requested Range Not Satisfiable
                    val total = totalFromUnsatisfiedRange(connection.getHeaderField("Content-Range"))
                    if (total != null && total == existing && existing > 0) {
                        moveIntoPlace(part, destination)
                        return DownloadOutcome(existing, etag, resumed = true)
                    }
                    // The partial no longer matches the package: start over.
                    part.delete()
                    throw RegionHttpException("The server rejected the download range", code, transient = true)
                }
                HttpURLConnection.HTTP_NOT_FOUND ->
                    throw RegionHttpException("The package is no longer available on the server", code)
                else -> throw httpFailure(connection, code, serverMessage = null)
            }
            val total = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> totalFromContentRange(connection.getHeaderField("Content-Range"))
                else -> connection.contentLengthLong.takeIf { it > 0 }?.let { existing + it }
            }
            val responseEtag = connection.getHeaderField("ETag")?.takeIf { it.isNotBlank() } ?: etag
            onResponse(responseEtag, total)
            var written = existing
            var lastProgressAt = 0L
            connection.inputStream.use { input ->
                FileOutputStream(part, existing > 0).buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (isCancelled()) throw DownloadCancelledException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt > 200) {
                            lastProgressAt = now
                            onProgress(written, total)
                        }
                    }
                    output.flush()
                }
            }
            if (isCancelled()) throw DownloadCancelledException()
            if (total != null && written != total) {
                throw RegionHttpException(
                    "The download was truncated (got $written of $total bytes)",
                    transient = true,
                )
            }
            onProgress(written, total ?: written)
            moveIntoPlace(part, destination)
            return DownloadOutcome(written, responseEtag, resumed = resumedFrom > 0)
        } catch (e: DownloadCancelledException) {
            throw e
        } catch (e: RegionHttpException) {
            throw e
        } catch (e: IOException) {
            if (isCancelled()) throw DownloadCancelledException()
            throw networkFailure(e, url, duringTransfer = true)
        } finally {
            active = null
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw RegionHttpException("Invalid server address", cause = e)
        }
        val scheme = uri.scheme?.lowercase()
        when (scheme) {
            "https" -> Unit
            "http" -> if (!allowInsecure(url)) {
                throw RegionHttpException(
                    "Only HTTPS servers are supported. Plain HTTP is allowed only for " +
                        "local-network servers in debug builds with \"Allow insecure HTTP\" on.",
                )
            }
            else -> throw RegionHttpException("Unsupported server address scheme")
        }
        if (uri.host.isNullOrBlank()) {
            throw RegionHttpException("The server address has no host")
        }
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw RegionHttpException("Could not reach the server", cause = e)
        }
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "application/json")
        return connection
    }

    /**
     * Maps a non-2xx response to a message a rider can act on. The server's
     * own JSON `error` text wins when it sent one.
     */
    private fun httpFailure(connection: HttpURLConnection, code: Int, serverMessage: String?): RegionHttpException {
        val retryAfterMs = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            ?.takeIf { it >= 0 }?.let { it * 1000 }
        val transient = code == 408 || code == 429 || code in 500..599
        val message = serverMessage?.takeIf { it.isNotBlank() } ?: when (code) {
            in 300..399 -> {
                val location = connection.getHeaderField("Location")
                if (location.isNullOrBlank()) {
                    "The server redirected the request ($code). Check the server address."
                } else {
                    "The server redirected to $location. Use that address instead."
                }
            }
            401, 403 -> "The server refused the request ($code)"
            404 -> "This server has no map API at that address ($code). Check the address."
            408 -> "The server timed out waiting for the request ($code)"
            429 -> "The server is busy ($code). Try again in a moment."
            in 500..599 -> "The server had a problem ($code). Try again in a moment."
            else -> "The server answered with HTTP $code"
        }
        return RegionHttpException(message, code, transient = transient, retryAfterMs = retryAfterMs)
    }

    /** Maps a socket/TLS failure to a message that says what to check. */
    private fun networkFailure(e: IOException, url: String, duringTransfer: Boolean = false): RegionHttpException {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        val (message, transient) = when (e) {
            is UnknownHostException ->
                "Couldn't find \"$host\". Check the address and that this device is online." to true
            is ConnectException, is NoRouteToHostException ->
                "Couldn't connect to \"$host\". Check the address and port, and that the server is running." to true
            is SocketTimeoutException ->
                (if (duringTransfer) "The connection stalled" else "The server took too long to respond") to true
            is SSLHandshakeException, is SSLPeerUnverifiedException ->
                "Secure connection to \"$host\" failed: this device does not trust the server's HTTPS " +
                    "certificate." to false
            is SSLException ->
                "Secure connection to \"$host\" failed. If this is a plain-HTTP server on your " +
                    "network, enter the address with http://." to false
            is UnknownServiceException ->
                "Plain HTTP is not permitted for \"$host\"." to false
            // Socket-level detail ("unexpected end of stream") means nothing
            // to a rider mid-transfer; it stays in the exception cause.
            else -> (if (duringTransfer) "The connection dropped" else "Network error" +
                (e.message?.let { ": $it" } ?: "")) to true
        }
        return RegionHttpException(message, cause = e, transient = transient)
    }

    private fun totalFromContentRange(header: String?): Long? {
        // bytes 100-199/1234
        val slash = header?.lastIndexOf('/') ?: return null
        return header.substring(slash + 1).trim().toLongOrNull()?.takeIf { it > 0 }
    }

    private fun startFromContentRange(header: String?): Long? {
        // bytes 100-199/1234
        return header?.trim()?.removePrefix("bytes")?.trim()?.substringBefore('-')?.toLongOrNull()
    }

    private fun totalFromUnsatisfiedRange(header: String?): Long? {
        // bytes */1234
        val slash = header?.lastIndexOf('/') ?: return null
        return header.substring(slash + 1).trim().toLongOrNull()?.takeIf { it > 0 }
    }

    private fun moveIntoPlace(part: File, destination: File) {
        if (destination.exists() && !destination.delete()) {
            throw RegionHttpException("Could not replace the previous download")
        }
        if (!part.renameTo(destination)) {
            // Fall back to a copy+delete when the filesystem rejects renames.
            part.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            part.delete()
        }
    }
}

private const val MAX_GET_RETRY_DELAY_MS = 3_000L

private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var offset = 0
    while (offset < limit) {
        val count = read(buffer, offset, limit - offset)
        if (count < 0) break
        offset += count
    }
    return buffer.copyOfRange(0, offset)
}
