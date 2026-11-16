package com.buysellsyria.rep.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.buysellsyria.rep.RepApp
import com.buysellsyria.rep.print.BluetoothPrinter
import com.buysellsyria.rep.print.ReceiptRenderer
import com.buysellsyria.rep.ui.Green
import com.buysellsyria.rep.ui.Red
import com.buysellsyria.rep.ui.components.Screen
import kotlinx.coroutines.launch

/** Preview + print of a van sale ("sale") or a collection ("collection"). */
@Composable
fun ReceiptScreen(kind: String, uuid: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = RepApp.instance.repo
    val scope = rememberCoroutineScope()
    val printer = remember { BluetoothPrinter(context) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(uuid, reload) {
        val profile = repo.session.profile.value ?: return@LaunchedEffect
        val renderer = ReceiptRenderer(repo.session.paperDots)
        bitmap = if (kind == "sale") {
            val s = repo.sale(uuid) ?: return@LaunchedEffect
            val c = repo.customerNow(s.customerKey)
            renderer.vanSale(profile, s, repo.linesOf(s.linesJson), c?.phone ?: "", c?.due)
        } else {
            val col = repo.collection(uuid) ?: return@LaunchedEffect
            renderer.collection(profile, col, repo.customerNow(col.customerKey)?.due)
        }
    }

    fun doPrint() {
        val address = repo.session.printerAddress
        val bmp = bitmap ?: return
        if (address == null) { status = "اختر الطابعة من الإعدادات أولاً"; return }
        busy = true; status = "جارٍ الطباعة..."
        scope.launch {
            runCatching { printer.print(address, bmp) }
                .onSuccess {
                    status = "تمت الطباعة"
                    if (kind == "sale") { repo.markPrinted(uuid); reload++ }
                }
                .onFailure { status = "فشلت الطباعة: تأكد أن الطابعة مشغلة وقريبة (${it.message ?: ""})" }
            busy = false
        }
    }

    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) doPrint() else status = "يجب السماح بالبلوتوث للطباعة"
    }

    Screen(if (kind == "sale") "الفاتورة" else "إيصال القبض", onBack = onDone, bottomBar = {
        Surface(shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                status?.let { Text(it, color = if (it.startsWith("فشل")) Red else Color.DarkGray) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && bitmap != null, modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green),
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 31) btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) else doPrint()
                        },
                    ) { Text("طباعة") }
                    OutlinedButton(enabled = bitmap != null, modifier = Modifier.weight(1f).height(52.dp),
                        onClick = { bitmap?.let { printer.share(it, "receipt-$uuid") } }) { Text("مشاركة") }
                    OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f).height(52.dp)) { Text("تم") }
                }
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).background(Color(0xFFE0E0E0)).padding(16.dp)) {
            bitmap?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().background(Color.White))
            }
        }
    }
}
