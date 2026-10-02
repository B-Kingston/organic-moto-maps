package com.organicmoto.maps.region

import com.organicmoto.maps.region.net.RegionHttpException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * When an interrupted package transfer is retried automatically. Pure JVM so
 * the schedule is unit-tested on the desktop.
 *
 * Only transient failures (connectivity drops, stalls, 5xx, 408/429,
 * truncated bodies) are retried; a permanent failure (bad address, TLS trust,
 * 404, other 4xx) surfaces immediately because waiting cannot fix it. The
 * failure count resets whenever an attempt advanced the partial file, so a
 * long download over a flaky link keeps going as long as it makes progress,
 * while a dead link gives up after [MAX_ATTEMPTS] stalled tries and leaves
 * the resumable partial for a manual Resume.
 */
object DownloadRetryPolicy {

    const val MAX_ATTEMPTS = 5
    private const val BASE_DELAY_MS = 2_000L
    private const val MAX_DELAY_MS = 30_000L

    /** [failures] counts consecutive failed attempts that made no progress (1-based). */
    fun shouldRetry(error: Throwable, failures: Int): Boolean =
        error is RegionHttpException && error.transient && failures < MAX_ATTEMPTS

    /** Exponential backoff, honouring a server `Retry-After` within the cap. */
    fun delayMs(failures: Int, retryAfterMs: Long?): Long {
        val backoff = BASE_DELAY_MS shl (failures - 1).coerceIn(0, 4)
        return (retryAfterMs ?: backoff).coerceIn(BASE_DELAY_MS, MAX_DELAY_MS)
    }
}

/** A visible "connection lost, retrying" state for the download row. */
data class DownloadRetry(
    val attempt: Int,
    val maxAttempts: Int,
    val secondsUntilRetry: Int,
    val reason: String,
)

object PackageChecksum {

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    /** The expected digest in canonical form, or null when the server gave none. */
    fun expected(raw: String?): String? =
        raw?.trim()?.trim('"')?.lowercase()?.takeIf { SHA256_HEX.matches(it) }

    /** Streams [file] through SHA-256; [isCancelled] is polled between blocks. */
    fun sha256Hex(file: File, isCancelled: () -> Boolean = { false }): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                if (isCancelled()) throw CancellationException("checksum cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
