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
package com.buzbuz.smartautoclicker.core.processing.tests.processor

import android.graphics.Point
import android.graphics.Rect
import android.os.Build

import androidx.test.ext.junit.runners.AndroidJUnit4

import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.detection.DetectionResult
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.domain.model.AND
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.counter.ComparisonOperation
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterOperationValue
import com.buzbuz.smartautoclicker.core.processing.data.processor.ConditionsVerifier
import com.buzbuz.smartautoclicker.core.processing.data.processor.state.ProcessingState
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScalingManager
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScreenConditionScalingInfo
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingListener
import com.buzbuz.smartautoclicker.core.processing.domain.model.ProcessedConditionResult

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

import org.robolectric.annotation.Config

/** Verifies the value-capture seam added in P1-T03: Number/Text results carry the raw OCR value. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ConditionsVerifierTest {

    @Mock private lateinit var mockState: ProcessingState
    @Mock private lateinit var mockImageDetector: ImageDetector
    @Mock private lateinit var mockScalingManager: ScalingManager
    @Mock private lateinit var mockListener: SmartProcessingListener

    private lateinit var verifier: ConditionsVerifier

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // scaleUpDetectionResult just echoes the point in tests.
        whenever(mockScalingManager.scaleUpDetectionResult(any())).doAnswer { it.getArgument(0) }
        verifier = ConditionsVerifier(
            state = mockState,
            imageDetector = mockImageDetector,
            scalingManager = mockScalingManager,
            bitmapSupplier = { _, _, _ -> null },
            progressListener = mockListener,
        )
    }

    private fun captureResult(): ProcessedConditionResult.Screen {
        val captor = argumentCaptor<ProcessedConditionResult.Screen>()
        verify(mockListener).onScreenConditionProcessingCompleted(captor.capture(), any())
        return captor.firstValue
    }

    @Test
    fun `Number condition result carries the raw numberDetected`() = runTest {
        val area = Rect(0, 0, 10, 10)
        val condition = ScreenCondition.Number(
            id = Identifier(databaseId = 1L),
            eventId = Identifier(databaseId = 1L),
            name = "num",
            threshold = 80,
            priority = 0,
            detectionArea = area,
            comparisonOperation = ComparisonOperation.GREATER,
            counterValue = CounterOperationValue.Number(0.0),
        )
        whenever(mockScalingManager.getScreenConditionScalingInfo(condition))
            .doReturn(ScreenConditionScalingInfo.Number(condition, area))
        whenever(mockImageDetector.detectNumber(any(), any()))
            .doReturn(
                DetectionResult(
                    isDetected = true, confidenceRate = 90.0,
                    position = Point(5, 5), size = Point(2, 2), numberDetected = 42.0,
                )
            )

        verifier.verifyConditions(AND, listOf(condition))

        val result = captureResult()
        assertEquals(42.0, result.numberDetected!!, 0.0001)
        assertNull(result.recognizedText)
    }

    @Test
    fun `Text condition result carries the recognizedText`() = runTest {
        val area = Rect(0, 0, 10, 10)
        val condition = ScreenCondition.Text(
            id = Identifier(databaseId = 2L),
            eventId = Identifier(databaseId = 1L),
            name = "txt",
            threshold = 80,
            shouldBeDetected = true,
            priority = 0,
            text = "balance",
            detectionArea = area,
            alphabet = OCRAlphabet.LATIN,
        )
        whenever(mockScalingManager.getScreenConditionScalingInfo(condition))
            .doReturn(ScreenConditionScalingInfo.Text(condition, area))
        whenever(mockImageDetector.detectText(any(), any(), any(), any()))
            .doReturn(
                DetectionResult(
                    isDetected = true, confidenceRate = 95.0,
                    position = Point(1, 1), size = Point(2, 2), recognizedText = "balance 42",
                )
            )

        verifier.verifyConditions(AND, listOf(condition))

        val result = captureResult()
        assertEquals("balance 42", result.recognizedText)
        assertNull(result.numberDetected)
    }

    @Test
    fun `result hook carries the verification-start capture timestamp`() = runTest {
        val area = Rect(0, 0, 10, 10)
        val condition = ScreenCondition.Number(
            id = Identifier(databaseId = 3L),
            eventId = Identifier(databaseId = 1L),
            name = "num",
            threshold = 80,
            priority = 0,
            detectionArea = area,
            comparisonOperation = ComparisonOperation.GREATER,
            counterValue = CounterOperationValue.Number(0.0),
        )
        whenever(mockScalingManager.getScreenConditionScalingInfo(condition))
            .doReturn(ScreenConditionScalingInfo.Number(condition, area))
        whenever(mockImageDetector.detectNumber(any(), any()))
            .doReturn(DetectionResult(isDetected = true, confidenceRate = 90.0, position = Point(5, 5), size = Point(2, 2), numberDetected = 7.0))

        val before = System.currentTimeMillis()
        verifier.verifyConditions(AND, listOf(condition))
        val after = System.currentTimeMillis()

        val tsCaptor = argumentCaptor<Long>()
        verify(mockListener).onScreenConditionProcessingCompleted(any(), tsCaptor.capture())
        val ts = tsCaptor.firstValue
        assertTrue("deviceCapturedAtMs must be non-zero", ts > 0L)
        assertTrue("deviceCapturedAtMs must equal the verification-start time", ts in before..after)
    }
}
