package com.firebox.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyUsageDao {
    @Query("SELECT * FROM daily_usage WHERE date = :date LIMIT 1")
    fun observeByDate(date: String): Flow<DailyUsageEntity?>

    @Query(
        """
        SELECT 
          SUM(requests) AS requests,
          SUM(tokens) AS tokens,
          SUM(price_usd_micros) AS price_usd_micros
        FROM daily_usage
        WHERE date >= :fromDate AND date <= :toDate
        """
    )
    fun observeAggregate(fromDate: String, toDate: String): Flow<UsageAggregate?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailyUsageEntity)

    @Query(
        """
        UPDATE daily_usage 
        SET 
          requests = requests + :deltaRequests,
          tokens = tokens + :deltaTokens,
          price_usd_micros = price_usd_micros + :deltaPriceUsdMicros,
          updated_at_ms = :updatedAtMs
        WHERE date = :date
        """
    )
    suspend fun increment(
        date: String,
        deltaRequests: Long,
        deltaTokens: Long,
        deltaPriceUsdMicros: Long,
        updatedAtMs: Long,
    ): Int
}

