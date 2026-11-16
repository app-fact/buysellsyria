package com.buysellsyria.rep.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.buysellsyria.rep.RepApp
import com.buysellsyria.rep.Routes
import com.buysellsyria.rep.core.money.Money
import com.buysellsyria.rep.data.Fmt
import com.buysellsyria.rep.ui.Green
import com.buysellsyria.rep.ui.Orange
import com.buysellsyria.rep.ui.Red
import com.buysellsyria.rep.ui.components.EmptyState
import com.buysellsyria.rep.ui.components.InfoRow
import com.buysellsyria.rep.ui.components.Screen
import java.util.Calendar

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
private fun SyncMark(synced: Boolean) =
    Text(if (synced) "✓ مُرسلة" else "⏳ بانتظار الإرسال", color = if (synced) Green else Orange)

@Composable
fun HistoryScreen(nav: NavHostController) {
    val repo = RepApp.instance.repo
    val sales by repo.sales.collectAsState(initial = emptyList())
    val orders by repo.orders.collectAsState(initial = emptyList())
    val collections by repo.collections.collectAsState(initial = emptyList())
    val today = remember { startOfToday() }
    val visitsFlow = remember(today) { repo.visitsSince(today) }
    val visits by visitsFlow.collectAsState(initial = emptyList())
    val profile by repo.session.profile.collectAsState()
    val d = profile?.currency?.decimals ?: 0
    var tab by remember { mutableIntStateOf(0) }

    val todaySales = sales.filter { it.date >= today }
    val sold = todaySales.filter { it.type == "sale" }.sumOf { it.total }
    val returned = todaySales.filter { it.type == "return" }.sumOf { it.total }
    val cash = todaySales.sumOf { if (it.type == "sale") it.paid else -it.paid } +
        collections.filter { it.date >= today }.sumOf { it.amount }
    val credit = todaySales.filter { it.type == "sale" }.sumOf { it.total - it.paid }

    Screen("الفواتير والسجل", onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad)) {
            Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp)) {
                    Text("ملخص اليوم", style = MaterialTheme.typography.titleMedium)
                    InfoRow("المبيعات", Money.format(sold, d))
                    if (returned > 0) InfoRow("المرتجعات", Money.format(returned, d), color = Red)
                    InfoRow("النقد بيد المندوب", Money.format(cash, d), bold = true, color = Green)
                    InfoRow("مبيعات آجلة", Money.format(credit, d), color = Orange)
                    InfoRow("الزيارات", "${visits.size}")
                }
            }
            TabRow(tab) {
                listOf("الفواتير", "الطلبيات", "التحصيل", "زيارات اليوم").forEachIndexed { i, t ->
                    Tab(tab == i, { tab = i }, text = { Text(t) })
                }
            }
            LazyColumn {
                when (tab) {
                    0 -> {
                        if (sales.isEmpty()) item { EmptyState("لا توجد فواتير") }
                        items(sales, key = { it.uuid }) { s ->
                            Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.receipt("sale", s.uuid)) }
                                .padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${if (s.type == "return") "مرتجع" else "فاتورة"} ${s.number}",
                                        color = if (s.type == "return") Red else Color.Unspecified)
                                    Text("${s.customerName} • ${Fmt.dateTime(s.date)}", color = Color.Gray)
                                    SyncMark(s.synced)
                                }
                                Text(Money.format(s.total, d), style = MaterialTheme.typography.titleMedium)
                            }
                            HorizontalDivider()
                        }
                    }
                    1 -> {
                        if (orders.isEmpty()) item { EmptyState("لا توجد طلبيات") }
                        items(orders, key = { it.uuid }) { o ->
                            Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(o.serverName ?: "طلبية", style = MaterialTheme.typography.titleMedium)
                                    Text("${o.customerName} • ${Fmt.dateTime(o.date)}", color = Color.Gray)
                                    SyncMark(o.synced)
                                }
                                Text(Money.format(o.totalEstimate, d))
                            }
                            HorizontalDivider()
                        }
                    }
                    2 -> {
                        if (collections.isEmpty()) item { EmptyState("لا يوجد تحصيل") }
                        items(collections, key = { it.uuid }) { c ->
                            Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.receipt("collection", c.uuid)) }
                                .padding(16.dp, 10.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("إيصال ${c.number}")
                                    Text("${c.customerName} • ${Fmt.dateTime(c.date)}", color = Color.Gray)
                                    SyncMark(c.synced)
                                }
                                Text(Money.format(c.amount, d), color = Green)
                            }
                            HorizontalDivider()
                        }
                    }
                    else -> {
                        if (visits.isEmpty()) item { EmptyState("لا توجد زيارات اليوم") }
                        items(visits, key = { it.uuid }) { v ->
                            Column(Modifier.fillMaxWidth().padding(16.dp, 10.dp)) {
                                Text(v.customerName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${Fmt.time(v.checkIn)} - ${v.checkOut?.let { Fmt.time(it) } ?: "جارية"} • " +
                                        Fmt.duration(v.checkIn, v.checkOut ?: System.currentTimeMillis()) +
                                        (if (v.openMode == "manual" || v.closeMode == "manual") " • يدوية" else ""),
                                    color = Color.Gray,
                                )
                                v.noSaleReason?.let { Text("بدون بيع: $it", color = Orange) }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
