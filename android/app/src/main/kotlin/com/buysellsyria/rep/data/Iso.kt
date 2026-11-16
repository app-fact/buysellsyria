package com.buysellsyria.rep.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** UTC ISO-8601 without java.time (minSdk 24). */
object Iso {
    private fun fmt() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun format(millis: Long): String = fmt().format(Date(millis)) + "Z"

    fun parse(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return runCatching { fmt().parse(text.take(19))!!.time }.getOrDefault(0L)
    }
}

object Fmt {
    fun dateTime(millis: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(millis))
    fun time(millis: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))
    fun duration(fromMillis: Long, toMillis: Long = System.currentTimeMillis()): String {
        val min = ((toMillis - fromMillis) / 60000).coerceAtLeast(0)
        return if (min < 60) "$min د" else "${min / 60} س ${min % 60} د"
    }
}
