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
package com.buzbuz.smartautoclicker.core.observation.identity

import android.content.Context

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

import com.buzbuz.smartautoclicker.core.base.PreferencesDataStore

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first

import java.util.UUID

/**
 * Stable device identity for cloud sync. A client-generated id is created once and persisted; on
 * enrollment the server-returned deviceId is reconciled in and becomes the canonical id used to tag
 * observations. KEY_ACCOUNT_BOUND gates all sync.
 */
class DeviceIdentityDataSource(
    context: Context,
    dispatcher: CoroutineDispatcher,
    fileName: String = PREFERENCES_FILE_NAME,
) {

    private val dataStore = PreferencesDataStore(context, dispatcher, fileName)

    /** Stable client id, generated once and persisted (pre-enrollment identity). */
    suspend fun clientDeviceId(): String {
        dataStore.data.first()[KEY_CLIENT_DEVICE_ID]?.let { return it }
        val generated = UUID.randomUUID().toString()
        dataStore.edit { it[KEY_CLIENT_DEVICE_ID] = generated }
        return generated
    }

    /** The canonical id to tag observations with: the server-reconciled id if enrolled, else the client id. */
    suspend fun effectiveDeviceId(): String =
        dataStore.data.first()[KEY_RECONCILED_DEVICE_ID] ?: clientDeviceId()

    suspend fun reconciledDeviceId(): String? = dataStore.data.first()[KEY_RECONCILED_DEVICE_ID]

    /** On enroll: adopt the server's deviceId as canonical and mark the device account-bound. */
    suspend fun reconcileWithServer(serverDeviceId: String) {
        dataStore.edit { prefs ->
            prefs[KEY_RECONCILED_DEVICE_ID] = serverDeviceId
            prefs[KEY_ACCOUNT_BOUND] = true
        }
    }

    suspend fun isAccountBound(): Boolean = dataStore.data.first()[KEY_ACCOUNT_BOUND] ?: false

    /** Re-pair / token-revoked path: drop the binding (the client id is kept). */
    suspend fun clearAccountBinding() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_RECONCILED_DEVICE_ID)
            prefs[KEY_ACCOUNT_BOUND] = false
        }
    }

    companion object {
        const val PREFERENCES_FILE_NAME = "device_identity"
        val KEY_CLIENT_DEVICE_ID: Preferences.Key<String> = stringPreferencesKey("clientDeviceId")
        val KEY_RECONCILED_DEVICE_ID: Preferences.Key<String> = stringPreferencesKey("reconciledDeviceId")
        val KEY_ACCOUNT_BOUND: Preferences.Key<Boolean> = booleanPreferencesKey("accountBound")
    }
}
