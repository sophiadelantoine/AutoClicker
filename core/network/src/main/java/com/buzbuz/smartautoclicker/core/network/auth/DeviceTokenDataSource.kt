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
package com.buzbuz.smartautoclicker.core.network.auth

import android.content.Context

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

import com.buzbuz.smartautoclicker.core.base.PreferencesDataStore

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The persisted device-scoped credential. Treated as revocable, not secret (no extra encryption). */
data class DeviceToken(
    val deviceToken: String,
    val tenantId: String,
    val deviceId: String,
    val tokenExpiresAt: String,
)

/**
 * DataStore-backed store for the device token (mirrors SettingsDataSource). Values survive process
 * death. Keeps an in-memory snapshot ([cachedToken]) for synchronous OkHttp-interceptor access.
 */
class DeviceTokenDataSource(
    context: Context,
    dispatcher: CoroutineDispatcher,
    fileName: String = PREFERENCES_FILE_NAME,
) {

    private val dataStore = PreferencesDataStore(context, dispatcher, fileName)

    @Volatile
    private var cached: DeviceToken? = null

    val tokenFlow: Flow<DeviceToken?> = dataStore.data.map { it.toDeviceToken() }

    /** Synchronous snapshot for interceptors; null until [current] or [save] populates it. */
    fun cachedToken(): DeviceToken? = cached

    /** Read the persisted token (and refresh the in-memory snapshot). */
    suspend fun current(): DeviceToken? = dataStore.data.first().toDeviceToken().also { cached = it }

    suspend fun save(token: DeviceToken) {
        dataStore.edit { prefs ->
            prefs[KEY_DEVICE_TOKEN] = token.deviceToken
            prefs[KEY_TENANT_ID] = token.tenantId
            prefs[KEY_DEVICE_ID] = token.deviceId
            prefs[KEY_TOKEN_EXPIRES_AT] = token.tokenExpiresAt
        }
        cached = token
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_DEVICE_TOKEN)
            prefs.remove(KEY_TENANT_ID)
            prefs.remove(KEY_DEVICE_ID)
            prefs.remove(KEY_TOKEN_EXPIRES_AT)
        }
        cached = null
    }

    private fun Preferences.toDeviceToken(): DeviceToken? {
        val token = this[KEY_DEVICE_TOKEN] ?: return null
        return DeviceToken(
            deviceToken = token,
            tenantId = this[KEY_TENANT_ID].orEmpty(),
            deviceId = this[KEY_DEVICE_ID].orEmpty(),
            tokenExpiresAt = this[KEY_TOKEN_EXPIRES_AT].orEmpty(),
        )
    }

    companion object {
        const val PREFERENCES_FILE_NAME = "device_token"
        val KEY_DEVICE_TOKEN: Preferences.Key<String> = stringPreferencesKey("deviceToken")
        val KEY_TENANT_ID: Preferences.Key<String> = stringPreferencesKey("tenantId")
        val KEY_DEVICE_ID: Preferences.Key<String> = stringPreferencesKey("deviceId")
        val KEY_TOKEN_EXPIRES_AT: Preferences.Key<String> = stringPreferencesKey("tokenExpiresAt")
    }
}
