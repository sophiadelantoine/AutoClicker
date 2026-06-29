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
package com.buzbuz.smartautoclicker.core.capture.sync

import com.buzbuz.smartautoclicker.core.database.dao.ObservationDao
import com.buzbuz.smartautoclicker.core.database.dao.SyncStateCount
import com.buzbuz.smartautoclicker.core.database.entity.ObservationEntity
import com.buzbuz.smartautoclicker.core.observation.sync.PendingUpload
import com.buzbuz.smartautoclicker.core.observation.sync.SyncState

import kotlinx.coroutines.flow.Flow

/**
 * Drives the observation upload SyncState machine over [ObservationDao]. Only legal transitions are
 * applied: PENDING/FAILED -> UPLOADING -> SYNCED|FAILED. SYNCED is terminal (append-only; rows are
 * never deleted on sync). The table is the source of truth for what still needs uploading.
 */
class ObservationSyncRepository(private val dao: ObservationDao) {

    /** Oldest-first batch of not-yet-synced observations, projected for upload. */
    suspend fun getUploadBatch(limit: Int): List<PendingUpload> =
        dao.getUploadBatch(limit).map { it.toPendingUpload() }

    /** Mark a pulled batch UPLOADING in-flight (retry counts preserved). */
    suspend fun markUploading(ids: List<String>) {
        if (ids.isNotEmpty()) dao.markStateForIds(ids, SyncState.UPLOADING.name)
    }

    /** Terminal success: accepted|duplicate both land SYNCED. */
    suspend fun markSynced(id: String) {
        dao.markStateForIds(listOf(id), SyncState.SYNCED.name)
    }

    /** Retryable failure: back to FAILED with an incremented retry count. */
    suspend fun markFailed(id: String, currentRetryCount: Int) {
        dao.markSyncState(id, SyncState.FAILED.name, currentRetryCount + 1)
    }

    fun observeSyncStateCounts(): Flow<List<SyncStateCount>> = dao.observeSyncStateCounts()

    private fun ObservationEntity.toPendingUpload(): PendingUpload = PendingUpload(
        id = id,
        scenarioId = scenarioId,
        deviceId = deviceId,
        deviceCapturedAt = deviceCapturedAt,
        value = value,
        valueType = valueType,
        confidence = confidence,
        isFulfilled = isFulfilled,
        hasCrop = cropPath != null,
        retryCount = retryCount,
    )
}
