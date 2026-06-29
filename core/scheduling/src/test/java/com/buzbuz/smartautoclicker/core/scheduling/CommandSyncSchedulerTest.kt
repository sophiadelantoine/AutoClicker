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
package com.buzbuz.smartautoclicker.core.scheduling

import android.content.Context

import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommandSyncSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun schedulePeriodic_enqueuesUniqueConnectedWork_andKeepsOnRepeat() {
        CommandSyncScheduler.schedulePeriodic(context)
        CommandSyncScheduler.schedulePeriodic(context) // KEEP: must not add a second

        val infos = workManager.getWorkInfosForUniqueWork(CommandPullWorker.WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
        assertEquals(NetworkType.CONNECTED, infos[0].constraints.requiredNetworkType)
    }

    @Test
    fun fetchNow_enqueuesAOneShotRequest() {
        CommandSyncScheduler.fetchNow(context)

        val infos = workManager.getWorkInfosByTag(CommandSyncScheduler.FETCH_NOW_TAG).get()
        assertTrue(infos.isNotEmpty())
        assertEquals(NetworkType.CONNECTED, infos[0].constraints.requiredNetworkType)
    }
}
