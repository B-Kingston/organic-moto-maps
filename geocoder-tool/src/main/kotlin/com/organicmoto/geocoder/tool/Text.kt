package com.organicmoto.geocoder.tool

import java.text.Normalizer
import java.util.Locale

/**
 * Search-text normalization and tokenization.
 *
 * This is a faithful port of the string handling Organic Maps uses for its
 * search index (libs/indexer/search_string_utils.cpp + search_delimiters.cpp):
 *  - NFKD decomposition + lowercase + combining-mark strip + special char fixes
 *  - the exact delimiter set OM uses (so indexing and querying split identically)
 *  - the "xyz's" -> "xyzs" merge rule
 *  - street-type-synonym dropping with OM's StreetTokensFilter semantics
 *
 * MUST stay in sync between the :geocoder-tool index builder and the Android
 * app (this file is copied into the app sources in a later step). Do not add
 * Android imports.
 */
object SearchText {

    /**
     * Normalize and simplify a string the OM way:
     * special char replacements, NFKD, lowercase, strip combining marks,
     * collapse repeated spaces.
     */
    fun normalize(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                'Đ', 'đ' -> sb.append('d')
                'ı', 'İ' -> sb.append('i')
                'Ø', 'ø' -> sb.append('o')
                'Œ', 'œ' -> sb.append("oe")
                'Æ', 'æ' -> sb.append("ae")
                '‘', '’' -> sb.append('\'')
                '№' -> sb.append('#')
                '\uFE0E', '\uFE0F' -> sb.append(' ') // emoji variation selectors
                else -> sb.append(ch)
            }
        }
        var out = Normalizer.normalize(sb.toString(), Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT)
        out = COMBINING.replace(out, "")
        out = SPACES.replace(out, " ")
        return out
    }

    private val COMBINING = Regex("\\p{M}+")
    private val SPACES = Regex(" +")

    /** Delimiter set ported from OM's search_delimiters.cpp. */
    fun isDelimiter(c: Char): Boolean {
        if (c < '0') return true
        if (c > '9' && c < 'A') return true
        if (c > 'Z' && c < 'a') return true
        if (c > 'z' && c.code < 0xC0) return true
        return c.code in SPECIAL_DELIMS
    }

    private val SPECIAL_DELIMS = setOf(
        0x2116, // NUMERO SIGN
        0x2013, // EN DASH
        0x2019, // RIGHT SINGLE QUOTATION MARK
        0x00AB, // LEFT-POINTING DOUBLE ANGLE QUOTATION MARK
        0x00BB, // RIGHT-POINTING DOUBLE ANGLE QUOTATION MARK
        0x3000, // IDEOGRAPHIC SPACE
        0x30FB, // KATAKANA MIDDLE DOT
        0x200E, // LEFT-TO-RIGHT MARK
        0xFF08, // FULLWIDTH LEFT PARENTHESIS
        0xFF09, // FULLWIDTH RIGHT PARENTHESIS
        0x2018, // LEFT SINGLE QUOTATION MARK
        0x2014, // EM DASH
        0x0F0B, // TIBETAN MARK INTERSYLLABIC TSHEG
        0x201C, // LEFT DOUBLE QUOTATION MARK
        0x201E, // DOUBLE LOW-9 QUOTATION MARK
        0xFFFD, // REPLACEMENT CHARACTER
        0x200C, // ZERO WIDTH NON-JOINER
        0x201D, // RIGHT DOUBLE QUOTATION MARK
        0x3001, // IDEOGRAPHIC COMMA
        0x300C, // LEFT CORNER BRACKET
        0x300D, // RIGHT CORNER BRACKET
        0x061F, // ARABIC QUESTION MARK
        0x2192, // RIGHTWARDS ARROW
        0x2212, // MINUS SIGN
        0x200D, // ZERO WIDTH JOINER
        0x200B, // ZERO WIDTH SPACE
    )

    /**
     * Tokenize an ALREADY-NORMALIZED string. Applies OM's "xyz's" -> "xyzs"
     * merge: a token directly followed by "'s" (+ delimiter or end) merges
     * the s into the token.
     */
    fun tokenize(normalized: String): List<String> {
        val tokens = ArrayList<String>(8)
        var i = 0
        val n = normalized.length
        while (i < n) {
            while (i < n && isDelimiter(normalized[i])) i++
            if (i >= n) break
            val start = i
            while (i < n && !isDelimiter(normalized[i])) i++
            var token = normalized.substring(start, i)
            if (i + 1 < n && normalized[i] == '\'' && normalized[i + 1] == 's' &&
                (i + 2 == n || isDelimiter(normalized[i + 2]))
            ) {
                token += "s"
                i += 2
            }
            tokens.add(token)
        }
        return tokens
    }

    /** True when [token] (already normalized) is a street-type synonym. */
    fun isStreetSynonym(token: String): Boolean = token in STREET_SYNONYMS

    /**
     * OM's StreetTokensFilter semantics: the first street-synonym token is
     * "delayed"; a second synonym flushes both; a single delayed token at the
     * end is dropped. So "Ann Street" -> [ann], "Park Avenue" -> [park],
     * "Street of Memories" -> [of, memories].
     */
    fun streetTokens(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        var delayed: String? = null
        for (t in tokens) {
            if (isStreetSynonym(t)) {
                val d = delayed
                if (d != null) {
                    out.add(d)
                    out.add(t)
                    delayed = null
                } else {
                    delayed = t
                }
            } else {
                out.add(t)
            }
        }
        return out
    }

    /**
     * Street-type synonyms. Kept in RAW (pre-normalization) form and passed
     * through [normalize] so the set matches tokens exactly (e.g. "đường"
     * normalizes to "d\u01B0\u01A1ng", not ASCII "duong"). Port of OM's
     * StreetsSynonymsHolder list plus common Australian/English suffixes.
     */
    private val STREET_SYNONYMS: Set<String> = listOf(
        // English
        "street", "streets", "st", "road", "rd", "drive", "dr", "lane", "ln",
        "avenue", "av", "ave", "highway", "hwy", "freeway", "fwy",
        "parkway", "pkwy", "blvd", "boulevard", "court", "ct", "place", "pl",
        "terrace", "ter", "parade", "pde", "crescent", "cres", "close", "cl",
        "circuit", "cct", "esplanade", "esp", "grove", "gr", "walk", "way",
        "gardens", "gdns",
        // French
        "rue",
        // Italian
        "via", "viale", "piazza",
        // Spanish
        "calle", "avenida", "plaza",
        // German
        "straße", "strasse", "str", "platz",
        // Indonesian
        "jalan",
        // Polish/Croatian
        "ulica", "ul",
        // Portuguese
        "rua",
        // Romanian
        "strada",
        // Turkish
        "sokağı", "sokagi", "sokak", "sk",
        // Vietnamese
        "đường", "duong",
        // Ukrainian
        "вулиця", "вул", "проспект",
        // Russian
        "улица",
    ).map { normalize(it) }.toSet()

    /** OM caps indexed name tokens; truncate longer token lists. */
    fun capTokens(tokens: List<String>): List<String> =
        if (tokens.size <= MAX_TOKENS) tokens else tokens.subList(0, MAX_TOKENS)

    const val MAX_TOKENS = 31
}
