package com.buysellsyria.rep.data

import androidx.room.withTransaction
import com.buysellsyria.rep.core.money.Line
import com.buysellsyria.rep.core.money.Money
import com.buysellsyria.rep.core.numbering.DocNumber
import com.buysellsyria.rep.data.db.AppDatabase
import com.buysellsyria.rep.data.db.CollectionEntity
import com.buysellsyria.rep.data.db.CustomerEntity
import com.buysellsyria.rep.data.db.DocLine
import com.buysellsyria.rep.data.db.LoadEntity
import com.buysellsyria.rep.data.db.OrderEntity
import com.buysellsyria.rep.data.db.OutboxEntity
import com.buysellsyria.rep.data.db.PriceEntity
import com.buysellsyria.rep.data.db.ProductEntity
import com.buysellsyria.rep.data.db.StockEntity
import com.buysellsyria.rep.data.db.VanSaleEntity
import com.buysellsyria.rep.data.db.VisitEntity
import com.buysellsyria.rep.data.remote.ApiClient
import com.buysellsyria.rep.data.remote.ApiException
import com.buysellsyria.rep.data.remote.OpDto
import com.buysellsyria.rep.data.remote.PullResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

sealed class SyncState {
    data object Idle : SyncState()
    data object Running : SyncState()
    data class Done(val at: Long, val errors: Int) : SyncState()
    data class Failed(val message: String) : SyncState()
}

/** Where a GPS-based action happened (optional). */
data class Where(val lat: Double, val lng: Double, val distance: Double? = null)

