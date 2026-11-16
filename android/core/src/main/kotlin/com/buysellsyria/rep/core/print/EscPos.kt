package com.buysellsyria.rep.core.print

import java.io.ByteArrayOutputStream

/**
 * ESC/POS helpers. Arabic text is printed as an image (raster), because most
 * cheap thermal printers have no proper Arabic code page / shaping.
 */
object EscPos {
    val INIT = byteArrayOf(0x1B, 0x40)
    val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)

    fun feed(lines: Int) = byteArrayOf(0x1B, 0x64, lines.coerceIn(0, 255).toByte())

    /** Partial cut after feeding (ignored by printers without a cutter). */
    val CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

    /**
     * Encode a 1-bit image as `GS v 0` raster commands, split in bands so that
     * printers with small buffers do not drop data.
     *
     * @param isBlack returns true if pixel (x, y) must be printed.
     */
    fun raster(width: Int, height: Int, bandHeight: Int = 128, isBlack: (x: Int, y: Int) -> Boolean): ByteArray {
        require(width > 0 && height > 0)
        val bytesPerRow = (width + 7) / 8
        val out = ByteArrayOutputStream()
        var y0 = 0
        while (y0 < height) {
            val h = minOf(bandHeight, height - y0)
            out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
            out.write(bytesPerRow and 0xFF); out.write((bytesPerRow shr 8) and 0xFF)
            out.write(h and 0xFF); out.write((h shr 8) and 0xFF)
            for (y in y0 until y0 + h) {
                for (bx in 0 until bytesPerRow) {
                    var b = 0
                    for (bit in 0 until 8) {
                        val x = bx * 8 + bit
                        if (x < width && isBlack(x, y)) b = b or (0x80 shr bit)
                    }
                    out.write(b)
                }
            }
            y0 += h
        }
        return out.toByteArray()
    }

    /** Luminance threshold for an ARGB pixel. Transparent counts as white. */
    fun isDark(argb: Int, threshold: Int = 160): Boolean {
        val a = (argb ushr 24) and 0xFF
        if (a < 128) return false
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000 < threshold
    }
}
