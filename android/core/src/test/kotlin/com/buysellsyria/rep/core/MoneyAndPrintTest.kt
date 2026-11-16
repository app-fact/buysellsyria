package com.buysellsyria.rep.core

import com.buysellsyria.rep.core.money.Line
import com.buysellsyria.rep.core.money.Money
import com.buysellsyria.rep.core.numbering.DocNumber
import com.buysellsyria.rep.core.print.EscPos
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MoneyAndPrintTest {
    @Test
    fun totals() {
        val lines = listOf(Line(1, "رز", 3.0, 12_500.0), Line(2, "سكر", 2.0, 10_000.0, 10.0))
        val t = Money.totals(lines, invoiceDiscount = 1_500.0, paid = 50_000.0, decimals = 0)
        assertEquals(55_500.0, t.subtotal)
        assertEquals(54_000.0, t.total)
        assertEquals(4_000.0, t.due)
    }

    @Test
    fun discountNeverExceedsSubtotal() {
        val t = Money.totals(listOf(Line(1, "x", 1.0, 100.0)), 500.0, 0.0, 0)
        assertEquals(0.0, t.total)
    }

    @Test
    fun format() {
        assertEquals("1,234,567", Money.format(1_234_567.0, 0))
        assertEquals("1,234.50", Money.format(1234.5, 2))
        assertEquals("-500", Money.format(-500.0, 0))
        assertEquals("2.5", Money.formatQty(2.5))
        assertEquals("3", Money.formatQty(3.0))
    }

    @Test
    fun numbering() {
        assertEquals("R05-000123", DocNumber.sale("R05", 123))
        assertEquals("R05-C00007", DocNumber.collection("R05", 7))
        assertEquals(41, DocNumber.nextSeq(localLast = 3, serverLast = 40))
    }

    @Test
    fun rasterEncodesBitsMsbFirst() {
        // 10x1 image, pixels 0 and 9 black -> 2 bytes per row: 0x80, 0x40
        val bytes = EscPos.raster(10, 1) { x, _ -> x == 0 || x == 9 }
        assertContentEquals(byteArrayOf(0x1D, 0x76, 0x30, 0x00, 2, 0, 1, 0, 0x80.toByte(), 0x40), bytes)
    }

    @Test
    fun rasterSplitsIntoBands() {
        val bytes = EscPos.raster(8, 300, bandHeight = 128) { _, _ -> true }
        // 3 bands: 128 + 128 + 44 rows, each with an 8-byte header
        assertEquals(3 * 8 + 300, bytes.size)
    }

    @Test
    fun darkness() {
        assertEquals(true, EscPos.isDark(0xFF000000.toInt()))
        assertEquals(false, EscPos.isDark(0xFFFFFFFF.toInt()))
        assertEquals(false, EscPos.isDark(0x00000000))
    }
}
