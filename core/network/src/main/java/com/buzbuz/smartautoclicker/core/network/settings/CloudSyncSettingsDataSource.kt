/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker.core.network.settings

import android.content.Context

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey

import com.buzbuz.smartautoclicker.core.base.PreferencesDataStore

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The cloud_sync_enabled toggle (default OFF, Addendum §3). Enabling it does NOT enroll the device;
 * enrollment (KEY_ACCOUNT_BOUND) remains the source of truth. The uploader requires both.
 */
class CloudSyncSettingsDataSource(
    context: Context,
    dispatcher: CoroutineDispatcher,
    fileName: String = PREFERENCES_FILE_NAME,
) {

    private val dataStore = PreferencesDataStore(context, dispatcher, fileName)

    val enabledFlow: Flow<Boolean> = dataStore.data.map { it[KEY_CLOUD_SYNC_ENABLED] ?: false }

    suspend fun isEnabled(): Boolean = dataStore.data.first()[KEY_CLOUD_SYNC_ENABLED] ?: false

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_CLOUD_SYNC_ENABLED] = enabled }
    }

    companion object {
        const val PREFERENCES_FILE_NAME = "cloud_sync"
        val KEY_CLOUD_SYNC_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("cloudSyncEnabled")
    }
}
