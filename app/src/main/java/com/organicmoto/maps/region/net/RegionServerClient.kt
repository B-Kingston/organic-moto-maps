package com.organicmoto.maps.region.net

import com.organicmoto.maps.region.ServerArtifact
import com.organicmoto.maps.region.ServerJob
import com.organicmoto.maps.region.ServerRegion
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * Typed client for the map server's JSON API. URLs are always derived from the
 * user's configured base address plus a fixed path — the app never accepts a
 * server-supplied absolute URL for anything but the artifact download, which is
 * resolved against the same base, must stay on the same origin, and is
 * re-validated by [RegionHttpClient].
 */
class RegionServerClient(private val http: RegionHttpClient) {

    /** Normalizes a user-typed address; throws when it cannot be used. */
    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw RegionHttpException("Enter a server address")
        val hasExplicitScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)
        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            hasExplicitScheme -> throw RegionHttpException("Only HTTP(S) server addresses are supported")
            else -> "https://$trimmed"
        }
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            throw RegionHttpException("That server address is not valid", cause = e)
        }
        if (uri.host.isNullOrBlank()) throw RegionHttpException("That server address has no host")
        if (uri.userInfo != null) throw RegionHttpException("Server addresses must not contain credentials")
        if (uri.query != null || uri.fragment != null) {
            throw RegionHttpException("That server address is not valid")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            throw RegionHttpException("Only HTTP(S) server addresses are supported")
        }
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        // Accept an address copied from an API URL (".../api/v1/catalog").
        val path = uri.path.orEmpty().trimEnd('/')
            .replace(Regex("/api/v1(/.*)?$"), "")
            .trimEnd('/')
        return "$scheme://${uri.host.lowercase()}$port$path"
    }

    /**
     * Addresses to try for a user-typed server, in order. An address typed
     * without a scheme is tried as HTTPS first; when [allowPlainHttp] permits
     * the plain-HTTP form (a debug build pointed at a LAN server), that form
     * is the fallback, so "192.168.1.20:8080" just works. An explicit scheme
     * is always taken literally.
     */
    fun candidateBaseUrls(raw: String, allowPlainHttp: (String) -> Boolean): List<String> {
        val primary = normalizeBaseUrl(raw)
        val explicitScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(raw.trim())
        if (explicitScheme) return listOf(primary)
        val plain = "http://" + primary.removePrefix("https://")
        return if (allowPlainHttp(plain)) listOf(primary, plain) else listOf(primary)
    }

    fun catalog(baseUrl: String): List<ServerRegion> {
        val doc = http.fetchJson("$baseUrl/api/v1/catalog", maxBytes = 2 shl 20)
        val array = doc.optJSONArray("regions") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::parseRegion)
        }
    }

    fun csrfToken(baseUrl: String): String {
        val doc = http.fetchJson("$baseUrl/api/v1/csrf")
        return doc.optString("token").takeIf { it.isNotBlank() }
            ?: throw RegionHttpException("The server did not issue a request token")
    }

    /** Server health document; null when the endpoint is absent. */
    fun health(baseUrl: String): JSONObject? = try {
        http.fetchJson("$baseUrl/api/v1/health")
    } catch (e: RegionHttpException) {
        if (e.statusCode == 404) null else throw e
    }

    fun requestBuild(baseUrl: String, csrfToken: String, regionId: String): ServerJob {
        val body = JSONObject().put("regionId", regionId)
        return parseJob(http.postJson("$baseUrl/api/v1/builds", body, csrfToken))
    }

    fun job(baseUrl: String, jobId: String): ServerJob =
        parseJob(http.fetchJson("$baseUrl/api/v1/builds/$jobId"))

    fun cancelBuild(baseUrl: String, csrfToken: String, jobId: String): ServerJob =
        parseJob(http.post("$baseUrl/api/v1/builds/$jobId/cancel", csrfToken))

    /**
     * Resolves a server-relative path against the configured base. An
     * absolute URL is accepted only on the base's own origin: the download
     * can never be redirected to a host, port, or scheme the user did not
     * configure.
     */
    fun resolve(baseUrl: String, path: String): String {
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            if (origin(path) != origin(baseUrl)) {
                throw RegionHttpException("The server pointed the download at a different address")
            }
            return path
        }
        return baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    private fun origin(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: return@runCatching null
        val port = when {
            uri.port >= 0 -> uri.port
            scheme == "https" -> 443
            else -> 80
        }
        "$scheme://${uri.host?.lowercase()}:$port"
    }.getOrNull()

    fun downloadArtifact(
        baseUrl: String,
        job: ServerJob,
        destination: File,
        etag: String?,
        onProgress: (Long, Long?) -> Unit,
        isCancelled: () -> Boolean,
        onResponse: (etag: String?, total: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        val url = resolve(baseUrl, job.downloadUrl.ifBlank {
            "/api/v1/artifacts/${job.regionId}/unknown/${job.regionId}.motomap"
        })
        return http.download(url, destination, etag, onProgress, isCancelled, onResponse)
    }

    fun cancelActive() = http.cancelActive()

    private fun parseRegion(obj: JSONObject): ServerRegion? {
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val artifactObj = obj.optJSONObject("artifact")
        return ServerRegion(
            id = id,
            name = obj.optString("name", id),
            coverage = obj.optString("coverage").takeIf { it.isNotBlank() },
            disabled = obj.optBoolean("disabled", false),
            maxZoom = obj.optInt("maxZoom", 14),
            sourceDate = obj.optString("sourceDate"),
            sourceSha256 = obj.optString("sourceSha256"),
            sourcePinned = obj.optBoolean("sourcePinned", false),
            artifact = artifactObj?.let {
                ServerArtifact(
                    available = it.optBoolean("available", false),
                    fingerprint = it.optString("fingerprint"),
                    size = it.optLong("size", 0),
                    sha256 = it.optString("sha256"),
                    generatedAt = it.optString("generatedAt"),
                    downloadUrl = it.optString("downloadUrl"),
                )
            },
            activeJobId = obj.optString("activeJobId").takeIf { it.isNotBlank() },
        )
    }

    private fun parseJob(obj: JSONObject): ServerJob {
        val id = obj.optString("id").takeIf { it.isNotBlank() }
            ?: throw RegionHttpException("The server returned an incomplete job")
        return ServerJob(
            id = id,
            regionId = obj.optString("regionId"),
            regionName = obj.optString("regionName"),
            state = obj.optString("state"),
            step = obj.optString("step"),
            progress = obj.optDouble("progress", 0.0),
            message = obj.optString("message"),
            error = obj.optString("error"),
            cached = obj.optBoolean("cached", false),
            artifactSize = obj.optLong("artifactSize", 0),
            artifactSha256 = obj.optString("artifactSha256"),
            downloadUrl = obj.optString("downloadUrl"),
        )
    }
}
