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
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for [ObservationValueMapper], pinning the exact value/type/confidence per detection kind. */
class ObservationValueMapperTest {

    @Test
    fun number_mapsToNumberTypeAndLocaleIndependentString() {
        val v = ObservationValueMapper.forNumber(42.0, 88.4)
        assertEquals(ObservationValueType.NUMBER, v.valueType)
        assertEquals("42.0", v.value)
        assertEquals(88, v.confidence)
    }

    @Test
    fun failedNumber_mapsToNullValue() {
        val v = ObservationValueMapper.forNumber(null, 0.0)
        assertEquals(ObservationValueType.NUMBER, v.valueType)
        assertNull(v.value)
    }

    @Test
    fun text_isTrimOnly_noCaseFoldNoNfc() {
        val v = ObservationValueMapper.forText("  Hello WORLD 世界  ", 95.6)
        assertEquals(ObservationValueType.TEXT, v.valueType)
        assertEquals("Hello WORLD 世界", v.value)
        assertEquals(96, v.confidence)
    }

    @Test
    fun text_emptyAfterTrim_isPreservedDistinctFromNull() {
        assertEquals("", ObservationValueMapper.forText("   ", 0.0).value)
        assertNull(ObservationValueMapper.forText(null, 0.0).value)
    }

    @Test
    fun state_mirrorsIsFulfilled() {
        assertEquals("detected", ObservationValueMapper.forState(true, 100.0).value)
        assertEquals("not_detected", ObservationValueMapper.forState(false, 100.0).value)
        assertEquals(ObservationValueType.STATE, ObservationValueMapper.forState(true, 100.0).valueType)
    }

    @Test
    fun confidence_isClampedTo0to100() {
        assertEquals(100, ObservationValueMapper.forState(true, 150.0).confidence)
        assertEquals(0, ObservationValueMapper.forState(true, -5.0).confidence)
    }
}
