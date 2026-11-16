package com.buysellsyria.rep.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.buysellsyria.rep.RepApp
import com.buysellsyria.rep.Routes
import com.buysellsyria.rep.core.money.Line
import com.buysellsyria.rep.core.money.Money
import com.buysellsyria.rep.data.Where
import com.buysellsyria.rep.data.db.DocLine
import com.buysellsyria.rep.data.db.ProductEntity
import com.buysellsyria.rep.sync.SyncWorker
import com.buysellsyria.rep.ui.Green
import com.buysellsyria.rep.ui.Navy
import com.buysellsyria.rep.ui.Orange
import com.buysellsyria.rep.ui.Red
import com.buysellsyria.rep.ui.components.EmptyState
import com.buysellsyria.rep.ui.components.InfoRow
import com.buysellsyria.rep.ui.components.NumberField
import com.buysellsyria.rep.ui.components.QtyStepper
import com.buysellsyria.rep.ui.components.Screen
import com.buysellsyria.rep.ui.components.SearchField
import com.buysellsyria.rep.ui.components.toDoubleOrZero
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

enum class SaleMode(val title: String, val stockKind: String, val limitToStock: Boolean, val needsCustomer: Boolean) {
    SALE("فاتورة بيع", "van", true, true),
    RETURN("مرتجع من زبون", "van", false, true),
    ORDER("طلبية من المستودع", "main", false, true),
    LOAD("طلب تحميل السيارة", "main", false, false),
    UNLOAD("طلب تنزيل إلى المستودع", "van", true, false),
}

class CartViewModel : ViewModel() {
    val qty = mutableStateMapOf<Long, Double>()
    var reviewing by mutableStateOf(false)
    var discount by mutableStateOf("")
    var paid by mutableStateOf<String?>(null)
    var note by mutableStateOf("")
    var saving by mutableStateOf(false)
}

