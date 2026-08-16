package com.organicmoto.geocoder.tool

/**
 * Minimal open-addressing long->long hash map (linear probing, interleaved
 * key/value array). Used to cache OSM node id -> packed coordinate while the
 * PBF streams (millions of entries, so boxing into a java HashMap is too
 * wasteful). Key 0 is reserved as the empty slot; OSM ids are >= 1.
 *
 * The table starts small and doubles as needed (each growth rehashes the
 * entries, total rehash work is ~2x the final size), so the initial capacity
 * is deliberately modest: a state extract only ever allocates what it needs.
 */
class LongLongMap(initialCapacityPowerOfTwo: Int = 20) {

    private var mask: Int
    private var table: LongArray
    var size: Int = 0
        private set

    init {
        require(initialCapacityPowerOfTwo in 4..30)
        val capacity = 1 shl initialCapacityPowerOfTwo
        table = LongArray(capacity * 2)
        mask = capacity - 1
    }

    /**
     * Drops all entries and releases the backing table (replaced by a tiny
     * one). Call when the map is no longer needed — e.g. right after the PBF
     * pass — so the multi-hundred-MB table can be reclaimed before the
     * locality-assignment and write passes.
     */
    fun clear() {
        table = LongArray(2 * 8) // capacity 8
        mask = 7
        size = 0
    }

    /**
     * Fibonacci-style mixing: multiply by the golden ratio, keep the top
     * log2(capacity) bits (== ushr 40 at the 2^24 default, per spec).
     * -0x61C8864680B583EBL is the same bit pattern as 0x9E3779B97F4A7C15L
     * (Kotlin hex literals are limited to the signed Long range).
     */
    private fun hash(key: Long): Int {
        val capacity = table.size / 2
        val shift = 64 - Integer.numberOfTrailingZeros(capacity)
        return ((key * -0x61C8864680B583EBL) ushr shift).toInt() and (capacity - 1)
    }

    fun put(key: Long, value: Long) {
        require(key != 0L) { "key 0 is reserved" }
        if (size + 1 > (table.size / 2) * 7 / 10) grow()
        var i = hash(key)
        while (true) {
            val k = table[2 * i]
            if (k == 0L) {
                table[2 * i] = key
                table[2 * i + 1] = value
                size++
                return
            }
            if (k == key) {
                table[2 * i + 1] = value
                return
            }
            i = (i + 1) and mask
        }
    }

    /** Returns the value or 0 if absent. */
    operator fun get(key: Long): Long {
        if (key == 0L) return 0L
        var i = hash(key)
        while (true) {
            val k = table[2 * i]
            if (k == 0L) return 0L
            if (k == key) return table[2 * i + 1]
            i = (i + 1) and mask
        }
    }

    operator fun set(key: Long, value: Long) = put(key, value)

    private fun grow() {
        val old = table
        val oldMask = mask
        table = LongArray(old.size * 2)
        mask = (table.size / 2) - 1
        size = 0
        var i = 0
        while (i < old.size) {
            val key = old[i]
            if (key != 0L) put(key, old[i + 1])
            i += 2
        }
        check(oldMask != mask)
    }
}
