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

import com.buzbuz.smartautoclicker.core.network.dto.EnrollRequestDto
import com.buzbuz.smartautoclicker.core.network.dto.EnrollResponseDto
import com.buzbuz.smartautoclicker.core.network.dto.ObservationBatchRequestDto
import com.buzbuz.smartautoclicker.core.network.dto.ObservationBatchResponseDto
import com.buzbuz.smartautoclicker.core.network.dto.ObservationUploadDto

import kotlinx.serialization.encodeToString

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trips the /v1 wire DTOs through the pinned serializer, asserting the canonical field shapes. */
class NetworkDtoSerializationTest {

    private val json = TraxIntelApiFactory.json

    @Test
    fun enrollRequest_emitsCanonicalFields() {
        val payload = json.encodeToString(
            EnrollRequestDto(pairingCode = "CODE123", model = "Pixel 8", osVersion = "14", appVersion = "4.0.0"),
        )
        assertTrue(payload.contains("\"pairingCode\":\"CODE123\""))
        assertTrue(payload.contains("\"model\":\"Pixel 8\""))
        assertTrue(payload.contains("\"osVersion\":\"14\""))
        assertTrue(payload.contains("\"appVersion\":\"4.0.0\""))

        val back = json.decodeFromString<EnrollRequestDto>(payload)
        assertEquals("CODE123", back.pairingCode)
        assertEquals("Pixel 8", back.model)
    }

    @Test
    fun enrollResponse_roundTrips() {
        val wire = """{"deviceId":"d-1","tenantId":"t-1","deviceToken":"jwt.abc","tokenExpiresAt":"2027-06-29T12:00:00Z"}"""
        val r = json.decodeFromString<EnrollResponseDto>(wire)
        assertEquals("d-1", r.deviceId)
        assertEquals("t-1", r.tenantId)
        assertEquals("jwt.abc", r.deviceToken)
        assertEquals("2027-06-29T12:00:00Z", r.tokenExpiresAt)
    }

    @Test
    fun batchRequest_perRowShape_andNoTenantOrDeviceInBody() {
        val payload = json.encodeToString(
            ObservationBatchRequestDto(
                listOf(
                    ObservationUploadDto(
                        id = "o-1", scenarioId = "s-1", deviceCapturedAt = "2026-06-29T12:00:00Z",
                        value = "42", valueType = "NUMBER", confidence = 95, isFulfilled = true, hasCrop = false,
                    ),
                ),
            ),
        )
        assertTrue(payload.contains("\"observations\""))
        assertTrue(payload.contains("\"id\":\"o-1\""))
        assertTrue(payload.contains("\"scenarioId\":\"s-1\""))
        assertTrue(payload.contains("\"deviceCapturedAt\":\"2026-06-29T12:00:00Z\""))
        assertTrue(payload.contains("\"valueType\":\"NUMBER\""))
        assertTrue(payload.contains("\"confidence\":95"))
        assertTrue(payload.contains("\"isFulfilled\":true"))
        assertTrue(payload.contains("\"hasCrop\":false"))
        // tenantId/deviceId are derived from the token server-side, never sent in the body
        assertFalse(payload.contains("tenantId"))
        assertFalse(payload.contains("deviceId"))
    }

    @Test
    fun batchResponse_roundTrips_acceptedAndDuplicate() {
        val wire = """{"results":[
            {"id":"o-1","status":"accepted","serverReceivedAt":"2026-06-29T12:00:01Z","cropUploadUrl":"/v1/observations/o-1/crop?token=x"},
            {"id":"o-2","status":"duplicate"}
        ]}"""
        val r = json.decodeFromString<ObservationBatchResponseDto>(wire)
        assertEquals(2, r.results.size)
        assertEquals("accepted", r.results[0].status)
        assertEquals("/v1/observations/o-1/crop?token=x", r.results[0].cropUploadUrl)
        assertEquals("duplicate", r.results[1].status)
        assertNull(r.results[1].serverReceivedAt)
        assertNull(r.results[1].cropUploadUrl)
    }
}
