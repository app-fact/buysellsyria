package com.buysellsyria.rep.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.buysellsyria.rep.core.geo.Geo
import com.buysellsyria.rep.core.geo.GeoPoint
import com.buysellsyria.rep.core.money.Money
import com.buysellsyria.rep.data.Where
import com.buysellsyria.rep.data.db.CustomerEntity
import com.buysellsyria.rep.sync.SyncWorker
import com.buysellsyria.rep.ui.Green
import com.buysellsyria.rep.ui.Navy
import com.buysellsyria.rep.ui.Orange
import com.buysellsyria.rep.ui.Red
import com.buysellsyria.rep.ui.components.EmptyState
import com.buysellsyria.rep.ui.components.InfoRow
import com.buysellsyria.rep.ui.components.NumberField
import com.buysellsyria.rep.ui.components.Screen
import com.buysellsyria.rep.ui.components.SearchField
import com.buysellsyria.rep.ui.components.toDoubleOrZero
import kotlinx.coroutines.launch

private fun distanceText(m: Double) = if (m < 1000) "${m.toInt()} م" else "${"%.1f".format(m / 1000)} كم"

@Composable
fun CustomersScreen(nav: NavHostController, next: String) {
    val repo = RepApp.instance.repo
    val customers by repo.customers.collectAsState(initial = emptyList())
    val fix by RepApp.instance.location.last.collectAsState()
    val profile by repo.session.profile.collectAsState()
    var query by remember { mutableStateOf("") }
    var collectFor by remember { mutableStateOf<CustomerEntity?>(null) }
    val title = when (next) {
        SaleMode.SALE.name -> "اختر الزبون للبيع"
        SaleMode.ORDER.name -> "اختر الزبون للطلبية"
        SaleMode.RETURN.name -> "اختر الزبون للمرتجع"
        "collect" -> "اختر الزبون للتحصيل"
        else -> "الزبائن"
    }
    val here = fix?.point
    val list = customers
        .filter { query.isBlank() || it.name.contains(query, true) || it.phone.contains(query) || it.city.contains(query, true) }
        .map { c -> c to if (here != null && c.lat != null && c.lng != null) Geo.distanceMeters(here, GeoPoint(c.lat, c.lng)) else null }
        .sortedWith(compareBy({ it.second ?: Double.MAX_VALUE }, { it.first.name }))

    Screen(title, onBack = { nav.popBackStack() }) { pad ->
        Scaffold(
            Modifier.padding(pad),
            floatingActionButton = {
                if (profile?.rep?.can_create_customers != false) FloatingActionButton(onClick = { nav.navigate(Routes.NEW_CUSTOMER) }) {
                    Icon(Icons.Default.Add, "زبون جديد")
                }
            },
        ) { inner ->
            Column(Modifier.padding(inner)) {
                SearchField(query, { query = it }, "ابحث بالاسم أو الهاتف أو المنطقة")
                if (list.isEmpty()) EmptyState("لا يوجد زبائن")
                LazyColumn {
                    items(list, key = { it.first.key }) { (c, d) ->
                        CustomerRow(c, d, profile?.currency?.decimals ?: 0) {
                            when (next) {
                                "view" -> nav.navigate(Routes.customer(c.key))
                                "collect" -> collectFor = c
                                else -> nav.navigate(Routes.sale(SaleMode.valueOf(next), c.key)) {
                                    popUpTo(Routes.HOME)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    collectFor?.let { c ->
        CollectionDialog(c, onDismiss = { collectFor = null }) { uuid ->
            collectFor = null
            nav.navigate(Routes.receipt("collection", uuid)) { popUpTo(Routes.HOME) }
        }
    }
}

@Composable
private fun CustomerRow(c: CustomerEntity, distance: Double?, decimals: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium)
            Text(listOf(c.city, c.street, c.phone).filter { it.isNotBlank() }.joinToString(" • "), color = Color.Gray)
            if (c.due > 0.0) Text("ذمة: ${Money.format(c.due, decimals)}", color = Orange)
        }
        when {
            c.lat == null -> Icon(Icons.Default.LocationOff, "بدون موقع", tint = Color.LightGray)
            distance != null -> Text(distanceText(distance), color = if (distance <= 50) Green else Navy)
        }
    }
    HorizontalDivider()
}

@Composable
fun CollectionDialog(c: CustomerEntity, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val context = LocalContext.current
    val repo = RepApp.instance.repo
    val scope = rememberCoroutineScope()
    val decimals = repo.session.decimals
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تحصيل من ${c.name}") },
        text = {
            Column {
                Text("الذمة الحالية: ${Money.format(c.due, decimals)}")
                Spacer(Modifier.height(8.dp))
                NumberField("المبلغ", amount, { amount = it })
                OutlinedTextField(note, { note = it }, label = { Text("ملاحظة") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = amount.toDoubleOrZero() > 0, onClick = {
                scope.launch {
                    val uuid = repo.createCollection(c.key, amount.toDoubleOrZero(), note)
                    SyncWorker.syncSoon(context)
                    onDone(uuid)
                }
            }) { Text("حفظ وطباعة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
fun CustomerScreen(nav: NavHostController, key: String) {
    val context = LocalContext.current
    val app = RepApp.instance
    val repo = app.repo
    val scope = rememberCoroutineScope()
    val customerFlow = remember(key) { repo.customer(key) }
    val customer by customerFlow.collectAsState(initial = null)
    val visit by repo.openVisit.collectAsState(initial = null)
    val profile by repo.session.profile.collectAsState()
    val fix by app.location.last.collectAsState()
    var collect by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val c = customer ?: return
    val decimals = profile?.currency?.decimals ?: 0
    val distance = fix?.point?.let { p -> if (c.lat != null && c.lng != null) Geo.distanceMeters(p, GeoPoint(c.lat, c.lng)) else null }
    val visitHere = visit?.customerKey == c.key

    Screen(c.name, onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    InfoRow("الهاتف", c.phone.ifBlank { "-" })
                    InfoRow("العنوان", listOf(c.city, c.street).filter { it.isNotBlank() }.joinToString("، ").ifBlank { "-" })
                    InfoRow("الذمة", Money.format(c.due, decimals), bold = true, color = if (c.due > 0) Orange else Green)
                    InfoRow("المسافة", distance?.let { distanceText(it) } ?: if (c.lat == null) "بدون موقع" else "-")
                    if (c.serverId == null) Text("زبون جديد بانتظار المزامنة", color = Orange)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = c.phone.isNotBlank(), modifier = Modifier.weight(1f), onClick = {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone}")))
                }) { Icon(Icons.Default.Call, null); Spacer(Modifier.width(4.dp)); Text("اتصال") }
                OutlinedButton(enabled = c.lat != null, modifier = Modifier.weight(1f), onClick = {
                    val uri = Uri.parse("geo:${c.lat},${c.lng}?q=${c.lat},${c.lng}(${Uri.encode(c.name)})")
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }) { Icon(Icons.Default.Directions, null); Spacer(Modifier.width(4.dp)); Text("الطريق") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                scope.launch {
                    val f = app.location.currentFix()
                    if (f == null) message = "تعذر تحديد الموقع. تأكد من تشغيل GPS."
                    else if (f.accuracy > 50) message = "دقة الموقع ضعيفة (${f.accuracy.toInt()} م). حاول في مكان مكشوف."
                    else { repo.setCustomerLocation(c.key, f.lat, f.lng); SyncWorker.syncSoon(context); message = "تم حفظ موقع الزبون" }
                    busy = false
                }
            }) { Icon(Icons.Default.MyLocation, null); Spacer(Modifier.width(4.dp)); Text(if (c.lat == null) "تعيين موقع الزبون هنا" else "تحديث موقع الزبون هنا") }
            message?.let { Text(it, color = Navy, modifier = Modifier.padding(8.dp)) }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            val big = Modifier.fillMaxWidth().height(56.dp)
            Button({ nav.navigate(Routes.sale(SaleMode.SALE, c.key)) }, big,
                colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("بيع مباشر من السيارة") }
            Spacer(Modifier.height(8.dp))
            Button({ nav.navigate(Routes.sale(SaleMode.ORDER, c.key)) }, big) { Text("طلبية من المستودع") }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ collect = true }, Modifier.weight(1f).height(52.dp)) { Text("تحصيل") }
                OutlinedButton({ nav.navigate(Routes.sale(SaleMode.RETURN, c.key)) }, Modifier.weight(1f).height(52.dp)) {
                    Text("مرتجع", color = Red)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (!visitHere && profile?.config?.allow_manual_visit != false) {
                TextButton(onClick = {
                    scope.launch {
                        val f = app.location.last.value
                        repo.openVisit(c.key, "manual", f?.let { Where(it.lat, it.lng, distance) })
                        SyncWorker.syncSoon(context)
                    }
                }) { Text("فتح زيارة يدوياً") }
            } else if (visitHere) {
                Text("الزيارة مفتوحة الآن", color = Green, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (collect) CollectionDialog(c, onDismiss = { collect = false }) { uuid ->
        collect = false
        nav.navigate(Routes.receipt("collection", uuid))
    }
}

@Composable
fun NewCustomerScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = RepApp.instance
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var street by remember { mutableStateOf("") }
    var useLocation by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Screen("زبون جديد", onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("اسم المحل / الزبون *") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("الهاتف") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(city, { city = it }, label = { Text("المدينة / المنطقة") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(street, { street = it }, label = { Text("العنوان") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(useLocation, { useLocation = it })
                Text("أنا الآن عند الزبون: احفظ موقعي كموقع الزبون")
            }
            error?.let { Text(it, color = Red) }
            Spacer(Modifier.height(16.dp))
            Button(enabled = name.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().height(54.dp), onClick = {
                busy = true
                scope.launch {
                    val f = if (useLocation) app.location.currentFix() else null
                    if (useLocation && f == null) error = "تعذر تحديد الموقع، تم الحفظ بدون موقع"
                    val key = app.repo.createCustomer(name, phone, street, city, f?.let { Where(it.lat, it.lng) })
                    SyncWorker.syncSoon(context)
                    nav.navigate(Routes.customer(key)) { popUpTo(Routes.NEW_CUSTOMER) { inclusive = true } }
                }
            }) { Text(if (busy) "جارٍ تحديد الموقع..." else "حفظ") }
        }
    }
}
