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
package com.buzbuz.smartautoclicker.core.network

import com.buzbuz.smartautoclicker.core.network.dto.CommandAckRequestDto
import com.buzbuz.smartautoclicker.core.network.dto.CommandPullResponse
import com.buzbuz.smartautoclicker.core.network.dto.EnrollRequestDto
import com.buzbuz.smartautoclicker.core.network.dto.EnrollResponseDto
import com.buzbuz.smartautoclicker.core.network.dto.FcmTokenUpdateDto
import com.buzbuz.smartautoclicker.core.network.dto.ObservationBatchRequestDto
import com.buzbuz.smartautoclicker.core.network.dto.ObservationBatchResponseDto

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/** Typed /v1 surface. Routes use the canonical colon form (POST /v1/devices:enroll). */
interface TraxIntelApiService {

    @POST("v1/devices:enroll")
    suspend fun enroll(@Body request: EnrollRequestDto): EnrollResponseDto

    @POST("v1/observations:batch")
    suspend fun uploadObservations(@Body request: ObservationBatchRequestDto): ObservationBatchResponseDto

    @GET("v1/devices/{deviceId}/commands")
    suspend fun pullCommands(@Path("deviceId") deviceId: String): CommandPullResponse

    @POST("v1/devices/{deviceId}/commands:ack")
    suspend fun ackCommands(@Path("deviceId") deviceId: String, @Body request: CommandAckRequestDto)

    @PATCH("v1/devices/{deviceId}/fcm-token")
    suspend fun updateFcmToken(@Path("deviceId") deviceId: String, @Body request: FcmTokenUpdateDto)
}