@Composable
fun SaleScreen(nav: NavHostController, mode: SaleMode, customerKey: String?) {
    val context = LocalContext.current
    val app = RepApp.instance
    val repo = app.repo
    val scope = rememberCoroutineScope()
    val vm: CartViewModel = viewModel()
    val profile by repo.session.profile.collectAsState()
    val decimals = profile?.currency?.decimals ?: 0
    val customer by (customerKey?.let { repo.customer(it) } ?: flowOf(null)).collectAsState(initial = null)
    val products by repo.products.collectAsState(initial = emptyList())
    val stockList by (if (mode.stockKind == "van") repo.vanStock else repo.mainStock).collectAsState(initial = emptyList())
    val stock = remember(stockList) { stockList.associate { it.productId to it.qty } }
    val pricelistId = if (mode.needsCustomer) repo.pricelistFor(customer) else null
    val priceRows by (pricelistId?.let { repo.prices(it) } ?: flowOf(emptyList())).collectAsState(initial = emptyList())
    val prices = remember(priceRows) { priceRows.associate { it.productId to it.price } }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var editQty by remember { mutableStateOf<ProductEntity?>(null) }

    fun priceOf(p: ProductEntity): Double = when (mode) {
        SaleMode.LOAD, SaleMode.UNLOAD -> p.loadPrice
        else -> prices[p.id] ?: p.listPrice
    }
    fun limitOf(p: ProductEntity): Double? = if (mode.limitToStock) (stock[p.id] ?: 0.0).coerceAtLeast(0.0) else null
    fun setQty(p: ProductEntity, q: Double) {
        val limited = limitOf(p)?.let { q.coerceAtMost(it) } ?: q
        if (limited <= 0) vm.qty.remove(p.id) else vm.qty[p.id] = limited
        if (limited < q) Toast.makeText(context, "الكمية المتوفرة ${Money.formatQty(limited)} فقط", Toast.LENGTH_SHORT).show()
    }

    val byId = remember(products) { products.associateBy { it.id } }
    val lines = vm.qty.mapNotNull { (id, q) ->
        byId[id]?.let { p -> DocLine(p.id, p.name, p.uom, q, priceOf(p)) }
    }
    val totals = Money.totals(lines.map { Line(it.productId, it.name, it.qty, it.price) },
        vm.discount.toDoubleOrZero(), vm.paid?.toDoubleOrZero() ?: 0.0, decimals)

    val title = mode.title + (customer?.let { " - ${it.name}" } ?: "")
    Screen(
        title,
        onBack = { if (vm.reviewing) vm.reviewing = false else nav.popBackStack() },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${lines.size} صنف", color = Color.Gray)
                        Text("${Money.format(if (vm.reviewing) totals.total else totals.subtotal, decimals)} ${profile?.currency?.symbol ?: ""}",
                            style = MaterialTheme.typography.titleLarge)
                    }
                    if (!vm.reviewing) {
                        Button(enabled = lines.isNotEmpty(), onClick = { vm.reviewing = true }, modifier = Modifier.height(52.dp)) {
                            Text("متابعة")
                        }
                    } else {
                        Button(
                            enabled = lines.isNotEmpty() && !vm.saving,
                            colors = ButtonDefaults.buttonColors(containerColor = Green),
                            modifier = Modifier.height(52.dp),
                            onClick = {
                                vm.saving = true
                                scope.launch {
                                    val fix = app.location.last.value
                                    val where = fix?.let { Where(it.lat, it.lng) }
                                    when (mode) {
                                        SaleMode.SALE, SaleMode.RETURN -> {
                                            val paid = vm.paid?.toDoubleOrZero() ?: totals.total
                                            val uuid = repo.createVanSale(if (mode == SaleMode.SALE) "sale" else "return",
                                                customerKey!!, lines, totals.discount, paid.coerceAtMost(totals.total), where)
                                            SyncWorker.syncSoon(context)
                                            nav.navigate(Routes.receipt("sale", uuid)) { popUpTo(Routes.HOME) }
                                        }
                                        SaleMode.ORDER -> {
                                            repo.createOrder(customerKey!!, lines, vm.note)
                                            SyncWorker.syncSoon(context)
                                            Toast.makeText(context, "تم حفظ الطلبية وستُرسل للشركة", Toast.LENGTH_LONG).show()
                                            nav.popBackStack(Routes.HOME, false)
                                        }
                                        SaleMode.LOAD, SaleMode.UNLOAD -> {
                                            repo.createLoadRequest(if (mode == SaleMode.LOAD) "load" else "unload", lines, vm.note)
                                            SyncWorker.syncSoon(context)
                                            Toast.makeText(context, "تم إرسال الطلب لأمين المستودع", Toast.LENGTH_LONG).show()
                                            nav.popBackStack()
                                        }
                                    }
                                }
                            },
                        ) { Text(if (vm.saving) "جارٍ الحفظ..." else "تأكيد") }
                    }
                }
            }
        },
    ) { pad ->
        if (mode.needsCustomer && customerKey == null) {
            EmptyState("اختر زبوناً أولاً")
            return@Screen
        }
        if (!vm.reviewing) {
            Column(Modifier.padding(pad)) {
                SearchField(query, { query = it }, "ابحث عن مادة أو باركود")
                val categories = remember(products) { products.map { it.category }.filter { it.isNotBlank() }.distinct() }
                if (categories.size > 1) {
                    Row(Modifier.fillMaxWidth().horizontalScrollRow().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(category == null, { category = null }, { Text("الكل") })
                        categories.forEach { c -> FilterChip(category == c, { category = if (category == c) null else c }, { Text(c) }) }
                    }
                }
                val visible = products.filter { p ->
                    (category == null || p.category == category) &&
                        (query.isBlank() || p.name.contains(query, true) || p.code.contains(query, true) || p.barcode == query) &&
                        (!mode.limitToStock || (stock[p.id] ?: 0.0) > 0 || vm.qty.containsKey(p.id))
                }
                if (visible.isEmpty()) EmptyState(if (mode.limitToStock) "لا توجد مواد في السيارة" else "لا توجد مواد")
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(visible, key = { it.id }) { p ->
                        val q = vm.qty[p.id] ?: 0.0
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (q > 0) Green.copy(alpha = 0.08f) else Color.Transparent)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text("${Money.format(priceOf(p), decimals)} / ${p.uom}", color = Navy)
                                val s = stock[p.id] ?: 0.0
                                Text(
                                    (if (mode.stockKind == "van") "بالسيارة: " else "بالمستودع: ") + Money.formatQty(s),
                                    color = if (s > 0) Color.Gray else Red,
                                )
                            }
                            QtyStepper(q, { setQty(p, it) }, { editQty = p })
                        }
                        HorizontalDivider()
                    }
                }
            }
        } else {
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
                lines.forEach { l ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(l.name, fontWeight = FontWeight.SemiBold)
                            Text("${Money.formatQty(l.qty)} × ${Money.format(l.price, decimals)}", color = Color.Gray)
                        }
                        Text(Money.format(l.subtotal, decimals))
                    }
                    HorizontalDivider()
                }
                Spacer(Modifier.height(12.dp))
                InfoRow("المجموع", Money.format(totals.subtotal, decimals))
                if (mode == SaleMode.SALE || mode == SaleMode.RETURN) {
                    NumberField("خصم على الفاتورة", vm.discount, { vm.discount = it })
                    InfoRow("الصافي", Money.format(totals.total, decimals), bold = true)
                    Spacer(Modifier.height(8.dp))
                    val paidLabel = if (mode == SaleMode.SALE) "المبلغ المدفوع (فارغ = كامل المبلغ)" else "المبلغ المردود نقداً (فارغ = كامل المبلغ)"
                    NumberField(paidLabel, vm.paid ?: "", { vm.paid = it.ifBlank { null } })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ vm.paid = null }) { Text("نقداً كامل") }
                        TextButton({ vm.paid = "0" }) { Text("آجل كامل") }
                    }
                    val paid = vm.paid?.toDoubleOrZero() ?: totals.total
                    val due = totals.total - paid.coerceAtMost(totals.total)
                    if (due > 0) InfoRow(if (mode == SaleMode.SALE) "يُضاف لذمة الزبون" else "يُخصم من ذمة الزبون",
                        Money.format(due, decimals), color = Orange, bold = true)
                } else {
                    if (mode == SaleMode.ORDER) Text("الأسعار تقديرية، تُعتمد من الشركة عند تأكيد الطلبية", color = Color.Gray)
                    OutlinedTextField(vm.note, { vm.note = it }, label = { Text("ملاحظة") }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    editQty?.let { p ->
        var text by remember(p.id) { mutableStateOf(vm.qty[p.id]?.let { Money.formatQty(it) } ?: "") }
        AlertDialog(
            onDismissRequest = { editQty = null },
            title = { Text(p.name) },
            text = { NumberField("الكمية", text, { text = it }) },
            confirmButton = { Button({ setQty(p, text.toDoubleOrZero()); editQty = null }) { Text("موافق") } },
            dismissButton = { TextButton({ editQty = null }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun Modifier.horizontalScrollRow(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))
