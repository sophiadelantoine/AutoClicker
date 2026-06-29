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
package com.buzbuz.smartautoclicker.core.detection

import android.os.Build

import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

import org.robolectric.annotation.Config

/** Unit tests for the native-result mappers, in particular the recognized-text decode added in P1-T01b. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class DetectionResultTest {

    /** A "detected" 7-element numeric array with the no-number sentinel in slot 6. */
    private fun detectedNumeric(confidence: Double = 0.92): DoubleArray =
        doubleArrayOf(1.0, 10.0, 20.0, 30.0, 40.0, confidence, Double.MIN_VALUE)

    @Test
    fun textResult_populatesRecognizedText() {
        val bundle: Array<Any?> = arrayOf(detectedNumeric(), "hello".toByteArray(Charsets.UTF_8))

        val result = bundle.toTextDetectionResult()

        assertTrue(result.isDetected)
        assertEquals("hello", result.recognizedText)
        assertEquals(0.92, result.confidenceRate, 0.0001)
        // slot 6 holds the no-number sentinel, so a text read carries no number.
        assertNull(result.numberDetected)
    }

    @Test
    fun textResult_preservesMultibyteUtf8() {
        // Raw UTF-8 bytes (not modified-UTF-8) must round-trip CJK / Arabic exactly.
        val text = "残高 ٤٢ 世界"
        val bundle: Array<Any?> = arrayOf(detectedNumeric(), text.toByteArray(Charsets.UTF_8))

        assertEquals(text, bundle.toTextDetectionResult().recognizedText)
    }

    @Test
    fun textResult_emptyBytes_yieldNullText() {
        val bundle: Array<Any?> = arrayOf(detectedNumeric(confidence = 0.0), ByteArray(0))

        assertNull(bundle.toTextDetectionResult().recognizedText)
    }

    @Test
    fun textResult_malformedBundle_yieldsEmptyResult() {
        assertNull((null as Array<Any?>?).toTextDetectionResult().recognizedText)
        assertNull(arrayOf<Any?>(detectedNumeric()).toTextDetectionResult().recognizedText)
    }

    @Test
    fun numberResult_leavesRecognizedTextNull() {
        // Number reads use the legacy DoubleArray path and must not gain a recognized text.
        val result = doubleArrayOf(1.0, 5.0, 6.0, 7.0, 8.0, 0.99, 42.0).toDetectionResult()

        assertNull(result.recognizedText)
        assertEquals(42.0, result.numberDetected!!, 0.0001)
    }

    @Test
    fun textResult_flagOff_restoresLegacyNumericOnly() {
        val bundle: Array<Any?> = arrayOf(detectedNumeric(confidence = 0.77), "ignored".toByteArray(Charsets.UTF_8))

        val result = bundle.toTextDetectionResult(recognizedTextEnabled = false)

        assertNull(result.recognizedText)          // flag off -> no text
        assertTrue(result.isDetected)              // numeric path unchanged
        assertEquals(0.77, result.confidenceRate, 0.0001)
    }

    @Test
    fun textResult_flagOn_populatesRecognizedText() {
        val bundle: Array<Any?> = arrayOf(detectedNumeric(), "on".toByteArray(Charsets.UTF_8))

        assertEquals("on", bundle.toTextDetectionResult(recognizedTextEnabled = true).recognizedText)
    }
}