class Repository(
    private val db: AppDatabase,
    val session: Session,
    val api: ApiClient,
) {
    private val syncMutex = Mutex()
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState

    private val json get() = api.json
    private val linesSer = ListSerializer(DocLine.serializer())

    // Observables for the UI
    val products = db.products().observeAll()
    val customers = db.customers().observeAll()
    val vanStock = db.stock().observe("van")
    val mainStock = db.stock().observe("main")
    val openVisit = db.visits().observeOpen()
    val sales = db.docs().observeSales()
    val orders = db.docs().observeOrders()
    val collections = db.docs().observeCollections()
    val loads = db.loads().observeAll()
    val outbox = db.outbox().observeAll()
    val pendingCount = db.outbox().observeCount()
    fun customer(key: String) = db.customers().observe(key)
    fun visitsSince(from: Long) = db.visits().observeSince(from)
    fun prices(pricelistId: Long) = db.prices().observeFor(pricelistId)

    fun linesOf(text: String): List<DocLine> = json.decodeFromString(linesSer, text)

    fun pricelistFor(customer: CustomerEntity?): Long? =
        customer?.pricelistId ?: session.profile.value?.defaultPricelistId

    // ------------------------------------------------------------------
    // Login / logout
    // ------------------------------------------------------------------
    suspend fun login(serverUrl: String, code: String, pin: String) {
        session.serverUrl = normalizeUrl(serverUrl)
        session.token = null
        val res = api.login(code.trim(), pin.trim(), session.deviceId)
        session.token = res.token
        session.saveProfile(Profile(res.rep, res.company, res.currency, res.config))
        session.saleSeq = maxOf(session.saleSeq, res.rep.last_van_sale_seq)
        session.lastPull = null
        sync()
    }

    private fun normalizeUrl(url: String): String {
        val u = url.trim().trimEnd('/')
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
    }

    suspend fun logout() {
        db.withTransaction {
            db.outbox().clear(); db.products().clear(); db.customers().clear(); db.prices().clear()
            db.stock().clearAll(); db.visits().clear(); db.docs().clearSales(); db.docs().clearOrders()
            db.docs().clearCollections(); db.loads().clear()
        }
        session.logout()
    }

    // ------------------------------------------------------------------
    // Local operations (work offline; queued in the outbox)
    // ------------------------------------------------------------------
    private fun uuid() = UUID.randomUUID().toString()

    private suspend fun enqueue(type: String, payload: JsonObject, uuid: String = uuid(), label: String = "") {
        db.outbox().insert(
            OutboxEntity(uuid = uuid, type = type, payload = payload.toString(),
                createdAt = System.currentTimeMillis(), label = label)
        )
    }

    private fun JsonObjectBuilder.putCustomer(c: CustomerEntity) {
        if (c.serverId != null) put("customer_id", c.serverId)
        if (c.uuid != null) put("customer_uuid", c.uuid)
    }

    private fun JsonObjectBuilder.putWhere(w: Where?) {
        if (w != null) {
            put("latitude", w.lat); put("longitude", w.lng)
            w.distance?.let { put("distance", it) }
        }
    }

    suspend fun createCustomer(name: String, phone: String, street: String, city: String, where: Where?): String {
        val key = uuid()
        val c = CustomerEntity(key, null, key, name.trim(), phone.trim(), street.trim(), city.trim(),
            where?.lat, where?.lng, session.profile.value?.defaultPricelistId, 0.0)
        db.withTransaction {
            db.customers().upsert(c)
            enqueue("customer_upsert", buildJsonObject {
                put("customer_uuid", key); put("name", c.name); put("phone", c.phone)
                put("street", c.street); put("city", c.city); putWhere(where)
            }, label = "زبون جديد: ${c.name}")
        }
        return key
    }

    suspend fun setCustomerLocation(key: String, lat: Double, lng: Double) {
        val c = db.customers().get(key) ?: return
        db.withTransaction {
            db.customers().upsert(c.copy(lat = lat, lng = lng))
            enqueue("customer_location", buildJsonObject {
                putCustomer(c); put("latitude", lat); put("longitude", lng)
            }, label = "موقع: ${c.name}")
        }
    }

    suspend fun openVisit(customerKey: String, mode: String, where: Where?): VisitEntity? {
        val c = db.customers().get(customerKey) ?: return null
        db.visits().open()?.let { if (it.customerKey == customerKey) return it else closeVisit(mode, where, null) }
        val visit = VisitEntity(uuid(), c.key, c.name, System.currentTimeMillis(), openMode = mode,
            inDistance = where?.distance)
        db.withTransaction {
            db.visits().upsert(visit)
            enqueue("visit_open", buildJsonObject {
                put("visit_uuid", visit.uuid); putCustomer(c); put("check_in", Iso.format(visit.checkIn))
                put("mode", mode); putWhere(where)
            }, label = "بدء زيارة: ${c.name}")
        }
        return visit
    }

    suspend fun closeVisit(mode: String, where: Where?, noSaleReason: String?) {
        val v = db.visits().open() ?: return
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.visits().upsert(v.copy(checkOut = now, closeMode = mode, outDistance = where?.distance,
                noSaleReason = noSaleReason))
            enqueue("visit_close", buildJsonObject {
                put("visit_uuid", v.uuid); put("check_out", Iso.format(now)); put("mode", mode)
                putWhere(where); noSaleReason?.let { put("no_sale_reason", it) }
            }, label = "نهاية زيارة: ${v.customerName}")
        }
    }

    /** Did anything happen during the open visit? Used to ask for a "no sale" reason. */
    suspend fun visitHasActivity(visitUuid: String): Boolean =
        db.docs().salesForVisit(visitUuid) + db.docs().ordersForVisit(visitUuid) +
            db.docs().collectionsForVisit(visitUuid) > 0

    /** Van sale ("sale") or customer return ("return"). Returns the new document uuid. */
    suspend fun createVanSale(type: String, customerKey: String, lines: List<DocLine>, discount: Double,
                              paid: Double, where: Where?): String {
        val profile = session.profile.value ?: error("not logged in")
        val c = db.customers().get(customerKey) ?: error("customer")
        val decimals = session.decimals
        val totals = Money.totals(lines.map { Line(it.productId, it.name, it.qty, it.price, it.discount) },
            discount, paid, decimals)
        val visit = db.visits().open()?.takeIf { it.customerKey == customerKey }
        val docUuid = uuid()
        db.withTransaction {
            val seq = session.saleSeq + 1
            session.saleSeq = seq
            val number = DocNumber.sale(profile.rep.invoice_prefix, seq)
            db.docs().insertSale(VanSaleEntity(docUuid, number, type, c.key, c.name, visit?.uuid,
                System.currentTimeMillis(), json.encodeToString(linesSer, lines), totals.subtotal,
                totals.discount, totals.total, totals.paid))
            val sign = if (type == "return") 1.0 else -1.0
            lines.forEach { db.stock().add("van", it.productId, sign * it.qty) }
            db.customers().addDue(c.key, -sign * totals.due)
            enqueue("van_sale", buildJsonObject {
                put("number", number); put("seq", seq); put("sale_type", type); putCustomer(c)
                visit?.let { put("visit_uuid", it.uuid) }
                put("date", Iso.format(System.currentTimeMillis()))
                put("discount_amount", totals.discount); put("paid", totals.paid); put("total", totals.total)
                putWhere(where)
                putJsonArray("lines") {
                    lines.forEach { l ->
                        addJsonObject {
                            put("product_id", l.productId); put("qty", l.qty)
                            put("price_unit", l.price); put("discount", l.discount)
                        }
                    }
                }
            }, uuid = docUuid, label = (if (type == "return") "مرتجع " else "فاتورة ") + number)
        }
        return docUuid
    }

    suspend fun createOrder(customerKey: String, lines: List<DocLine>, note: String): String {
        val c = db.customers().get(customerKey) ?: error("customer")
        val visit = db.visits().open()?.takeIf { it.customerKey == customerKey }
        val docUuid = uuid()
        db.withTransaction {
            db.docs().insertOrder(OrderEntity(docUuid, c.key, c.name, visit?.uuid, System.currentTimeMillis(),
                json.encodeToString(linesSer, lines), lines.sumOf { it.subtotal }, note))
            enqueue("sale_order", buildJsonObject {
                putCustomer(c); visit?.let { put("visit_uuid", it.uuid) }
                put("date", Iso.format(System.currentTimeMillis())); put("note", note)
                putJsonArray("lines") {
                    lines.forEach { l -> addJsonObject { put("product_id", l.productId); put("qty", l.qty) } }
                }
            }, uuid = docUuid, label = "طلبية: ${c.name}")
        }
        return docUuid
    }

    suspend fun createCollection(customerKey: String, amount: Double, note: String): String {
        val profile = session.profile.value ?: error("not logged in")
        val c = db.customers().get(customerKey) ?: error("customer")
        val visit = db.visits().open()?.takeIf { it.customerKey == customerKey }
        val docUuid = uuid()
        db.withTransaction {
            val seq = session.collectionSeq + 1
            session.collectionSeq = seq
            val number = DocNumber.collection(profile.rep.invoice_prefix, seq)
            db.docs().insertCollection(CollectionEntity(docUuid, number, c.key, c.name, visit?.uuid,
                System.currentTimeMillis(), amount, note))
            db.customers().addDue(c.key, -amount)
            enqueue("collection", buildJsonObject {
                put("number", number); putCustomer(c); visit?.let { put("visit_uuid", it.uuid) }
                put("date", Iso.format(System.currentTimeMillis())); put("amount", amount); put("note", note)
            }, uuid = docUuid, label = "تحصيل $number")
        }
        return docUuid
    }

    suspend fun createLoadRequest(type: String, lines: List<DocLine>, note: String): String {
        val docUuid = uuid()
        db.withTransaction {
            db.loads().upsert(listOf(LoadEntity(docUuid, null, docUuid, "بانتظار المزامنة", type, "pending",
                System.currentTimeMillis(), lines.sumOf { it.subtotal }, json.encodeToString(linesSer, lines), null)))
            enqueue("load_request", buildJsonObject {
                put("type", type); put("note", note)
                putJsonArray("lines") {
                    lines.forEach { l -> addJsonObject { put("product_id", l.productId); put("qty", l.qty) } }
                }
            }, uuid = docUuid, label = if (type == "load") "طلب تحميل" else "طلب تنزيل")
        }
        return docUuid
    }

    suspend fun sale(uuid: String) = db.docs().sale(uuid)
    suspend fun collection(uuid: String) = db.docs().collection(uuid)
    suspend fun markPrinted(uuid: String) = db.docs().markPrinted(uuid)
    suspend fun allCustomers() = db.customers().all()
    suspend fun currentOpenVisit() = db.visits().open()
    suspend fun customerNow(key: String) = db.customers().get(key)

    // ------------------------------------------------------------------
    // Sync
    // ------------------------------------------------------------------
    /** Push the outbox, then pull fresh data. Safe to call concurrently. */
    suspend fun sync(): SyncState = syncMutex.withLock {
        if (session.token == null) return SyncState.Idle
        _syncState.value = SyncState.Running
        val result = try {
            val errors = push()
            pull()
            session.lastSyncAt = System.currentTimeMillis()
            db.visits().purgeBefore(System.currentTimeMillis() - 7 * DAY)
            val keep = System.currentTimeMillis() - 30 * DAY
            db.docs().purgeSales(keep); db.docs().purgeOrders(keep); db.docs().purgeCollections(keep)
            SyncState.Done(System.currentTimeMillis(), errors)
        } catch (e: ApiException) {
            if (e.httpStatus == 401) session.token = null
            SyncState.Failed(e.message ?: "error")
        } catch (e: Exception) {
            SyncState.Failed(e.message ?: e.javaClass.simpleName)
        }
        _syncState.value = result
        result
    }

    private suspend fun push(): Int {
        var errors = 0
        val all = db.outbox().all()
        for (chunk in all.chunked(50)) {
            val ops = chunk.map { OpDto(it.uuid, it.type, api.parsePayload(it.payload), Iso.format(it.createdAt)) }
            val res = api.push(ops)
            val byUuid = res.results.associateBy { it.uuid }
            for (op in chunk) {
                val r = byUuid[op.uuid] ?: continue
                if (r.status == "ok") {
                    db.withTransaction { onPushed(op, r.res_id, r.name); db.outbox().delete(op.uuid) }
                } else {
                    errors++
                    db.outbox().markError(op.uuid, r.message ?: "error")
                }
            }
        }
        return errors
    }

    private suspend fun onPushed(op: OutboxEntity, resId: Long?, name: String?) {
        when (op.type) {
            "customer_upsert" -> {
                val uuid = api.parsePayload(op.payload)["customer_uuid"]?.jsonPrimitive?.content
                val c = uuid?.let { db.customers().byUuid(it) }
                if (c != null && resId != null && c.serverId == null) db.customers().upsert(c.copy(serverId = resId))
            }
            "van_sale" -> db.docs().saleSynced(op.uuid)
            "sale_order" -> db.docs().orderSynced(op.uuid, name)
            "collection" -> db.docs().collectionSynced(op.uuid)
            "load_request" -> db.loads().deleteLocal(op.uuid)
        }
    }

    private suspend fun pull() {
        val data = api.pull(session.lastPull)
        val pending = db.outbox().all()
        db.withTransaction { applyPull(data, pending) }
        session.lastPull = data.server_time
    }

    /**
     * Apply Odoo data, then re-apply the effect of operations not yet accepted by
     * Odoo, so van stock and customer balances shown to the rep stay right.
     */
    private suspend fun applyPull(d: PullResponse, pending: List<OutboxEntity>) {
        session.saveProfile(Profile(d.rep, d.company, d.currency, d.config, d.default_pricelist_id, d.rep_balance))
        session.saleSeq = maxOf(session.saleSeq, d.rep.last_van_sale_seq)

        if (d.products.isNotEmpty()) db.products().upsert(d.products.map {
            ProductEntity(it.id, it.name, it.default_code, it.barcode, it.uom, it.category, it.list_price, it.load_price)
        })
        // prune in chunks: SQLite limits the number of bound variables
        val keepProducts = d.product_ids.toHashSet()
        db.products().ids().filterNot { it in keepProducts }.chunked(500).forEach { db.products().deleteIds(it) }

        for (c in d.customers) {
            val existing = db.customers().byServerId(c.id) ?: c.uuid?.let { db.customers().byUuid(it) }
            db.customers().upsert(CustomerEntity(
                key = existing?.key ?: "srv-${c.id}", serverId = c.id, uuid = c.uuid ?: existing?.uuid,
                name = c.name, phone = c.phone, street = c.street, city = c.city,
                lat = c.latitude, lng = c.longitude, pricelistId = c.pricelist_id, due = c.due,
            ))
        }
        val keepCustomers = d.customer_ids.toHashSet()
        db.customers().serverIds().filterNot { it in keepCustomers }.chunked(500).forEach { db.customers().deleteServerIds(it) }

        db.prices().clear()
        db.prices().insert(d.prices.flatMap { (pl, m) ->
            m.map { (pid, price) -> PriceEntity(pl.toLong(), pid.toLong(), price) }
        })

        db.stock().clearAll()
        db.stock().insert(d.van_stock.map { StockEntity("van", it.key.toLong(), it.value) } +
            d.main_stock.map { StockEntity("main", it.key.toLong(), it.value) })

        db.loads().clearServer()
        db.loads().upsert(d.loads.map { l ->
            LoadEntity("srv-${l.id}", l.id, l.uuid, l.name, l.type, l.state, Iso.parse(l.date), l.amount_total,
                json.encodeToString(linesSer, l.lines.map { DocLine(it.product_id, it.product, "", it.qty, it.price_unit) }),
                l.invoice)
        })

        // Customer dues from Odoo (for all customers, not just changed ones)
        for ((id, due) in d.customer_dues) {
            db.customers().byServerId(id.toLong())?.let { db.customers().upsert(it.copy(due = due)) }
        }

        // Re-apply unsynced operations
        for (op in pending) {
            val p = runCatching { api.parsePayload(op.payload) }.getOrNull() ?: continue
            val key = customerKeyOf(p)
            when (op.type) {
                "van_sale" -> {
                    val sign = if (p["sale_type"]?.jsonPrimitive?.content == "return") 1.0 else -1.0
                    p["lines"]?.jsonArray?.forEach { l ->
                        val o = l.jsonObject
                        db.stock().add("van", o["product_id"]!!.jsonPrimitive.long, sign * o["qty"]!!.jsonPrimitive.double)
                    }
                    val due = (p["total"]?.jsonPrimitive?.double ?: 0.0) - (p["paid"]?.jsonPrimitive?.double ?: 0.0)
                    if (key != null) db.customers().addDue(key, -sign * due)
                }
                "collection" -> if (key != null) db.customers().addDue(key, -(p["amount"]?.jsonPrimitive?.double ?: 0.0))
            }
        }
    }

    private suspend fun customerKeyOf(p: JsonObject): String? {
        p["customer_id"]?.jsonPrimitive?.long?.let { id -> db.customers().byServerId(id)?.let { return it.key } }
        p["customer_uuid"]?.jsonPrimitive?.content?.let { u -> db.customers().byUuid(u)?.let { return it.key } }
        return null
    }

    companion object {
        const val DAY = 24L * 3600 * 1000
    }
}
