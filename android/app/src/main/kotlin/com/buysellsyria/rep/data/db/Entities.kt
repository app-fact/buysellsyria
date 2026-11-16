package com.buysellsyria.rep.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val code: String,
    val barcode: String,
    val uom: String,
    val category: String,
    val listPrice: Double,
    val loadPrice: Double,
)

/**
 * [key] is stable for the life of the customer on this phone:
 * "srv-<id>" for customers coming from Odoo, a UUID for customers created offline.
 */
@Entity(tableName = "customers", indices = [Index("serverId", unique = true)])
data class CustomerEntity(
    @PrimaryKey val key: String,
    val serverId: Long?,
    val uuid: String?,
    val name: String,
    val phone: String,
    val street: String,
    val city: String,
    val lat: Double?,
    val lng: Double?,
    val pricelistId: Long?,
    /** Balance from Odoo + effect of operations not yet synced. */
    val due: Double,
)

@Entity(tableName = "prices", primaryKeys = ["pricelistId", "productId"])
data class PriceEntity(val pricelistId: Long, val productId: Long, val price: Double)

/** kind = "van" | "main" */
@Entity(tableName = "stock", primaryKeys = ["kind", "productId"])
data class StockEntity(val kind: String, val productId: Long, val qty: Double)

@Entity(tableName = "visits")
data class VisitEntity(
    @PrimaryKey val uuid: String,
    val customerKey: String,
    val customerName: String,
    val checkIn: Long,
    val checkOut: Long? = null,
    val openMode: String,
    val closeMode: String? = null,
    val inDistance: Double? = null,
    val outDistance: Double? = null,
    val noSaleReason: String? = null,
)

@Serializable
data class DocLine(
    val productId: Long,
    val name: String,
    val uom: String = "",
    val qty: Double,
    val price: Double,
    val discount: Double = 0.0,
) {
    val subtotal get() = qty * price * (1 - discount / 100.0)
}

/** Van sale or return (type = "sale" | "return"). */
@Entity(tableName = "van_sales")
data class VanSaleEntity(
    @PrimaryKey val uuid: String,
    val number: String,
    val type: String,
    val customerKey: String,
    val customerName: String,
    val visitUuid: String?,
    val date: Long,
    val linesJson: String,
    val subtotal: Double,
    val discount: Double,
    val total: Double,
    val paid: Double,
    val printedCount: Int = 0,
    val synced: Boolean = false,
)

@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey val uuid: String,
    val customerKey: String,
    val customerName: String,
    val visitUuid: String?,
    val date: Long,
    val linesJson: String,
    val totalEstimate: Double,
    val note: String,
    val serverName: String? = null,
    val synced: Boolean = false,
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val uuid: String,
    val number: String,
    val customerKey: String,
    val customerName: String,
    val visitUuid: String?,
    val date: Long,
    val amount: Double,
    val note: String,
    val synced: Boolean = false,
)

/** Van loading / unloading. From Odoo ([serverId] set) or requested from the phone and not yet synced. */
@Entity(tableName = "loads")
data class LoadEntity(
    @PrimaryKey val key: String,
    val serverId: Long?,
    val uuid: String?,
    val name: String,
    val type: String,
    val state: String,
    val date: Long,
    val amountTotal: Double,
    val linesJson: String,
    val invoice: String?,
)

/** Operation waiting to be pushed to Odoo. status = "pending" | "error". */
@Entity(tableName = "outbox", indices = [Index("uuid", unique = true)])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val type: String,
    val payload: String,
    val createdAt: Long,
    val status: String = "pending",
    val attempts: Int = 0,
    val lastError: String? = null,
    val label: String = "",
)
