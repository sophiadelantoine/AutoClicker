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
package com.buzbuz.smartautoclicker.core.observation

import kotlinx.serialization.json.Json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies [TrackingScenario] deserializes from the documented JSON shape. */
class TrackingScenarioTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun deserializesNumberTrackingScenario() {
        val payload = """
            {"id":"t1","scenarioId":"scn-1","readType":"NUMBER",
             "area":{"left":0,"top":0,"right":100,"bottom":50},"intervalMs":10000}
        """.trimIndent()

        val tracking = json.decodeFromString<TrackingScenario>(payload)

        assertEquals("t1", tracking.id)
        assertEquals("scn-1", tracking.scenarioId)
        assertEquals(ObservationValueType.NUMBER, tracking.readType)
        assertEquals(100, tracking.area.right)
        assertEquals(50, tracking.area.bottom)
        assertEquals(10_000L, tracking.intervalMs)
        assertNull(tracking.alphabet)
    }

    @Test
    fun deserializesTextTrackingScenarioWithAlphabet() {
        val payload = """
            {"id":"t2","scenarioId":"s","readType":"TEXT",
             "area":{"left":1,"top":2,"right":3,"bottom":4},"intervalMs":5000,"alphabet":"ARABIC"}
        """.trimIndent()

        val tracking = json.decodeFromString<TrackingScenario>(payload)

        assertEquals(ObservationValueType.TEXT, tracking.readType)
        assertEquals("ARABIC", tracking.alphabet)
    }
}
