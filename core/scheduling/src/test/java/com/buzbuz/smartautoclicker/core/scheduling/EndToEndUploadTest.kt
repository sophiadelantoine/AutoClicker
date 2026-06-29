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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder

import com.buzbuz.smartautoclicker.core.capture.sync.ObservationSyncRepository
import com.buzbuz.smartautoclicker.core.database.ClickDatabase
import com.buzbuz.smartautoclicker.core.database.entity.ObservationEntity
import com.buzbuz.smartautoclicker.core.network.TraxIntelApiService
import com.buzbuz.smartautoclicker.core.network.auth.DeviceTokenDataSource
import com.buzbuz.smartautoclicker.core.network.dto.EnrollRequestDto
import com.buzbuz.smartautoclicker.core.network.dto.EnrollResponseDto
import com.buzbuz.smartautoclicker.core.network.dto.ObservationBatchRequestDto
import com.buzbuz.smartautoclicker.core.network.dto.ObservationBatchResponseDto
import com.buzbuz.smartautoclicker.core.network.dto.ObservationResultDto
import com.buzbuz.smartautoclicker.core.network.enrollment.DeviceDescriptor
import com.buzbuz.smartautoclicker.core.network.enrollment.EnrollmentRepository
import com.buzbuz.smartautoclicker.core.network.enrollment.EnrollmentResult
import com.buzbuz.smartautoclicker.core.network.settings.CloudSyncSettingsDataSource
import com.buzbuz.smartautoclicker.core.observation.identity.DeviceIdentityDataSource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

import java.io.IOException
import java.util.UUID

/**
 * End-to-end vertical slice (device -> cloud), all real components against a mock /v1 backend that
 * dedups on observation id (like the server's ON CONFLICT (tenant_id, id)): enroll -> enable sync ->
 * persist one captured Observation -> run the uploader -> SYNCED + exactly one server-side row, and a
 * forced mid-flight retry yields a duplicate status with zero net duplicates.
 */
@RunWith(RobolectricTestRunner::class)
class EndToEndUploadTest {

    /** Mock /v1 backend that stores observation ids once and dedups re-posts. */
    private class MockCloudBackend : TraxIntelApiService {
        val storedObservationIds = mutableSetOf<String>()
        var dropAckOnUploadNumber = -1 // simulate an ack lost AFTER the server stored the rows
        private var uploadCalls = 0

        override suspend fun enroll(request: EnrollRequestDto): EnrollResponseDto =
            EnrollResponseDto("srv-device", "tenant-1", "device.jwt", "2027-01-01T00:00:00Z")

        override suspend fun uploadObservations(request: ObservationBatchRequestDto): ObservationBatchResponseDto {
            uploadCalls++
            val results = request.observations.map { o ->
                // add() == true on first store (accepted), false if already stored (duplicate)
                ObservationResultDto(o.id, if (storedObservationIds.add(o.id)) "accepted" else "duplicate")
            }
            if (uploadCalls == dropAckOnUploadNumber) throw IOException("ack lost after server stored")
            return ObservationBatchResponseDto(results)
        }
        override suspend fun pullCommands(deviceId: String) = error("unused")
        override suspend fun ackCommands(deviceId: String, request: com.buzbuz.smartautoclicker.core.network.dto.CommandAckRequestDto) {}
        override suspend fun updateFcmToken(deviceId: String, request: com.buzbuz.smartautoclicker.core.network.dto.FcmTokenUpdateDto) {}
    }

    private lateinit var context: Context
    private lateinit var database: ClickDatabase
    private lateinit var syncRepository: ObservationSyncRepository
    private lateinit var tokenStore: DeviceTokenDataSource
    private lateinit var identity: DeviceIdentityDataSource
    private lateinit var settings: CloudSyncSettingsDataSource
    private val backend = MockCloudBackend()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ClickDatabase::class.java).build()
        syncRepository = ObservationSyncRepository(database.observationDao())
        val unique = UUID.randomUUID().toString().take(8)
        tokenStore = DeviceTokenDataSource(context, Dispatchers.IO, "e2e_token_$unique")
        identity = DeviceIdentityDataSource(context, Dispatchers.IO, "e2e_id_$unique")
        settings = CloudSyncSettingsDataSource(context, Dispatchers.IO, "e2e_sync_$unique")
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun worker() = TestListenableWorkerBuilder<ObservationUploadWorker>(context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, params: WorkerParameters) =
                ObservationUploadWorker(c, params, syncRepository, backend, tokenStore, identity, settings)
        })
        .build()

    private suspend fun enrollAndEnableSync() {
        val result = EnrollmentRepository(backend, tokenStore, identity)
            .enroll("PAIR-CODE", DeviceDescriptor("Pixel 8", "14", "4.0.0"))
        assertTrue(result is EnrollmentResult.Success)
        assertTrue(identity.isAccountBound())
        settings.setEnabled(true)
    }

    private suspend fun captureObservation(): String {
        val id = UUID.randomUUID().toString()
        database.observationDao().insert(
            ObservationEntity(id, "srv-device", "scn", 1_000L, "42", "NUMBER", 95, true, null, "PENDING", 0),
        )
        return id
    }

    @Test
    fun happyPath_capturedObservationUploadsSyncedExactlyOnce() = runTest {
        enrollAndEnableSync()
        val obsId = captureObservation()

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("server stored exactly one row", setOf(obsId), backend.storedObservationIds)
        // SYNCED -> no longer in the upload queue
        assertTrue(database.observationDao().getUploadBatch(10).none { it.id == obsId })
    }

    @Test
    fun forcedMidFlightRetry_yieldsDuplicateStatus_zeroNetDuplicates() = runTest {
        enrollAndEnableSync()
        val obsId = captureObservation()

        // First attempt: server stores the row, but the ack is lost (ambiguous timeout) -> retry.
        backend.dropAckOnUploadNumber = 1
        val first = worker().doWork()
        assertTrue(first is ListenableWorker.Result.Retry)
        assertEquals(1, backend.storedObservationIds.size) // server already has it
        assertEquals("FAILED", database.observationDao().getUploadBatch(10).single { it.id == obsId }.syncState)

        // Second attempt: server dedups (duplicate), worker lands it SYNCED. Still ONE stored row.
        val second = worker().doWork()
        assertTrue(second is ListenableWorker.Result.Success)
        assertEquals("zero net duplicates", 1, backend.storedObservationIds.size)
        assertTrue(database.observationDao().getUploadBatch(10).none { it.id == obsId })
    }
}
