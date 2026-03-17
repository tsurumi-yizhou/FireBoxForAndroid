package com.firebox.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.fireBoxPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "firebox_prefs",
)

