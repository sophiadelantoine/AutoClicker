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

import androidx.test.core.app.ApplicationProvider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceIdentityDataSourceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun clientDeviceId_isStableAndNotBoundByDefault() = runTest {
        val identity = DeviceIdentityDataSource(context, Dispatchers.IO, "id_stable")
        val first = identity.clientDeviceId()
        assertEquals("get-or-create returns the same id", first, identity.clientDeviceId())
        assertFalse(identity.isAccountBound())
        assertEquals(first, identity.effectiveDeviceId())
    }

    @Test
    fun reconcileAdoptsServerIdAndBinds_clearRevertsToClientId() = runTest {
        val identity = DeviceIdentityDataSource(context, Dispatchers.IO, "id_reconcile")
        val clientId = identity.clientDeviceId()

        identity.reconcileWithServer("server-device-id")
        assertEquals("server-device-id", identity.effectiveDeviceId())
        assertNotEquals(clientId, identity.effectiveDeviceId())
        assertTrue(identity.isAccountBound())

        identity.clearAccountBinding()
        assertFalse(identity.isAccountBound())
        assertEquals(clientId, identity.effectiveDeviceId())
    }
}
