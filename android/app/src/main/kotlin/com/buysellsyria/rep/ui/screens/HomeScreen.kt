package com.buysellsyria.rep.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AssignmentReturn
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.buysellsyria.rep.RepApp
import com.buysellsyria.rep.Routes
import com.buysellsyria.rep.core.money.Money
import com.buysellsyria.rep.data.Fmt
import com.buysellsyria.rep.data.SyncState
import com.buysellsyria.rep.data.Where
import com.buysellsyria.rep.location.VisitTrackingService
import com.buysellsyria.rep.sync.SyncWorker
import com.buysellsyria.rep.ui.Blue
import com.buysellsyria.rep.ui.Green
import com.buysellsyria.rep.ui.Navy
import com.buysellsyria.rep.ui.Orange
import com.buysellsyria.rep.ui.Red
import com.buysellsyria.rep.ui.components.Screen
import com.buysellsyria.rep.ui.components.Tile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val NO_SALE_REASONS = listOf("المحل مغلق", "لا يحتاج بضاعة", "عنده بضاعة من منافس", "السعر غير مناسب", "صاحب المحل غير موجود", "أخرى")

@Composable
fun HomeScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = RepApp.instance
    val repo = app.repo
    val scope = rememberCoroutineScope()
    val profile by repo.session.profile.collectAsState()
    val visit by repo.openVisit.collectAsState(initial = null)
    val pending by repo.pendingCount.collectAsState(initial = 0)
    val sync by repo.syncState.collectAsState()
    var closeDialog by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true && repo.session.trackingEnabled) {
            VisitTrackingService.start(context)
        }
    }
    LaunchedEffect(Unit) {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (app.location.hasPermission()) {
            if (repo.session.trackingEnabled) VisitTrackingService.start(context)
        } else permissions.launch(perms.toTypedArray())
        SyncWorker.syncSoon(context)
        while (true) { delay(30_000); now = System.currentTimeMillis() }
    }

    Screen(
        title = profile?.rep?.name ?: "المندوب",
        actions = {
            IconButton(onClick = { scope.launch { repo.sync() } }) { Icon(Icons.Default.Sync, "مزامنة") }
            IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Default.Settings, "الإعدادات") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(12.dp)) {
            // Sync status
            val (syncText, syncColor) = when (val s = sync) {
                SyncState.Running -> "جارٍ المزامنة..." to Blue
                is SyncState.Failed -> "بدون اتصال - البيانات محفوظة على الهاتف" to Orange
                is SyncState.Done -> (if (s.errors > 0) "تمت المزامنة مع ${s.errors} أخطاء" else "تمت المزامنة ${Fmt.time(s.at)}") to
                    (if (s.errors > 0) Red else Green)
                SyncState.Idle -> (if (repo.session.lastSyncAt > 0) "آخر مزامنة ${Fmt.dateTime(repo.session.lastSyncAt)}" else "لم تتم المزامنة") to Color.Gray
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("● ", color = syncColor)
                Text(syncText, color = syncColor, modifier = Modifier.weight(1f))
                if (pending > 0) Text("$pending بانتظار الإرسال", color = Orange)
            }
            if (repo.session.token == null) {
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Red.copy(alpha = 0.1f))) {
                    Column(Modifier.padding(12.dp)) {
                        Text("انتهت جلسة الدخول. بياناتك محفوظة؛ سجّل الدخول لمتابعة المزامنة.", color = Red)
                        TextButton(onClick = { nav.navigate(Routes.LOGIN) }) { Text("تسجيل الدخول") }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // Current visit
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (visit != null) Green.copy(alpha = 0.12f) else Color.White),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(16.dp)) {
                    val v = visit
                    if (v == null) {
                        Text("لا توجد زيارة مفتوحة", style = MaterialTheme.typography.titleMedium)
                        Text("تفتح الزيارة تلقائياً عند وصولك لمسافة ${profile?.config?.geofence_radius ?: 50} م من الزبون",
                            color = Color.Gray)
                    } else {
                        Text("زيارة جارية", color = Green, fontWeight = FontWeight.Bold)
                        Text(v.customerName, style = MaterialTheme.typography.titleLarge)
                        Text("منذ ${Fmt.time(v.checkIn)} • ${Fmt.duration(v.checkIn, now)}" +
                            if (v.openMode == "manual") " • يدوية" else "", color = Color.Gray)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ nav.navigate(Routes.sale(SaleMode.SALE, v.customerKey)) }, Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("بيع") }
                            Button({ nav.navigate(Routes.sale(SaleMode.ORDER, v.customerKey)) }, Modifier.weight(1f)) { Text("طلبية") }
                            OutlinedButton({ nav.navigate(Routes.customer(v.customerKey)) }, Modifier.weight(1f)) { Text("الزبون") }
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { closeDialog = true }) { Text("إنهاء الزيارة", color = Red) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            val tiles = listOf(
                Triple("الزبائن", Icons.Default.People, Navy) to { nav.navigate(Routes.customers()) },
                Triple("بيع مباشر", Icons.Default.PointOfSale, Green) to { nav.navigate(Routes.customers(SaleMode.SALE.name)) },
                Triple("طلبية من المستودع", Icons.Default.ShoppingCart, Blue) to { nav.navigate(Routes.customers(SaleMode.ORDER.name)) },
                Triple("مرتجع", Icons.Default.AssignmentReturn, Red) to { nav.navigate(Routes.customers(SaleMode.RETURN.name)) },
                Triple("تحصيل", Icons.Default.Payments, Orange) to { nav.navigate(Routes.customers("collect")) },
                Triple("مخزون السيارة", Icons.Default.LocalShipping, Navy) to { nav.navigate(Routes.stock("van")) },
                Triple("مخزون المستودع", Icons.Default.Warehouse, Blue) to { nav.navigate(Routes.stock("main")) },
                Triple("تحميل السيارة", Icons.Default.Inventory, Green) to { nav.navigate(Routes.LOADS) },
                Triple("الفواتير والسجل", Icons.Default.ReceiptLong, Navy) to { nav.navigate(Routes.HISTORY) },
            )
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (t, click) -> Tile(t.first, t.second, t.third, Modifier.weight(1f), onClick = click) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }
            profile?.let {
                Text("رصيدك لدى الشركة: ${Money.format(it.repBalance, it.currency.decimals)} ${it.currency.symbol}",
                    color = Color.Gray, modifier = Modifier.padding(4.dp))
            }
        }
    }

    if (closeDialog) {
        val v = visit
        CloseVisitDialog(
            askReason = v != null,
            onDismiss = { closeDialog = false },
            onConfirm = { reason ->
                closeDialog = false
                scope.launch {
                    val fix = app.location.last.value
                    val r = if (v != null && repo.visitHasActivity(v.uuid)) null else reason
                    repo.closeVisit("manual", fix?.let { Where(it.lat, it.lng) }, r)
                    SyncWorker.syncSoon(context)
                }
            },
        )
    }
}

@Composable
fun CloseVisitDialog(askReason: Boolean, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var reason by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إنهاء الزيارة") },
        text = {
            Column {
                if (askReason) {
                    Text("إذا لم يتم بيع، اختر السبب:")
                    NO_SALE_REASONS.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = reason == r, onClick = { reason = r })
                            Spacer(Modifier.width(4.dp)); Text(r)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(reason) }) { Text("إنهاء") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
