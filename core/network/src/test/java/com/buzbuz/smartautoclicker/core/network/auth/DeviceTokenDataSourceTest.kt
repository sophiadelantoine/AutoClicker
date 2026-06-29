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

import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceTokenDataSourceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun saveThenReadRoundTrips_andPersistsToDisk() = runTest {
        // unique file per test: DataStore allows only one active instance per file per process
        val fileName = "device_token_roundtrip"
        val store = DeviceTokenDataSource(context, Dispatchers.IO, fileName)

        store.save(DeviceToken("jwt.abc", "tenant-1", "device-1", "2027-01-01T00:00:00Z"))

        val read = store.current()
        assertEquals("jwt.abc", read?.deviceToken)
        assertEquals("tenant-1", read?.tenantId)
        assertEquals("device-1", read?.deviceId)
        assertEquals("2027-01-01T00:00:00Z", read?.tokenExpiresAt)
        // the synchronous snapshot used by the interceptor matches
        assertEquals("jwt.abc", store.cachedToken()?.deviceToken)
        // file-backed => survives process death
        assertTrue(context.preferencesDataStoreFile(fileName).exists())
    }

    @Test
    fun clearRemovesToken() = runTest {
        val store = DeviceTokenDataSource(context, Dispatchers.IO, "device_token_clear")
        store.save(DeviceToken("jwt", "t", "d", "x"))
        store.clear()
        assertNull(store.current())
        assertNull(store.cachedToken())
    }
}
