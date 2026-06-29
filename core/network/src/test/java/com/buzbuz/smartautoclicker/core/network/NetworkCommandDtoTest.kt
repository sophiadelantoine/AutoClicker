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

import com.buzbuz.smartautoclicker.core.network.dto.AckReason
import com.buzbuz.smartautoclicker.core.network.dto.CommandAckDto
import com.buzbuz.smartautoclicker.core.network.dto.CommandAckRequestDto
import com.buzbuz.smartautoclicker.core.network.dto.CommandPullResponse
import com.buzbuz.smartautoclicker.core.network.dto.FcmTokenUpdateDto

import kotlinx.serialization.encodeToString

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkCommandDtoTest {

    private val json = TraxIntelApiFactory.json

    @Test
    fun ackRequest_serializesPerCommandReason_forAllFourReasons() {
        val request = CommandAckRequestDto(
            listOf(
                CommandAckDto("c1", AckReason.APPLIED),
                CommandAckDto("c2", AckReason.PENDING_CONSENT),
                CommandAckDto("c3", AckReason.NOOP),
                CommandAckDto("c4", AckReason.EXPIRED),
            ),
        )
        val payload = json.encodeToString(request)

        assertTrue(payload.contains("\"acks\""))
        for (reason in listOf("APPLIED", "PENDING_CONSENT", "NOOP", "EXPIRED")) {
            assertTrue("ack carries reason $reason", payload.contains("\"reason\":\"$reason\""))
        }
        assertEquals(4, json.decodeFromString<CommandAckRequestDto>(payload).acks.size)
    }

    @Test
    fun commandPullResponse_exposesExpiresAt() {
        val wire = """{"commands":[{"commandId":"c1","type":"START","scenarioId":"s1",
            "issuedAt":"2026-06-29T12:00:00Z","expiresAt":"2026-06-29T12:05:00Z"}]}"""
        val response = json.decodeFromString<CommandPullResponse>(wire)

        assertEquals(1, response.commands.size)
        assertEquals("START", response.commands[0].type)
        assertEquals("2026-06-29T12:05:00Z", response.commands[0].expiresAt) // EXPIRED path input
    }

    @Test
    fun fcmTokenUpdate_serializes() {
        assertTrue(json.encodeToString(FcmTokenUpdateDto("tok-123")).contains("\"fcmToken\":\"tok-123\""))
    }
}
