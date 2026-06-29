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
package com.buzbuz.smartautoclicker.core.capture

import android.graphics.Rect

import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.model.AND
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.counter.ComparisonOperation
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterOperationValue
import com.buzbuz.smartautoclicker.core.domain.model.event.ScreenEvent
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.observation.ObservationValueType
import com.buzbuz.smartautoclicker.core.observation.TrackingScenario

/** A cloud [TrackingScenario] adapted into a runnable, read-only local scenario graph. */
data class LocalTrackingScenario(
    val scenario: Scenario,
    val screenEvent: ScreenEvent,
)

/**
 * Adapts a cloud [TrackingScenario] onto a single-[ScreenEvent], read-only local [Scenario] with
 * exactly one [ScreenCondition] and no Action. Every id is temporary (databaseId == 0, derived
 * tempId) and never persisted — this graph exists only to drive a tracking read at runtime.
 *
 * Synthetic sentinels make the single condition fire whenever a value is read:
 * - NUMBER: GREATER_OR_EQUALS the lowest possible value (any number read is "fulfilled");
 * - TEXT:   shouldBeDetected = true with a permissive (empty, threshold 0) target;
 * - STATE:  a permissive Color condition.
 */
object TrackingScenarioAdapter {

    private const val DEFAULT_DETECTION_QUALITY = 1200
    private const val PERMISSIVE_THRESHOLD = 0
    private const val PERMISSIVE_COLOR_THRESHOLD = 100
    private const val READ_NAME = "TraxIntel read"

    /** Lowest counter value: any recognized number is >= this, so a NUMBER read always fulfills. */
    private const val LOWEST_COUNTER_VALUE = -Double.MAX_VALUE

    fun toLocalScenario(tracking: TrackingScenario): LocalTrackingScenario {
        val base = tracking.id.hashCode().toLong() and 0x7FFFFFFFL
        val scenarioId = Identifier(id = base + 1L, asTemporary = true)
        val eventId = Identifier(id = base + 2L, asTemporary = true)
        val conditionId = Identifier(id = base + 3L, asTemporary = true)

        val area = Rect(tracking.area.left, tracking.area.top, tracking.area.right, tracking.area.bottom)

        val event = ScreenEvent(
            id = eventId,
            scenarioId = scenarioId,
            name = READ_NAME,
            conditionOperator = AND,
            actions = emptyList(),
            conditions = listOf(buildCondition(tracking, conditionId, eventId, area)),
            enabledOnStart = true,
            priority = 0,
            keepDetecting = false,
            cooldownMs = 0L,
        )

        val scenario = Scenario(
            id = scenarioId,
            name = "TraxIntel: ${tracking.id}",
            detectionQuality = DEFAULT_DETECTION_QUALITY,
            eventCount = 1,
        )

        return LocalTrackingScenario(scenario, event)
    }

    private fun buildCondition(
        tracking: TrackingScenario,
        conditionId: Identifier,
        eventId: Identifier,
        area: Rect,
    ): ScreenCondition = when (tracking.readType) {
        ObservationValueType.NUMBER -> ScreenCondition.Number(
            id = conditionId,
            eventId = eventId,
            name = READ_NAME,
            threshold = PERMISSIVE_THRESHOLD,
            priority = 0,
            detectionArea = area,
            comparisonOperation = ComparisonOperation.GREATER_OR_EQUALS,
            counterValue = CounterOperationValue.Number(LOWEST_COUNTER_VALUE),
        )
        ObservationValueType.TEXT -> ScreenCondition.Text(
            id = conditionId,
            eventId = eventId,
            name = READ_NAME,
            threshold = PERMISSIVE_THRESHOLD,
            shouldBeDetected = true,
            priority = 0,
            text = "",
            detectionArea = area,
            alphabet = parseAlphabet(tracking.alphabet),
        )
        ObservationValueType.STATE -> ScreenCondition.Color(
            id = conditionId,
            eventId = eventId,
            name = READ_NAME,
            threshold = PERMISSIVE_COLOR_THRESHOLD,
            shouldBeDetected = true,
            priority = 0,
            color = 0,
            detectionArea = area,
        )
    }

    /** A NUMBER read uses the default recognition model, so alphabet is only consulted for TEXT. */
    private fun parseAlphabet(name: String?): OCRAlphabet =
        name?.let { runCatching { OCRAlphabet.valueOf(it) }.getOrNull() } ?: OCRAlphabet.LATIN
}
