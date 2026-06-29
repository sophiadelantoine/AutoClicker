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

import android.os.Build

import androidx.test.ext.junit.runners.AndroidJUnit4

import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.counter.ComparisonOperation
import com.buzbuz.smartautoclicker.core.observation.ObservationValueType
import com.buzbuz.smartautoclicker.core.observation.TrackingArea
import com.buzbuz.smartautoclicker.core.observation.TrackingScenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

import org.robolectric.annotation.Config

/** Tests the TrackingScenario -> local Scenario bridge. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class TrackingScenarioAdapterTest {

    private fun tracking(
        readType: ObservationValueType,
        alphabet: String? = null,
    ) = TrackingScenario(
        id = "t1",
        scenarioId = "scn-1",
        readType = readType,
        area = TrackingArea(0, 0, 100, 50),
        intervalMs = 10_000L,
        alphabet = alphabet,
    )

    @Test
    fun number_buildsReadOnlyScenarioWithExactlyOneConditionAndNoAction() {
        val local = TrackingScenarioAdapter.toLocalScenario(tracking(ObservationValueType.NUMBER))

        assertEquals(1, local.scenario.eventCount)
        assertEquals(1, local.screenEvent.conditions.size)
        assertTrue("read-only: no actions", local.screenEvent.actions.isEmpty())

        val condition = local.screenEvent.conditions.single()
        assertTrue(condition is ScreenCondition.Number)
        condition as ScreenCondition.Number
        assertEquals(ComparisonOperation.GREATER_OR_EQUALS, condition.comparisonOperation)

        // All ids are temporary (databaseId == 0) and never persisted.
        assertFalse(local.scenario.id.isInDatabase())
        assertFalse(local.screenEvent.id.isInDatabase())
        assertFalse(condition.id.isInDatabase())
    }

    @Test
    fun nonLatinAlphabet_hasNoEffectOnNumberRead() {
        // A NUMBER read uses detectNumber (default recognition model); the alphabet is irrelevant.
        val condition = TrackingScenarioAdapter
            .toLocalScenario(tracking(ObservationValueType.NUMBER, alphabet = "ARABIC"))
            .screenEvent.conditions.single()

        // The Number condition has no alphabet field at all -> alphabet cannot affect recognition.
        assertTrue(condition is ScreenCondition.Number)
    }

    @Test
    fun text_buildsPermissiveTextConditionWithParsedAlphabet() {
        val condition = TrackingScenarioAdapter
            .toLocalScenario(tracking(ObservationValueType.TEXT, alphabet = "ARABIC"))
            .screenEvent.conditions.single()

        assertTrue(condition is ScreenCondition.Text)
        condition as ScreenCondition.Text
        assertEquals(OCRAlphabet.ARABIC, condition.alphabet)
        assertTrue(condition.shouldBeDetected)
    }

    @Test
    fun unknownAlphabet_fallsBackToLatin() {
        val condition = TrackingScenarioAdapter
            .toLocalScenario(tracking(ObservationValueType.TEXT, alphabet = "NOT_A_REAL_ALPHABET"))
            .screenEvent.conditions.single() as ScreenCondition.Text

        assertEquals(OCRAlphabet.LATIN, condition.alphabet)
    }
}
