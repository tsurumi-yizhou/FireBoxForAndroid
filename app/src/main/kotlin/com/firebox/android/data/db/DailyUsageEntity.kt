package com.firebox.android.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_usage")
data class DailyUsageEntity(
    @PrimaryKey val date: String, // yyyy-MM-dd
    @ColumnInfo(name = "requests") val requests: Long,
    @ColumnInfo(name = "tokens") val tokens: Long,
    @ColumnInfo(name = "price_usd_micros") val priceUsdMicros: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

data class UsageAggregate(
    @ColumnInfo(name = "requests") val requests: Long?,
    @ColumnInfo(name = "tokens") val tokens: Long?,
    @ColumnInfo(name = "price_usd_micros") val priceUsdMicros: Long?,
) {
    val safeRequests: Long get() = requests ?: 0L
    val safeTokens: Long get() = tokens ?: 0L
    val safePriceUsdMicros: Long get() = priceUsdMicros ?: 0L

    companion object {
        val Zero = UsageAggregate(0L, 0L, 0L)
    }
}

