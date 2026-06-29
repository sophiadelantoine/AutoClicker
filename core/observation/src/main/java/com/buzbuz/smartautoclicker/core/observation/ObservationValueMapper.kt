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

import kotlin.math.roundToInt

/** The value-bearing fields of an [Observation], derived from a detection result. */
data class ObservationValue(
    val value: String?,
    val valueType: ObservationValueType,
    val confidence: Int,
)

/**
 * Maps a detection/processed result into the value-bearing fields of an [Observation]
 * (value, valueType, confidence), kept independent of the detection/processing types so the
 * caller passes only primitives.
 */
object ObservationValueMapper {

    /** Round a 0-100 confidence rate and clamp it to [0, 100]. */
    private fun clampConfidence(confidenceRate: Double): Int =
        confidenceRate.roundToInt().coerceIn(0, 100)

    /**
     * A Number capture: [ObservationValueType.NUMBER], locale-independent [Double.toString] (no
     * grouping separators). A failed read ([numberDetected] == null) maps to a null value; the
     * caller records isFulfilled = false for such reads.
     */
    fun forNumber(numberDetected: Double?, confidenceRate: Double): ObservationValue =
        ObservationValue(
            value = numberDetected?.toString(),
            valueType = ObservationValueType.NUMBER,
            confidence = clampConfidence(confidenceRate),
        )

    /**
     * A Text capture: [ObservationValueType.TEXT], the recognized text trimmed only (no case-fold,
     * no NFC). A null recognized text stays null; a non-null text empty after trim is preserved as
     * "" (distinct from null).
     */
    fun forText(recognizedText: String?, confidenceRate: Double): ObservationValue =
        ObservationValue(
            value = recognizedText?.trim(),
            valueType = ObservationValueType.TEXT,
            confidence = clampConfidence(confidenceRate),
        )

    /**
     * A State capture (image/color): [ObservationValueType.STATE], value mirrors [isFulfilled] as
     * "detected" / "not_detected".
     */
    fun forState(isFulfilled: Boolean, confidenceRate: Double): ObservationValue =
        ObservationValue(
            value = if (isFulfilled) "detected" else "not_detected",
            valueType = ObservationValueType.STATE,
            confidence = clampConfidence(confidenceRate),
        )
}
