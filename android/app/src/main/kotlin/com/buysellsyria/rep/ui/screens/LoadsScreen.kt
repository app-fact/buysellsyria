package com.buysellsyria.rep.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.buysellsyria.rep.RepApp
import com.buysellsyria.rep.Routes
import com.buysellsyria.rep.core.money.Money
import com.buysellsyria.rep.data.Fmt
import com.buysellsyria.rep.ui.Blue
import com.buysellsyria.rep.ui.Green
import com.buysellsyria.rep.ui.Orange
import com.buysellsyria.rep.ui.components.EmptyState
import com.buysellsyria.rep.ui.components.Screen

private fun stateText(s: String) = when (s) {
    "pending" -> "بانتظار المزامنة" to Orange
    "draft", "requested" -> "بانتظار أمين المستودع" to Blue
    "done" -> "تم - محمّل على حسابك" to Green
    "cancel" -> "ملغى" to Color.Gray
    else -> s to Color.Gray
}

@Composable
fun LoadsScreen(nav: NavHostController) {
    val repo = RepApp.instance.repo
    val loads by repo.loads.collectAsState(initial = emptyList())
    val d = repo.session.decimals
    var open by remember { mutableStateOf<String?>(null) }
    Screen("تحميل السيارة", onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ nav.navigate(Routes.sale(SaleMode.LOAD)) }, Modifier.weight(1f).height(52.dp)) { Text("طلب تحميل") }
                OutlinedButton({ nav.navigate(Routes.sale(SaleMode.UNLOAD)) }, Modifier.weight(1f).height(52.dp)) { Text("تنزيل للمستودع") }
            }
            Text("عند اعتماد التحميل في أودو تُنقل البضاعة إلى مخزون سيارتك وتصدر فاتورة على حسابك.",
                color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp))
            if (loads.isEmpty()) EmptyState("لا يوجد عمليات تحميل")
            LazyColumn {
                items(loads, key = { it.key }) { l ->
                    val (txt, color) = stateText(l.state)
                    Column(Modifier.fillMaxWidth().clickable { open = if (open == l.key) null else l.key }.padding(16.dp, 10.dp)) {
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text((if (l.type == "load") "تحميل " else "تنزيل ") + l.name, style = MaterialTheme.typography.titleMedium)
                                Text(Fmt.dateTime(l.date), color = Color.Gray)
                                Text(txt, color = color)
                                l.invoice?.let { Text("الفاتورة: $it", color = Color.Gray) }
                            }
                            Text(Money.format(l.amountTotal, d))
                        }
                        if (open == l.key) repo.linesOf(l.linesJson).forEach { line ->
                            Text("• ${line.name}: ${Money.formatQty(line.qty)}", modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
