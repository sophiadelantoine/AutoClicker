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

import org.junit.Assert.assertEquals
import org.junit.Test

/** Sanity tests for the [Observation] domain model. */
class ObservationTest {

    @Test
    fun valueType_hasExpectedEntries() {
        assertEquals(listOf("NUMBER", "TEXT", "STATE"), ObservationValueType.entries.map { it.name })
        assertEquals(ObservationValueType.NUMBER, ObservationValueType.valueOf("NUMBER"))
    }

    @Test
    fun observation_copyPreservesFields() {
        val observation = Observation(
            id = "obs-1",
            scenarioId = "scn-1",
            deviceId = "dev-1",
            deviceCapturedAt = 1_000L,
            value = "42",
            valueType = ObservationValueType.NUMBER,
            confidence = 90,
            isFulfilled = true,
            cropPath = null,
        )
        assertEquals(observation, observation.copy())
    }
}
