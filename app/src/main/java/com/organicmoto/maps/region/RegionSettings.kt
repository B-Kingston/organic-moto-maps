package com.organicmoto.maps.region

import android.content.Context

/** Persisted server settings for the Maps screen. */
class RegionSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var serverAddress: String
        get() = prefs.getString(KEY_SERVER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SERVER, value.trim()).apply()

    /**
     * Explicit opt-in for plain-HTTP LAN servers. Only honored in debug builds
     * and only for private/loopback addresses (see [LocalNetworkPolicy]); the
     * release manifest keeps cleartext disabled.
     */
    var allowInsecureLocal: Boolean
        get() = prefs.getBoolean(KEY_INSECURE, false)
        set(value) = prefs.edit().putBoolean(KEY_INSECURE, value).apply()

    companion object {
        private const val PREFS = "region_preferences"
        private const val KEY_SERVER = "server_address"
        private const val KEY_INSECURE = "allow_insecure_local"

        /** Placeholder shown in the Maps screen; the only scheme literal outside region/net. */
        const val SERVER_ADDRESS_HINT = "https://maps.example.net"
    }
}

/**
 * Decides whether a plain-HTTP server address is a local-network address.
 * Pure logic so the desktop tests can prove the policy; DNS is never resolved.
 */
object LocalNetworkPolicy {

    fun isLocalAddress(url: String): Boolean {
        val host = hostOf(url)?.lowercase()?.trim('[', ']') ?: return false
        if (host == "localhost" || host.endsWith(".local") || host == "::1") return true
        parseIpv4(host)?.let { return isPrivateIpv4(it) }
        if (host.contains(':')) return isPrivateIpv6(host)
        return false
    }

    private fun hostOf(url: String): String? {
        val withoutScheme = url.substringAfter("://", url)
        val authority = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return null
        // Strip userinfo and port. IPv6 literals keep their brackets.
        val afterUser = authority.substringAfterLast('@')
        if (afterUser.startsWith("[")) {
            return afterUser.substringAfter('[').substringBefore(']').takeIf { it.isNotEmpty() }
        }
        return afterUser.substringBefore(':').takeIf { it.isNotEmpty() }
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in parts.indices) {
            val value = parts[i].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[i] = value
        }
        return octets
    }

    private fun isPrivateIpv4(octets: IntArray): Boolean = when {
        octets[0] == 10 -> true
        octets[0] == 172 && octets[1] in 16..31 -> true
        octets[0] == 192 && octets[1] == 168 -> true
        octets[0] == 127 -> true
        octets[0] == 169 && octets[1] == 254 -> true
        else -> false
    }

    private fun isPrivateIpv6(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized == "::1") return true
        val first = normalized.split(':').firstOrNull { it.isNotEmpty() } ?: return false
        val prefix = first.toIntOrNull(16) ?: return false
        // fc00::/7 (unique local) and fe80::/10 (link local).
        return (prefix and 0xFE00) == 0xFC00 || (prefix and 0xFFC0) == 0xFE80
    }
}
