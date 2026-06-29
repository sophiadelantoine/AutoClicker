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
import android.os.Build

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4

import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.database.ClickDatabase
import com.buzbuz.smartautoclicker.core.database.dao.ObservationDao
import com.buzbuz.smartautoclicker.core.database.entity.ObservationEntity
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.counter.ComparisonOperation
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterOperationValue
import com.buzbuz.smartautoclicker.core.processing.domain.model.ProcessedConditionResult

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

import org.robolectric.annotation.Config

/** Tests the on-device capture seam: one extraction event -> exactly one persisted Observation. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ObservationCaptureListenerTest {

    /** In-memory ObservationDao recording inserts, for the field-level assertions. */
    private class FakeObservationDao : ObservationDao {
        val inserted = mutableListOf<ObservationEntity>()
        override suspend fun insert(observation: ObservationEntity): Long {
            inserted.add(observation)
            return 1L
        }
        override suspend fun getUploadBatch(limit: Int): List<ObservationEntity> =
            inserted.filter { it.syncState == "PENDING" || it.syncState == "FAILED" }
                .sortedBy { it.deviceCapturedAt }
                .take(limit)
        override suspend fun markSyncState(id: String, syncState: String, retryCount: Int): Int = 0
        override suspend fun getPrunableCropPaths(before: Long): List<String> = emptyList()
    }

    private fun numberScreen(
        numberDetected: Double?,
        fulfilled: Boolean,
        confidence: Double,
    ): ProcessedConditionResult.Screen {
        val condition = ScreenCondition.Number(
            id = Identifier(databaseId = 1L),
            eventId = Identifier(databaseId = 1L),
            name = "num",
            threshold = 80,
            priority = 0,
            detectionArea = Rect(0, 0, 10, 10),
            comparisonOperation = ComparisonOperation.GREATER,
            counterValue = CounterOperationValue.Number(0.0),
        )
        return ProcessedConditionResult.Screen(
            isFulfilled = fulfilled,
            haveBeenDetected = fulfilled,
            condition = condition,
            confidenceRate = confidence,
            position = null,
            size = null,
            numberDetected = numberDetected,
            recognizedText = null,
        )
    }

    @Test
    fun number_event_buildsOneObservationWithExpectedFields() = runTest {
        val dao = FakeObservationDao()
        val listener = ObservationCaptureListener(
            deviceId = "dev-1",
            scenarioIdProvider = { "scn-1" },
            observationDao = dao,
            coroutineScope = this,
            idGenerator = { "obs-fixed" },
        )

        listener.onScreenConditionProcessingCompleted(
            numberScreen(numberDetected = 42.0, fulfilled = true, confidence = 88.0),
            deviceCapturedAtMs = 12_345L,
        )
        advanceUntilIdle()

        assertEquals(1, dao.inserted.size)
        val e = dao.inserted.single()
        assertEquals("obs-fixed", e.id)
        assertEquals("dev-1", e.deviceId)
        assertEquals("scn-1", e.scenarioId)
        assertEquals(12_345L, e.deviceCapturedAt)
        assertEquals("NUMBER", e.valueType)
        assertEquals("42.0", e.value)
        assertEquals(88, e.confidence)
        assertEquals(true, e.isFulfilled)
        assertNull(e.cropPath)
        assertEquals("PENDING", e.syncState)
    }

    @Test
    fun falseNumberRead_isPersistedNotDropped() = runTest {
        val dao = FakeObservationDao()
        val listener = ObservationCaptureListener("dev-1", { "scn-1" }, dao, this)

        listener.onScreenConditionProcessingCompleted(
            numberScreen(numberDetected = null, fulfilled = false, confidence = 0.0),
            deviceCapturedAtMs = 1L,
        )
        advanceUntilIdle()

        assertEquals(1, dao.inserted.size)
        assertEquals(false, dao.inserted.single().isFulfilled)
        assertNull(dao.inserted.single().value)
    }

    @Test
    fun number_event_yieldsExactlyOneObservationRow() = runTest {
        // Immediate executors so Room's suspend insert/query run inline on the test dispatcher,
        // keeping the launched capture coroutine deterministic under advanceUntilIdle().
        val immediate = java.util.concurrent.Executor { it.run() }
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ClickDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryExecutor(immediate)
            .setTransactionExecutor(immediate)
            .build()
        try {
            val listener = ObservationCaptureListener(
                deviceId = "dev-1",
                scenarioIdProvider = { "scn-1" },
                observationDao = db.observationDao(),
                coroutineScope = this,
                idGenerator = { "obs-1" },
            )

            listener.onScreenConditionProcessingCompleted(
                numberScreen(numberDetected = 7.0, fulfilled = true, confidence = 90.0),
                deviceCapturedAtMs = 999L,
            )
            advanceUntilIdle()

            // Exactly one observation_table row from one extraction event.
            assertEquals(1, db.observationDao().getUploadBatch(10).size)
        } finally {
            db.close()
        }
    }
}
