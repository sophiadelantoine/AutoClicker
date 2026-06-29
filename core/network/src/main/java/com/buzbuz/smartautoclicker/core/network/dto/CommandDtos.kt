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
package com.buzbuz.smartautoclicker.core.network.dto

import kotlinx.serialization.Serializable

/**
 * Canonical command contract (P4-T00), matching the OpenAPI spec: pull is
 * GET /v1/devices/{deviceId}/commands, ack is POST /v1/devices/{deviceId}/commands:ack with a batch of
 * per-command {commandId, reason} pairs (a bare {ids:[...]} is rejected — it cannot carry a reason).
 */
@Serializable
data class CommandDto(
    val commandId: String,
    val type: String,
    val scenarioId: String? = null,
    val issuedAt: String,
    val expiresAt: String, // drives the EXPIRED ack path
)

@Serializable
data class CommandPullResponse(val commands: List<CommandDto>)

@Serializable
data class CommandAckDto(val commandId: String, val reason: String)

@Serializable
data class CommandAckRequestDto(val acks: List<CommandAckDto>)

@Serializable
data class FcmTokenUpdateDto(val fcmToken: String)

/** Per-command ack outcomes. */
object AckReason {
    const val APPLIED = "APPLIED"
    const val PENDING_CONSENT = "PENDING_CONSENT"
    const val NOOP = "NOOP"
    const val EXPIRED = "EXPIRED"
}

object CommandType {
    const val START = "START"
    const val STOP = "STOP"
    const val PULL_SCENARIOS = "PULL_SCENARIOS"
}
