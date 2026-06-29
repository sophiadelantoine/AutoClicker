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

import com.buzbuz.smartautoclicker.core.network.TraxIntelApiService
import com.buzbuz.smartautoclicker.core.network.auth.DeviceTokenDataSource
import com.buzbuz.smartautoclicker.core.network.command.CommandOutcome
import com.buzbuz.smartautoclicker.core.network.command.TrackingController
import com.buzbuz.smartautoclicker.core.network.dto.AckReason
import com.buzbuz.smartautoclicker.core.network.dto.CommandAckRequestDto
import com.buzbuz.smartautoclicker.core.network.dto.CommandDto
import com.buzbuz.smartautoclicker.core.network.dto.CommandPullResponse
import com.buzbuz.smartautoclicker.core.network.dto.CommandType
import com.buzbuz.smartautoclicker.core.network.dto.EnrollRequestDto
import com.buzbuz.smartautoclicker.core.network.dto.FcmTokenUpdateDto
import com.buzbuz.smartautoclicker.core.network.dto.ObservationBatchRequestDto
import com.buzbuz.smartautoclicker.core.observation.identity.DeviceIdentityDataSource

import io.mockk.coEvery
import io.mockk.mockk

import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommandPullWorkerTest {

    private class FakeApi(var commands: List<CommandDto> = emptyList()) : TraxIntelApiService {
        var pullCalls = 0
        var lastAck: CommandAckRequestDto? = null
        override suspend fun enroll(request: EnrollRequestDto) = error("unused")
        override suspend fun uploadObservations(request: ObservationBatchRequestDto) = error("unused")
        override suspend fun pullCommands(deviceId: String): CommandPullResponse {
            pullCalls++
            return CommandPullResponse(commands)
        }
        override suspend fun ackCommands(deviceId: String, request: CommandAckRequestDto) { lastAck = request }
        override suspend fun updateFcmToken(deviceId: String, request: FcmTokenUpdateDto) {}
    }

    private class FakeController : TrackingController {
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String?>()
        override suspend fun requestStart(scenarioId: String): CommandOutcome {
            started.add(scenarioId); return CommandOutcome.Applied
        }
        override suspend fun stop(scenarioId: String?): CommandOutcome {
            stopped.add(scenarioId); return CommandOutcome.Applied
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun enrolledIdentity() = mockk<DeviceIdentityDataSource>(relaxed = true).also {
        coEvery { it.isAccountBound() } returns true
        coEvery { it.reconciledDeviceId() } returns "dev-1"
    }

    private fun cmd(id: String, type: String, scenarioId: String?, expiresAt: String) =
        CommandDto(id, type, scenarioId, issuedAt = "2026-06-29T12:00:00Z", expiresAt = expiresAt)

    private fun worker(api: TraxIntelApiService, controller: TrackingController, identity: DeviceIdentityDataSource) =
        TestListenableWorkerBuilder<CommandPullWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(c: Context, name: String, params: WorkerParameters) =
                    CommandPullWorker(c, params, api, controller, identity, mockk(relaxed = true))
            })
            .build()

    @Test
    fun unenrolled_shortCircuits_withZeroApiCalls() = runTest {
        val identity = mockk<DeviceIdentityDataSource>(relaxed = true)
        coEvery { identity.isAccountBound() } returns false
        val api = FakeApi()

        val result = worker(api, FakeController(), identity).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, api.pullCalls)
    }

    @Test
    fun expiredCommand_ackedExpired_neverApplied() = runTest {
        val api = FakeApi(listOf(cmd("c1", CommandType.START, "s1", "2000-01-01T00:00:00Z")))
        val controller = FakeController()

        worker(api, controller, enrolledIdentity()).doWork()

        assertTrue("not applied", controller.started.isEmpty())
        assertEquals(AckReason.EXPIRED, api.lastAck!!.acks.single { it.commandId == "c1" }.reason)
    }

    @Test
    fun startCommand_appliedAndAckedOnce() = runTest {
        val api = FakeApi(listOf(cmd("c1", CommandType.START, "s1", "2099-01-01T00:00:00Z")))
        val controller = FakeController()

        val result = worker(api, controller, enrolledIdentity()).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf("s1"), controller.started)
        assertEquals(AckReason.APPLIED, api.lastAck!!.acks.single { it.commandId == "c1" }.reason)
    }

    @Test
    fun stopCommand_appliedAndAckedOnce() = runTest {
        val api = FakeApi(listOf(cmd("c2", CommandType.STOP, "s1", "2099-01-01T00:00:00Z")))
        val controller = FakeController()

        worker(api, controller, enrolledIdentity()).doWork()

        assertEquals(listOf<String?>("s1"), controller.stopped)
        assertEquals(AckReason.APPLIED, api.lastAck!!.acks.single { it.commandId == "c2" }.reason)
    }

    @Test
    fun mixedBatch_acksEachWithItsReason() = runTest {
        val api = FakeApi(
            listOf(
                cmd("expired", CommandType.START, "s1", "2000-01-01T00:00:00Z"),
                cmd("start", CommandType.START, "s2", "2099-01-01T00:00:00Z"),
            ),
        )
        worker(api, FakeController(), enrolledIdentity()).doWork()

        val acks = api.lastAck!!.acks.associate { it.commandId to it.reason }
        assertEquals(AckReason.EXPIRED, acks["expired"])
        assertEquals(AckReason.APPLIED, acks["start"])
        assertNull(acks["missing"])
    }
}
