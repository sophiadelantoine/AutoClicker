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

import kotlinx.serialization.Serializable

/** A screen rectangle for a [TrackingScenario], serialized as plain ints (android.graphics.Rect is not serializable). */
@Serializable
data class TrackingArea(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * A cloud-defined "track one value on one screen region" instruction, pushed to a device.
 *
 * It is intentionally a pure, serializable description (no domain/Android types beyond the int area)
 * so it can travel over the wire and live in this leaf module. The capture layer adapts it into a
 * runnable local scenario.
 *
 * @param id the tracking scenario id (cloud).
 * @param scenarioId the cloud scenario UUID recorded on every resulting observation.
 * @param readType what to read from the region.
 * @param area the screen region to read.
 * @param intervalMs how often to read, in millis.
 * @param alphabet OCR alphabet name for [ObservationValueType.TEXT] reads; ignored otherwise.
 */
@Serializable
data class TrackingScenario(
    val id: String,
    val scenarioId: String,
    val readType: ObservationValueType,
    val area: TrackingArea,
    val intervalMs: Long,
    val alphabet: String? = null,
)
