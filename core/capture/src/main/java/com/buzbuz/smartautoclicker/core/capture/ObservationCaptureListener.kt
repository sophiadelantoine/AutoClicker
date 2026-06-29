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

import android.util.Log

import com.buzbuz.smartautoclicker.core.database.dao.ObservationDao
import com.buzbuz.smartautoclicker.core.database.entity.toEntity
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.event.Event
import com.buzbuz.smartautoclicker.core.observation.Observation
import com.buzbuz.smartautoclicker.core.observation.ObservationValueMapper
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingListener
import com.buzbuz.smartautoclicker.core.processing.domain.model.ProcessedConditionResult

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

import java.util.UUID

/**
 * A [SmartProcessingListener] that turns every processed screen condition into exactly one persisted
 * [Observation]: the on-device capture seam of TraxIntel.
 *
 * For each [onScreenConditionProcessingCompleted] it builds an Observation (fresh UUID id, injected
 * [deviceId], the cloud [scenarioIdProvider] id, the verification-start [deviceCapturedAtMs], and the
 * value/type/confidence mapped from the result) and inserts it as PENDING. False reads are recorded
 * too (isFulfilled = false, value = null for Number/Text), never dropped. Screenshot crops are not
 * captured in this phase ([Observation.cropPath] is always null).
 *
 * Lives in core:capture (not core:observation) because it depends on the processing and domain types,
 * which transitively depend on core:smart:database — and that module depends on core:observation, so
 * placing the listener in core:observation would form a dependency cycle.
 */
class ObservationCaptureListener(
    private val deviceId: String,
    private val scenarioIdProvider: () -> String,
    private val observationDao: ObservationDao,
    private val coroutineScope: CoroutineScope,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : SmartProcessingListener {

    // SmartProcessingListener's only non-defaulted member; the capture seam consumes per-condition
    // results (below), so the event-level hook is a no-op here.
    override fun onEventProcessingCompleted(
        event: Event,
        fulfilled: Boolean,
        results: List<ProcessedConditionResult>,
    ) = Unit

    override fun onScreenConditionProcessingCompleted(
        result: ProcessedConditionResult.Screen,
        deviceCapturedAtMs: Long,
    ) {
        val mapped = when (result.condition) {
            is ScreenCondition.Number ->
                ObservationValueMapper.forNumber(result.numberDetected, result.confidenceRate)
            is ScreenCondition.Text ->
                ObservationValueMapper.forText(result.recognizedText, result.confidenceRate)
            is ScreenCondition.Color, is ScreenCondition.Image ->
                ObservationValueMapper.forState(result.isFulfilled, result.confidenceRate)
        }

        val observation = Observation(
            id = idGenerator(),
            scenarioId = scenarioIdProvider(),
            deviceId = deviceId,
            deviceCapturedAt = deviceCapturedAtMs,
            value = mapped.value,
            valueType = mapped.valueType,
            confidence = mapped.confidence,
            isFulfilled = result.isFulfilled,
            cropPath = null,
        )

        coroutineScope.launch {
            observationDao.insert(observation.toEntity(syncState = PENDING_SYNC_STATE))
            Log.i(
                TAG,
                "Captured 1 observation ${observation.id} " +
                    "(${observation.valueType}=${observation.value}, fulfilled=${observation.isFulfilled}) " +
                    "for scenario ${observation.scenarioId}",
            )
        }
    }

    private companion object {
        private const val TAG = "ObservationCapture"
        private const val PENDING_SYNC_STATE = "PENDING"
    }
}
