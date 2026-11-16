package com.buysellsyria.rep.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.buysellsyria.rep.core.print.EscPos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class PrinterDevice(val name: String, val address: String)

/** Prints bitmaps on Bluetooth (SPP) ESC/POS thermal printers. */
class BluetoothPrinter(private val context: Context) {

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isEnabled() = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PrinterDevice> = runCatching {
        adapter?.bondedDevices.orEmpty().map { PrinterDevice(it.name ?: it.address, it.address) }
    }.getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    suspend fun print(address: String, bitmap: Bitmap, copies: Int = 1) = withContext(Dispatchers.IO) {
        val device: BluetoothDevice = adapter?.getRemoteDevice(address) ?: error("Bluetooth غير متاح")
        runCatching { adapter?.cancelDiscovery() }
        val socket = device.createRfcommSocketToServiceRecord(SPP)
        try {
            socket.connect()
            val out = socket.outputStream
            val data = encode(bitmap)
            repeat(copies) {
                // write in small chunks: cheap printers have tiny buffers
                var i = 0
                while (i < data.size) {
                    val n = minOf(1024, data.size - i)
                    out.write(data, i, n); out.flush(); delay(15)
                    i += n
                }
            }
            delay(800) // let the printer finish before closing
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArrayOutputStream()
        out.write(EscPos.INIT)
        out.write(EscPos.ALIGN_CENTER)
        out.write(EscPos.raster(w, h) { x, y -> EscPos.isDark(pixels[y * w + x]) })
        out.write(EscPos.feed(3))
        out.write(EscPos.CUT)
        return out.toByteArray()
    }

    /** Fallback: share the receipt image (WhatsApp, any printing app...). */
    fun share(bitmap: Bitmap, name: String) {
        val dir = File(context.cacheDir, "receipts").apply { mkdirs() }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "مشاركة الفاتورة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
