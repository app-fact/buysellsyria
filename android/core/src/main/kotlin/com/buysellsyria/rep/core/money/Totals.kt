package com.buysellsyria.rep.core.money

import java.math.BigDecimal
import java.math.RoundingMode

data class Line(
    val productId: Long,
    val name: String,
    val qty: Double,
    val priceUnit: Double,
    val discountPercent: Double = 0.0,
) {
    val subtotal: Double get() = qty * priceUnit * (1 - discountPercent / 100.0)
}

data class Totals(val subtotal: Double, val discount: Double, val total: Double, val paid: Double) {
    val due: Double get() = total - paid
}

object Money {
    fun round(value: Double, decimals: Int): Double =
        BigDecimal.valueOf(value).setScale(decimals.coerceAtLeast(0), RoundingMode.HALF_UP).toDouble()

    fun totals(lines: List<Line>, invoiceDiscount: Double, paid: Double, decimals: Int): Totals {
        val sub = round(lines.sumOf { round(it.subtotal, decimals) }, decimals)
        val disc = round(invoiceDiscount.coerceIn(0.0, sub), decimals)
        val total = round(sub - disc, decimals)
        val p = round(paid.coerceAtLeast(0.0), decimals)
        return Totals(sub, disc, total, p)
    }

    /** "12,500" / "12,500.50" with Latin digits (clearer on thermal printers). */
    fun format(value: Double, decimals: Int): String {
        val bd = BigDecimal.valueOf(value).setScale(decimals.coerceAtLeast(0), RoundingMode.HALF_UP)
        val negative = bd.signum() < 0
        val plain = bd.abs().toPlainString()
        val intPart = plain.substringBefore('.')
        val frac = if (plain.contains('.')) "." + plain.substringAfter('.') else ""
        val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
        return (if (negative) "-" else "") + grouped + frac
    }

    fun formatQty(value: Double): String =
        if (value == Math.floor(value)) value.toLong().toString()
        else BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
