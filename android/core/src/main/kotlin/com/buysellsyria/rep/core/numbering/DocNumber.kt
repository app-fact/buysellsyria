package com.buysellsyria.rep.core.numbering

/** Offline document numbers: R05-000123 (sales), R05-C00045 (collections). */
object DocNumber {
    fun sale(prefix: String, seq: Int): String = "$prefix-" + seq.toString().padStart(6, '0')
    fun collection(prefix: String, seq: Int): String = "$prefix-C" + seq.toString().padStart(5, '0')

    /** Next sequence after a reinstall: never reuse a number the server already has. */
    fun nextSeq(localLast: Int, serverLast: Int): Int = maxOf(localLast, serverLast) + 1
}
