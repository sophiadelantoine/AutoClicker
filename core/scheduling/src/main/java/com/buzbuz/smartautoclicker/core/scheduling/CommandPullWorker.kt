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

import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

import com.buzbuz.smartautoclicker.core.network.TraxIntelApiService
import com.buzbuz.smartautoclicker.core.network.auth.DeviceTokenDataSource
import com.buzbuz.smartautoclicker.core.network.command.TrackingController
import com.buzbuz.smartautoclicker.core.network.dto.AckReason
import com.buzbuz.smartautoclicker.core.network.dto.CommandAckDto
import com.buzbuz.smartautoclicker.core.network.dto.CommandAckRequestDto
import com.buzbuz.smartautoclicker.core.network.dto.CommandDto
import com.buzbuz.smartautoclicker.core.network.dto.CommandType
import com.buzbuz.smartautoclicker.core.observation.identity.DeviceIdentityDataSource

import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

import retrofit2.HttpException

import java.io.IOException
import java.time.Instant

/**
 * Enrollment-gated worker that pulls pending remote commands, drops any past expiresAt (acked EXPIRED),
 * applies START/STOP through the [TrackingController], and acks every command exactly once with its
 * outcome reason via the canonical batch ack body (P4-T00). No API calls on an unenrolled device.
 */
@HiltWorker
class CommandPullWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val api: TraxIntelApiService,
    private val controller: TrackingController,
    private val identity: DeviceIdentityDataSource,
    private val tokenStore: DeviceTokenDataSource,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!identity.isAccountBound()) return Result.success() // unenrolled -> zero API calls
        tokenStore.current() // hydrate the bearer snapshot in a cold process
        val deviceId = identity.reconciledDeviceId() ?: return Result.success()

        return try {
            val commands = api.pullCommands(deviceId).commands
            if (commands.isEmpty()) return Result.success()

            val now = Instant.now()
            val acks = commands.map { command ->
                val reason = if (isExpired(command, now)) {
                    AckReason.EXPIRED // skipped, never applied
                } else {
                    apply(command).ackReason
                }
                CommandAckDto(command.commandId, reason)
            }
            api.ackCommands(deviceId, CommandAckRequestDto(acks))
            Result.success()
        } catch (_: HttpException) {
            Result.retry()
        } catch (_: IOException) {
            Result.retry()
        }
    }

    private fun isExpired(command: CommandDto, now: Instant): Boolean =
        runCatching { Instant.parse(command.expiresAt).isBefore(now) }.getOrDefault(false)

    private suspend fun apply(command: CommandDto) = when (command.type) {
        CommandType.START -> command.scenarioId?.let { controller.requestStart(it) }
            ?: com.buzbuz.smartautoclicker.core.network.command.CommandOutcome.NoOp
        CommandType.STOP -> controller.stop(command.scenarioId)
        else -> com.buzbuz.smartautoclicker.core.network.command.CommandOutcome.NoOp // PULL_SCENARIOS handled elsewhere
    }

    companion object {
        const val WORK_NAME = "traxintel-command-pull"
    }
}
