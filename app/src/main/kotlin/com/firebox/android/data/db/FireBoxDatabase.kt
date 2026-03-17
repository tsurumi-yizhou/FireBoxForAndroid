package com.firebox.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        DailyUsageEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class FireBoxDatabase : RoomDatabase() {
    abstract fun dailyUsageDao(): DailyUsageDao
}
