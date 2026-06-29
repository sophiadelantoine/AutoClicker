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

import androidx.test.core.app.ApplicationProvider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudSyncSettingsDataSourceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun defaultsOff_andTogglesOn() = runTest {
        val settings = CloudSyncSettingsDataSource(context, Dispatchers.IO, "cloud_sync_test")

        assertFalse("cloud_sync_enabled defaults OFF", settings.isEnabled())

        settings.setEnabled(true)
        assertTrue(settings.isEnabled())

        settings.setEnabled(false)
        assertFalse(settings.isEnabled())
    }
}
