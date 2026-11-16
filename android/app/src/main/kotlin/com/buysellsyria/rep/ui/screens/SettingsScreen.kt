package com.buysellsyria.rep.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.buysellsyria.rep.RepApp
import com.buysellsyria.rep.data.Fmt
import com.buysellsyria.rep.data.SyncState
import com.buysellsyria.rep.location.VisitTrackingService
import com.buysellsyria.rep.print.BluetoothPrinter
import com.buysellsyria.rep.print.PrinterDevice
import com.buysellsyria.rep.sync.SyncWorker
import com.buysellsyria.rep.ui.Orange
import com.buysellsyria.rep.ui.Red
import com.buysellsyria.rep.ui.components.Screen
import kotlinx.coroutines.launch

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onLoggedOut: () -> Unit) {
    val context = LocalContext.current
    val app = RepApp.instance
    val repo = app.repo
    val session = repo.session
    val scope = rememberCoroutineScope()
    val outbox by repo.outbox.collectAsState(initial = emptyList())
    val sync by repo.syncState.collectAsState()
    val profile by session.profile.collectAsState()
    val printer = remember { BluetoothPrinter(context) }
    var devices by remember { mutableStateOf<List<PrinterDevice>?>(null) }
    var printerName by remember { mutableStateOf(session.printerName) }
    var dots by remember { mutableIntStateOf(session.paperDots) }
    var tracking by remember { mutableStateOf(session.trackingEnabled) }
    var confirmLogout by remember { mutableStateOf(false) }

    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) devices = printer.pairedDevices()
    }

    Screen("الإعدادات والمزامنة", onBack = onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(12.dp)) {
            Section("المزامنة") {
                Text("السيرفر: ${session.serverUrl}", color = Color.Gray)
                Text(if (session.lastSyncAt > 0) "آخر مزامنة: ${Fmt.dateTime(session.lastSyncAt)}" else "لم تتم المزامنة بعد")
                when (val s = sync) {
                    is SyncState.Failed -> Text("آخر محاولة فشلت: ${s.message}", color = Red)
                    SyncState.Running -> Text("جارٍ المزامنة...")
                    else -> {}
                }
                Text("عمليات بانتظار الإرسال: ${outbox.size}", color = if (outbox.isEmpty()) Color.Gray else Orange)
                Spacer(Modifier.height(8.dp))
                Button(enabled = sync != SyncState.Running, onClick = { scope.launch { repo.sync() } },
                    modifier = Modifier.fillMaxWidth()) { Text("مزامنة الآن") }
                outbox.filter { it.status == "error" }.forEach { op ->
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text(op.label.ifBlank { op.type }, color = Red)
                    Text(op.lastError ?: "", color = Color.Gray)
                }
            }

            Section("الطابعة") {
                Text(printerName?.let { "الطابعة: $it" } ?: "لم يتم اختيار طابعة")
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 31) btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    else devices = printer.pairedDevices()
                }, modifier = Modifier.fillMaxWidth()) { Text("اختيار طابعة بلوتوث") }
                Text("اقرن الطابعة أولاً من إعدادات البلوتوث في الهاتف.", color = Color.Gray)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(dots == 384, { dots = 384; session.paperDots = 384 }, { Text("ورق 58 مم") })
                    FilterChip(dots == 576, { dots = 576; session.paperDots = 576 }, { Text("ورق 80 مم") })
                }
            }

            Section("تتبع الزيارات بالـ GPS") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("فتح وإغلاق الزيارات تلقائياً (${profile?.config?.geofence_radius ?: 50} م)", Modifier.weight(1f))
                    Switch(tracking, { on ->
                        tracking = on; session.trackingEnabled = on
                        if (on && app.location.hasPermission()) VisitTrackingService.start(context)
                        if (!on) VisitTrackingService.stop(context)
                    })
                }
                if (!app.location.isGpsOn()) Text("الـ GPS مطفأ! شغّله من إعدادات الهاتف.", color = Red)
            }

            Section("الحساب") {
                Text("${profile?.rep?.name ?: ""} (${profile?.rep?.code ?: ""})")
                Text(profile?.company?.name ?: "", color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { confirmLogout = true }) { Text("تسجيل الخروج", color = Red) }
            }
        }
    }

    devices?.let { list ->
        AlertDialog(
            onDismissRequest = { devices = null },
            title = { Text("الطابعات المقترنة") },
            text = {
                Column {
                    if (list.isEmpty()) Text("لا توجد أجهزة مقترنة")
                    list.forEach { d ->
                        Text(d.name, Modifier.fillMaxWidth().clickable {
                            session.printerAddress = d.address; session.printerName = d.name
                            printerName = d.name; devices = null
                        }.padding(vertical = 12.dp))
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton({ devices = null }) { Text("إغلاق") } },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("تسجيل الخروج") },
            text = {
                Text(if (outbox.isNotEmpty()) "تحذير: يوجد ${outbox.size} عملية لم تُرسل بعد وستُحذف! قم بالمزامنة أولاً."
                else "سيتم حذف البيانات من الهاتف.")
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        VisitTrackingService.stop(context)
                        SyncWorker.cancelAll(context)
                        repo.logout()
                        onLoggedOut()
                    }
                }) { Text("خروج") }
            },
            dismissButton = { TextButton({ confirmLogout = false }) { Text("إلغاء") } },
        )
    }
}
