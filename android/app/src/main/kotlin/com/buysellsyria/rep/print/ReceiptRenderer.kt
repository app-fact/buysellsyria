package com.buysellsyria.rep.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.buysellsyria.rep.core.money.Money
import com.buysellsyria.rep.data.Fmt
import com.buysellsyria.rep.data.Profile
import com.buysellsyria.rep.data.db.CollectionEntity
import com.buysellsyria.rep.data.db.DocLine
import com.buysellsyria.rep.data.db.VanSaleEntity

/**
 * Draws receipts as a bitmap. Android's text engine shapes Arabic correctly,
 * so the printer only has to print an image (works on any ESC/POS printer).
 */
class ReceiptRenderer(private val width: Int) {

    private sealed class Row {
        data class Text(val text: String, val size: Float, val bold: Boolean, val align: Layout.Alignment) : Row()
        /** Right text (RTL start) + left value on the same line. */
        data class Pair(val right: String, val left: String, val size: Float, val bold: Boolean) : Row()
        data object Separator : Row()
        data class Space(val px: Int) : Row()
    }

    private val rows = mutableListOf<Row>()
    private val center = Layout.Alignment.ALIGN_CENTER
    private val start = Layout.Alignment.ALIGN_NORMAL // right side in RTL

    private val base = if (width >= 576) 26f else 22f

    private fun text(t: String, size: Float = base, bold: Boolean = false, align: Layout.Alignment = start) {
        if (t.isNotBlank()) rows += Row.Text(t, size, bold, align)
    }

    private fun pair(r: String, l: String, bold: Boolean = false, size: Float = base) { rows += Row.Pair(r, l, size, bold) }
    private fun sep() { rows += Row.Separator }
    private fun space(px: Int = 8) { rows += Row.Space(px) }

    private fun header(p: Profile, title: String, number: String, date: Long) {
        text(p.company.name, base * 1.35f, true, center)
        text(p.company.address, base * 0.9f, align = center)
        text(p.company.phone, base * 0.9f, align = center)
        space()
        text(title, base * 1.2f, true, center)
        pair("الرقم:", number)
        pair("التاريخ:", Fmt.dateTime(date))
        pair("المندوب:", p.rep.name)
    }

    fun vanSale(p: Profile, s: VanSaleEntity, lines: List<DocLine>, customerPhone: String, balance: Double?): Bitmap {
        val d = p.currency.decimals
        val cur = p.currency.symbol
        header(p, if (s.type == "return") "فاتورة مرتجع" else "فاتورة مبيع", s.number, s.date)
        pair("الزبون:", s.customerName, bold = true)
        if (customerPhone.isNotBlank()) pair("الهاتف:", customerPhone)
        if (s.printedCount > 0) text("نسخة", base, true, center)
        sep()
        lines.forEachIndexed { i, l ->
            text("${i + 1}. ${l.name}", base, true)
            val discount = if (l.discount > 0) "  خصم ${Money.formatQty(l.discount)}%" else ""
            pair("${Money.formatQty(l.qty)} ${l.uom} × ${Money.format(l.price, d)}$discount", Money.format(l.subtotal, d))
        }
        sep()
        pair("عدد الأصناف:", lines.size.toString())
        pair("المجموع:", Money.format(s.subtotal, d))
        if (s.discount > 0) pair("الخصم:", Money.format(s.discount, d))
        pair("الصافي ($cur):", Money.format(s.total, d), bold = true, size = base * 1.2f)
        pair(if (s.type == "return") "المردود نقداً:" else "المدفوع:", Money.format(s.paid, d))
        pair("المتبقي:", Money.format(s.total - s.paid, d), bold = true)
        balance?.let { pair("رصيد الزبون:", Money.format(it, d)) }
        sep()
        text("توقيع الزبون: ....................", base)
        space(16)
        text("شكراً لتعاملكم", base, true, center)
        space(40)
        return draw()
    }

    fun collection(p: Profile, c: CollectionEntity, balance: Double?): Bitmap {
        val d = p.currency.decimals
        header(p, "إيصال قبض", c.number, c.date)
        pair("الزبون:", c.customerName, bold = true)
        sep()
        pair("المبلغ المقبوض (${p.currency.symbol}):", Money.format(c.amount, d), bold = true, size = base * 1.2f)
        if (c.note.isNotBlank()) text("ملاحظة: ${c.note}")
        balance?.let { pair("الرصيد المتبقي:", Money.format(it, d)) }
        sep()
        text("توقيع المندوب: ....................", base)
        space(40)
        return draw()
    }

    // ------------------------------------------------------------------
    private fun paint(size: Float, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun layout(text: String, size: Float, bold: Boolean, w: Int, align: Layout.Alignment): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint(size, bold), w.coerceAtLeast(1))
            .setAlignment(align)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setIncludePad(false)
            .build()

    private fun draw(): Bitmap {
        val margin = 6
        val inner = width - 2 * margin
        val heights = mutableListOf<Int>()
        val drawers = mutableListOf<(Canvas, Int) -> Unit>()
        fun add(h: Int, op: (Canvas, Int) -> Unit) { heights += h; drawers += op }
        for (r in rows) when (r) {
            is Row.Text -> {
                val l = layout(r.text, r.size, r.bold, inner, r.align)
                add(l.height + 4) { c, y -> c.save(); c.translate(margin.toFloat(), y.toFloat()); l.draw(c); c.restore() }
            }
            is Row.Pair -> {
                val leftW = (paint(r.size, r.bold).measureText(r.left) + 4).toInt().coerceAtMost(inner / 2)
                val left = layout(r.left, r.size, r.bold, leftW, Layout.Alignment.ALIGN_OPPOSITE)
                val right = layout(r.right, r.size, r.bold, inner - leftW - 8, start)
                val h = maxOf(left.height, right.height) + 4
                add(h) { c, y ->
                    c.save(); c.translate((margin + leftW + 8).toFloat(), y.toFloat()); right.draw(c); c.restore()
                    c.save(); c.translate(margin.toFloat(), y.toFloat()); left.draw(c); c.restore()
                }
            }
            Row.Separator -> add(14) { c, y ->
                val p = Paint().apply { color = Color.BLACK; strokeWidth = 2f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f) }
                c.drawLine(margin.toFloat(), y + 7f, (width - margin).toFloat(), y + 7f, p)
            }
            is Row.Space -> add(r.px) { _, _ -> }
        }
        val height = heights.sum().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        var y = 0
        for (i in heights.indices) { drawers[i](canvas, y); y += heights[i] }
        rows.clear()
        return bmp
    }
}
