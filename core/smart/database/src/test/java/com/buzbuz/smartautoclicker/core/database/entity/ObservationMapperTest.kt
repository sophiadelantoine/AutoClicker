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
package com.buzbuz.smartautoclicker.core.database.entity

import com.buzbuz.smartautoclicker.core.observation.ObservationValueType

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Round-trip tests for [ObservationMapper]. */
class ObservationMapperTest {

    @Test
    fun entityToDomainToEntity_isLossless() {
        val entity = ObservationEntity(
            id = "obs-1",
            deviceId = "dev-1",
            scenarioId = "scn-1",
            deviceCapturedAt = 123L,
            value = "42",
            valueType = "NUMBER",
            confidence = 90,
            isFulfilled = true,
            cropPath = "/data/crops/obs-1.png",
            syncState = "PENDING",
            retryCount = 2,
        )

        // Re-supply the entity-only sync metadata on the way back.
        val roundTripped = entity.toObservation().toEntity(entity.syncState, entity.retryCount)

        assertEquals(entity, roundTripped)
    }

    @Test
    fun toObservation_dropsSyncMetadataAndMapsFields() {
        val entity = ObservationEntity(
            id = "o",
            deviceId = "d",
            scenarioId = "s",
            deviceCapturedAt = 1L,
            value = null,
            valueType = "STATE",
            confidence = 0,
            isFulfilled = false,
            cropPath = null,
            syncState = "FAILED",
            retryCount = 5,
        )

        val observation = entity.toObservation()

        assertEquals("o", observation.id)
        assertEquals(ObservationValueType.STATE, observation.valueType)
        assertNull(observation.value)
        // The domain model carries no sync metadata; toEntity must take it from the caller.
        assertEquals("SYNCED", observation.toEntity(syncState = "SYNCED").syncState)
        assertEquals(0, observation.toEntity(syncState = "SYNCED").retryCount)
    }
}
