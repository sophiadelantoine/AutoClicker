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
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder

import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoOpUploadWorkerTest {

    @Test
    fun doWork_resolvesInjectedDependencyAndSucceeds() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val marker = SchedulingMarker()

        // A WorkerFactory that injects the marker, mirroring what the HiltWorkerFactory does at runtime.
        val worker = TestListenableWorkerBuilder<NoOpUploadWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): NoOpUploadWorker = NoOpUploadWorker(appContext, workerParameters, marker)
            })
            .build()

        val result = worker.doWork()

        assertTrue("worker succeeds", result is ListenableWorker.Result.Success)
        assertTrue("injected dependency was resolved and used", marker.ran)
    }
}
