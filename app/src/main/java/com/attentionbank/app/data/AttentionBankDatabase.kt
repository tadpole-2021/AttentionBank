package com.attentionbank.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "app_selection")
data class AppSelectionEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val role: String,
    val multiplier: String,
    val isSystemApp: Boolean,
)

@Entity(tableName = "balance_state")
data class BalanceStateEntity(
    @PrimaryKey val id: Int = 1,
    val todayBalanceSeconds: Int,
    val carryOverBalanceSeconds: Int,
    val todayEarnedSeconds: Int,
    val todaySpentSeconds: Int,
    val spendRemainder: Double,
    val activePackageName: String?,
    val lastLedgerUpdateMillis: Long,
    val settlementToken: Long,
)

@Entity(tableName = "usage_ledger")
data class UsageLedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val packageName: String,
    val eventType: String,
    val balanceDeltaSeconds: Int,
    val actualUsageSeconds: Int,
    val happenedAtMillis: Long,
)

@Dao
interface AppSelectionDao {
    @Query("SELECT * FROM app_selection ORDER BY role DESC, appName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<AppSelectionEntity>>

    @Query("SELECT * FROM app_selection")
    suspend fun getAll(): List<AppSelectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppSelectionEntity)

    @Query("DELETE FROM app_selection WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface BalanceStateDao {
    @Query("SELECT * FROM balance_state WHERE id = 1")
    fun observe(): Flow<BalanceStateEntity?>

    @Query("SELECT * FROM balance_state WHERE id = 1")
    suspend fun get(): BalanceStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BalanceStateEntity)
}

@Dao
interface UsageLedgerDao {
    @Query("SELECT * FROM usage_ledger ORDER BY happenedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<UsageLedgerEntity>>

    @Insert
    suspend fun insert(entity: UsageLedgerEntity)
}

@Database(
    entities = [
        AppSelectionEntity::class,
        BalanceStateEntity::class,
        UsageLedgerEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AttentionBankDatabase : RoomDatabase() {
    abstract fun appSelectionDao(): AppSelectionDao
    abstract fun balanceStateDao(): BalanceStateDao
    abstract fun usageLedgerDao(): UsageLedgerDao
}
