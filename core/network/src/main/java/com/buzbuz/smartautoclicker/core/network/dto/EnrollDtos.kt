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
 * Enroll request body for the canonical colon route POST /v1/devices:enroll (Wire JSON, line 3359).
 * The slash form POST /v1/devices/enroll is a known blueprint inconsistency and is NOT used.
 */
@Serializable
data class EnrollRequestDto(
    val pairingCode: String,
    val model: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
)

@Serializable
data class EnrollResponseDto(
    val deviceId: String,
    val tenantId: String,
    val deviceToken: String,
    val tokenExpiresAt: String,
)
