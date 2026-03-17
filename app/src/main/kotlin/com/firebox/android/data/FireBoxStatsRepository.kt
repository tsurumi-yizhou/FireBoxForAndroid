package com.firebox.android.data

import android.content.Context
import androidx.room.Room
import com.firebox.android.data.db.DailyUsageEntity
import com.firebox.android.data.db.FireBoxDatabase
import com.firebox.android.data.db.UsageAggregate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class FireBoxStatsRepository(context: Context) {
    private val appContext = context.applicationContext

    private val db: FireBoxDatabase =
        Room.databaseBuilder(appContext, FireBoxDatabase::class.java, "firebox.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    private val dao = db.dailyUsageDao()

    fun observeToday(): Flow<DailyUsageEntity?> = dao.observeByDate(today())

    fun observeYesterday(): Flow<DailyUsageEntity?> = dao.observeByDate(yesterday())

    fun observeMonthToDate(): Flow<UsageAggregate> {
        val now = LocalDate.now()
        val from = now.withDayOfMonth(1).format(dateFormatter)
        val to = now.format(dateFormatter)
        return dao.observeAggregate(from, to).map { it ?: UsageAggregate.Zero }
    }

    suspend fun recordUsage(
        date: String = today(),
        deltaRequests: Long,
        deltaTokens: Long,
        deltaPriceUsdMicros: Long,
    ) {
        val updated =
            dao.increment(
                date = date,
                deltaRequests = deltaRequests,
                deltaTokens = deltaTokens,
                deltaPriceUsdMicros = deltaPriceUsdMicros,
                updatedAtMs = System.currentTimeMillis(),
            )

        if (updated == 0) {
            dao.upsert(
                DailyUsageEntity(
                    date = date,
                    requests = deltaRequests,
                    tokens = deltaTokens,
                    priceUsdMicros = deltaPriceUsdMicros,
                    updatedAtMs = System.currentTimeMillis(),
                )
            )
        }
    }

    companion object {
        private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        fun today(): String = LocalDate.now().format(dateFormatter)

        fun yesterday(): String = LocalDate.now().minusDays(1).format(dateFormatter)
    }
}

