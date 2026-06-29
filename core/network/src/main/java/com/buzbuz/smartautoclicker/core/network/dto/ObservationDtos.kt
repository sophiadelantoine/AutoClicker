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
 * One observation in a POST /v1/observations:batch upload. tenantId/deviceId are NOT in the body —
 * the server derives them from the device token. deviceCapturedAt is ISO-8601 UTC (DateTimeFormatter.ISO_INSTANT).
 */
@Serializable
data class ObservationUploadDto(
    val id: String,
    val scenarioId: String,
    val deviceCapturedAt: String,
    val value: String? = null,
    val valueType: String,
    val confidence: Int,
    val isFulfilled: Boolean,
    val hasCrop: Boolean,
)

@Serializable
data class ObservationBatchRequestDto(val observations: List<ObservationUploadDto>)

@Serializable
data class ObservationResultDto(
    val id: String,
    val status: String,
    val serverReceivedAt: String? = null,
    val cropUploadUrl: String? = null,
)

@Serializable
data class ObservationBatchResponseDto(val results: List<ObservationResultDto>)
