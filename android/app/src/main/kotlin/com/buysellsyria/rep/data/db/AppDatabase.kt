package com.buysellsyria.rep.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY category, name")
    fun observeAll(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun get(id: Long): ProductEntity?

    @Upsert
    suspend fun upsert(items: List<ProductEntity>)

    @Query("DELETE FROM products WHERE id NOT IN (:keep)")
    suspend fun deleteNotIn(keep: List<Long>)

    @Query("DELETE FROM products")
    suspend fun clear()
}

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY name")
    fun observeAll(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers")
    suspend fun all(): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE `key` = :key")
    suspend fun get(key: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE `key` = :key")
    fun observe(key: String): Flow<CustomerEntity?>

    @Query("SELECT * FROM customers WHERE serverId = :id")
    suspend fun byServerId(id: Long): CustomerEntity?

    @Query("SELECT * FROM customers WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): CustomerEntity?

    @Upsert
    suspend fun upsert(item: CustomerEntity)

    @Query("UPDATE customers SET due = due + :delta WHERE `key` = :key")
    suspend fun addDue(key: String, delta: Double)

    @Query("DELETE FROM customers WHERE serverId IS NOT NULL AND serverId NOT IN (:keep)")
    suspend fun deleteServerNotIn(keep: List<Long>)

    @Query("DELETE FROM customers")
    suspend fun clear()
}

@Dao
interface PriceDao {
    @Query("SELECT * FROM prices WHERE pricelistId = :pricelistId")
    fun observeFor(pricelistId: Long): Flow<List<PriceEntity>>

    @Query("SELECT price FROM prices WHERE pricelistId = :pricelistId AND productId = :productId")
    suspend fun price(pricelistId: Long, productId: Long): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(items: List<PriceEntity>)

    @Query("DELETE FROM prices")
    suspend fun clear()
}

@Dao
abstract class StockDao {
    @Query("SELECT * FROM stock WHERE kind = :kind")
    abstract fun observe(kind: String): Flow<List<StockEntity>>

    @Query("SELECT qty FROM stock WHERE kind = :kind AND productId = :productId")
    abstract suspend fun qty(kind: String, productId: Long): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(items: List<StockEntity>)

    @Query("DELETE FROM stock WHERE kind = :kind")
    abstract suspend fun clear(kind: String)

    @Query("DELETE FROM stock")
    abstract suspend fun clearAll()

    open suspend fun add(kind: String, productId: Long, delta: Double) {
        insert(listOf(StockEntity(kind, productId, (qty(kind, productId) ?: 0.0) + delta)))
    }
}

@Dao
interface VisitDao {
    @Query("SELECT * FROM visits WHERE checkOut IS NULL ORDER BY checkIn DESC LIMIT 1")
    fun observeOpen(): Flow<VisitEntity?>

    @Query("SELECT * FROM visits WHERE checkOut IS NULL ORDER BY checkIn DESC LIMIT 1")
    suspend fun open(): VisitEntity?

    @Query("SELECT * FROM visits WHERE checkIn >= :from ORDER BY checkIn DESC")
    fun observeSince(from: Long): Flow<List<VisitEntity>>

    @Upsert
    suspend fun upsert(item: VisitEntity)

    @Query("DELETE FROM visits WHERE checkIn < :before AND checkOut IS NOT NULL")
    suspend fun purgeBefore(before: Long)

    @Query("DELETE FROM visits")
    suspend fun clear()
}

@Dao
interface DocDao {
    @Insert
    suspend fun insertSale(item: VanSaleEntity)

    @Query("SELECT * FROM van_sales ORDER BY date DESC LIMIT 300")
    fun observeSales(): Flow<List<VanSaleEntity>>

    @Query("SELECT * FROM van_sales WHERE uuid = :uuid")
    suspend fun sale(uuid: String): VanSaleEntity?

    @Query("UPDATE van_sales SET printedCount = printedCount + 1 WHERE uuid = :uuid")
    suspend fun markPrinted(uuid: String)

    @Insert
    suspend fun insertOrder(item: OrderEntity)

    @Query("SELECT * FROM orders ORDER BY date DESC LIMIT 300")
    fun observeOrders(): Flow<List<OrderEntity>>

    @Insert
    suspend fun insertCollection(item: CollectionEntity)

    @Query("SELECT * FROM collections ORDER BY date DESC LIMIT 300")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE uuid = :uuid")
    suspend fun collection(uuid: String): CollectionEntity?

    @Query("UPDATE van_sales SET synced = 1 WHERE uuid = :uuid")
    suspend fun saleSynced(uuid: String)

    @Query("UPDATE orders SET synced = 1, serverName = :name WHERE uuid = :uuid")
    suspend fun orderSynced(uuid: String, name: String?)

    @Query("UPDATE collections SET synced = 1 WHERE uuid = :uuid")
    suspend fun collectionSynced(uuid: String)

    @Query("SELECT COUNT(*) FROM van_sales WHERE visitUuid = :visit")
    suspend fun salesForVisit(visit: String): Int

    @Query("SELECT COUNT(*) FROM orders WHERE visitUuid = :visit")
    suspend fun ordersForVisit(visit: String): Int

    @Query("SELECT COUNT(*) FROM collections WHERE visitUuid = :visit")
    suspend fun collectionsForVisit(visit: String): Int

    @Query("DELETE FROM van_sales WHERE synced = 1 AND date < :before")
    suspend fun purgeSales(before: Long)

    @Query("DELETE FROM orders WHERE synced = 1 AND date < :before")
    suspend fun purgeOrders(before: Long)

    @Query("DELETE FROM collections WHERE synced = 1 AND date < :before")
    suspend fun purgeCollections(before: Long)

    @Query("DELETE FROM van_sales")
    suspend fun clearSales()

    @Query("DELETE FROM orders")
    suspend fun clearOrders()

    @Query("DELETE FROM collections")
    suspend fun clearCollections()
}

@Dao
interface LoadDao {
    @Query("SELECT * FROM loads ORDER BY date DESC")
    fun observeAll(): Flow<List<LoadEntity>>

    @Upsert
    suspend fun upsert(items: List<LoadEntity>)

    @Query("DELETE FROM loads WHERE serverId IS NOT NULL")
    suspend fun clearServer()

    @Query("DELETE FROM loads WHERE uuid = :uuid AND serverId IS NULL")
    suspend fun deleteLocal(uuid: String)

    @Query("DELETE FROM loads")
    suspend fun clear()
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(item: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY id LIMIT :limit")
    suspend fun batch(limit: Int): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY id")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY id")
    fun observeAll(): Flow<List<OutboxEntity>>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM outbox WHERE uuid = :uuid")
    suspend fun delete(uuid: String)

    @Query("UPDATE outbox SET status = 'error', attempts = attempts + 1, lastError = :message WHERE uuid = :uuid")
    suspend fun markError(uuid: String, message: String)

    @Query("DELETE FROM outbox")
    suspend fun clear()
}

@Database(
    entities = [
        ProductEntity::class, CustomerEntity::class, PriceEntity::class, StockEntity::class,
        VisitEntity::class, VanSaleEntity::class, OrderEntity::class, CollectionEntity::class,
        LoadEntity::class, OutboxEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun products(): ProductDao
    abstract fun customers(): CustomerDao
    abstract fun prices(): PriceDao
    abstract fun stock(): StockDao
    abstract fun visits(): VisitDao
    abstract fun docs(): DocDao
    abstract fun loads(): LoadDao
    abstract fun outbox(): OutboxDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "salesrep.db").build()
    }
}
