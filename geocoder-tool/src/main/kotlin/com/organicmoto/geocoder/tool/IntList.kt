package com.organicmoto.geocoder.tool

/**
 * Minimal growable primitive int list, used for posting lists. Avoids boxing
 * millions of doc ids into a java.util.List.
 */
class IntList(initialCapacity: Int = 8) {

    private var arr = IntArray(initialCapacity.coerceAtLeast(1))

    var size: Int = 0
        private set

    val last: Int get() = arr[size - 1]

    operator fun get(i: Int): Int = arr[i]

    fun add(v: Int) {
        if (size == arr.size) arr = arr.copyOf(arr.size shl 1)
        arr[size++] = v
    }

    /** Sorted, deduplicated copy (posting lists must be ascending & unique). */
    fun toSortedUniqueArray(): IntArray {
        val a = arr.copyOf(size)
        a.sort()
        var w = 0
        for (r in 1 until a.size) {
            if (a[r] != a[w]) {
                w++
                a[w] = a[r]
            }
        }
        return a.copyOf(w + 1)
    }
}
