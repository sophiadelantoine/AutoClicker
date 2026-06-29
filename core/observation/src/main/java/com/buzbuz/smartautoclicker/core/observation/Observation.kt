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

/** The kind of value captured in an [Observation]. */
enum class ObservationValueType {
    /** A numeric value read by OCR (e.g. a counter, price, score). */
    NUMBER,
    /** A text value read by OCR. */
    TEXT,
    /** A presence/absence state for an image or color condition (no extracted value). */
    STATE,
}

/**
 * A single screen observation captured on-device by TraxIntel.
 *
 * This is the domain view of a capture, before any cloud sync. Sync metadata (sync state, retry
 * count) is deliberately NOT part of this model — it lives only on the persistence entity, so the
 * domain stays a pure description of "what was seen, when".
 *
 * @param id client-generated UUID; also the server idempotency key.
 * @param scenarioId the cloud-side scenario UUID this observation belongs to.
 * @param deviceId the enrolled device identifier that captured it.
 * @param deviceCapturedAt capture time in epoch millis, from the device clock.
 * @param value the extracted value (number/text as string), or null for a STATE observation.
 * @param valueType the kind of [value].
 * @param confidence detection confidence, 0-100.
 * @param isFulfilled whether the originating condition was fulfilled at capture time.
 * @param cropPath optional on-device path to a screenshot crop of the observed region.
 */
data class Observation(
    val id: String,
    val scenarioId: String,
    val deviceId: String,
    val deviceCapturedAt: Long,
    val value: String?,
    val valueType: ObservationValueType,
    val confidence: Int,
    val isFulfilled: Boolean,
    val cropPath: String?,
)
