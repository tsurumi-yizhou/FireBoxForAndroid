package com.firebox.android

import android.content.Context
import com.firebox.android.data.FireBoxConfigRepository
import com.firebox.android.data.FireBoxStatsRepository

/**
 * Simple in-process service locator to share singletons between the UI (Activity/Compose)
 * and the background Service.
 *
 * Note: This is safe only when everything runs in the same process. If you move the Service
 * to another process (android:process), you cannot share memory singletons and should rely on
 * a proper IPC boundary + multi-process-safe storage.
 */
object FireBoxGraph {
    @Volatile
    private var configRepository: FireBoxConfigRepository? = null

    @Volatile
    private var statsRepository: FireBoxStatsRepository? = null

    @Volatile
    private var connectionStateHolder: ConnectionStateHolder? = null

    fun configRepository(context: Context): FireBoxConfigRepository {
        val existing = configRepository
        if (existing != null) return existing
        return synchronized(this) {
            val again = configRepository
            if (again != null) again
            else FireBoxConfigRepository(context.applicationContext).also { configRepository = it }
        }
    }

    fun statsRepository(context: Context): FireBoxStatsRepository {
        val existing = statsRepository
        if (existing != null) return existing
        return synchronized(this) {
            val again = statsRepository
            if (again != null) again
            else FireBoxStatsRepository(context.applicationContext).also { statsRepository = it }
        }
    }

    fun connectionStateHolder(): ConnectionStateHolder {
        val existing = connectionStateHolder
        if (existing != null) return existing
        return synchronized(this) {
            val again = connectionStateHolder
            if (again != null) again
            else ConnectionStateHolder().also { connectionStateHolder = it }
        }
    }
}

