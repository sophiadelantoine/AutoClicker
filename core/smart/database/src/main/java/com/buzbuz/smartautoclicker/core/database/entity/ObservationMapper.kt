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

import com.buzbuz.smartautoclicker.core.observation.Observation
import com.buzbuz.smartautoclicker.core.observation.ObservationValueType

/** Map an [ObservationEntity] to its [Observation] domain model. Entity-only sync metadata is dropped. */
fun ObservationEntity.toObservation(): Observation = Observation(
    id = id,
    scenarioId = scenarioId,
    deviceId = deviceId,
    deviceCapturedAt = deviceCapturedAt,
    value = value,
    valueType = ObservationValueType.valueOf(valueType),
    confidence = confidence,
    isFulfilled = isFulfilled,
    cropPath = cropPath,
)

/**
 * Map an [Observation] domain model to a persistable [ObservationEntity]. Sync metadata
 * ([syncState], [retryCount]) is entity-only, so the caller supplies it here.
 */
fun Observation.toEntity(syncState: String, retryCount: Int = 0): ObservationEntity = ObservationEntity(
    id = id,
    deviceId = deviceId,
    scenarioId = scenarioId,
    deviceCapturedAt = deviceCapturedAt,
    value = value,
    valueType = valueType.name,
    confidence = confidence,
    isFulfilled = isFulfilled,
    cropPath = cropPath,
    syncState = syncState,
    retryCount = retryCount,
)
