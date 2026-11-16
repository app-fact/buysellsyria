package com.buysellsyria.rep.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.buysellsyria.rep.RepApp
import com.buysellsyria.rep.core.money.Money
import com.buysellsyria.rep.ui.Navy
import com.buysellsyria.rep.ui.Red
import com.buysellsyria.rep.ui.components.EmptyState
import com.buysellsyria.rep.ui.components.Screen
import com.buysellsyria.rep.ui.components.SearchField

/** kind = "van" (my van) or "main" (company main warehouse, to take orders). */
@Composable
fun StockScreen(kind: String, onBack: () -> Unit) {
    val repo = RepApp.instance.repo
    val products by repo.products.collectAsState(initial = emptyList())
    val stockList by (if (kind == "van") repo.vanStock else repo.mainStock).collectAsState(initial = emptyList())
    val profile by repo.session.profile.collectAsState()
    val decimals = profile?.currency?.decimals ?: 0
    var query by remember { mutableStateOf("") }
    val stock = stockList.associate { it.productId to it.qty }
    val rows = products
        .filter { (stock[it.id] ?: 0.0) != 0.0 }
        .filter { query.isBlank() || it.name.contains(query, true) || it.code.contains(query, true) }
    val value = rows.sumOf { (stock[it.id] ?: 0.0) * (if (kind == "van") it.loadPrice else it.listPrice) }

    Screen(if (kind == "van") "مخزون السيارة" else "مخزون المستودع الرئيسي", onBack = onBack) { pad ->
        Column(Modifier.padding(pad)) {
            SearchField(query, { query = it })
            Text(
                "${rows.size} صنف • القيمة ${Money.format(value, decimals)}" +
                    if (kind == "main") " • يتحدث عند كل مزامنة" else "",
                color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (rows.isEmpty()) EmptyState("لا يوجد مخزون")
            LazyColumn {
                items(rows, key = { it.id }) { p ->
                    val q = stock[p.id] ?: 0.0
                    Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(p.code, p.category).filter { it.isNotBlank() }.joinToString(" • "), color = Color.Gray)
                        }
                        Text("${Money.formatQty(q)} ${p.uom}", style = MaterialTheme.typography.titleMedium,
                            color = if (q < 0) Red else Navy)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
