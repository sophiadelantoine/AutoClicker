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
package com.buzbuz.smartautoclicker.core.scheduling

import android.content.Context

import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

import com.buzbuz.smartautoclicker.core.capture.sync.ObservationSyncRepository
import com.buzbuz.smartautoclicker.core.network.TraxIntelApiService
import com.buzbuz.smartautoclicker.core.network.dto.ObservationBatchRequestDto
import com.buzbuz.smartautoclicker.core.network.dto.ObservationUploadDto
import com.buzbuz.smartautoclicker.core.observation.sync.PendingUpload

import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

import retrofit2.HttpException

import java.io.IOException
import java.time.Instant

/**
 * Batched, offline-tolerant observation uploader. Pulls a PENDING/FAILED batch, marks it UPLOADING,
 * POSTs to /v1/observations:batch, and lands accepted|duplicate rows as SYNCED. Retryable errors
 * (5xx / IO / timeout / 429) re-queue the rows (FAILED, retry+1) and return Result.retry() for
 * WorkManager backoff. The client UUID id is never mutated, so server dedup on (tenant_id, id)
 * makes retries (and ambiguous timeouts) zero-duplicate. Terminal 401 handling is added in P3-T07.
 */
@HiltWorker
class ObservationUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: ObservationSyncRepository,
    private val api: TraxIntelApiService,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val batch = syncRepository.getUploadBatch(BATCH_SIZE)
        if (batch.isEmpty()) return Result.success()

        syncRepository.markUploading(batch.map { it.id })

        return try {
            val response = api.uploadObservations(ObservationBatchRequestDto(batch.map { it.toUploadDto() }))
            val acknowledged = response.results
                .filter { it.status == STATUS_ACCEPTED || it.status == STATUS_DUPLICATE }
                .map { it.id }
                .toSet()

            batch.forEach { row ->
                if (row.id in acknowledged) {
                    syncRepository.markSynced(row.id)
                } else {
                    // Not acknowledged by the server: keep it re-uploadable for a later run.
                    syncRepository.markFailed(row.id, row.retryCount)
                }
            }
            if (batch.all { it.id in acknowledged }) Result.success() else Result.retry()
        } catch (e: HttpException) {
            requeue(batch)
            if (e.code() in 500..599 || e.code() == HTTP_TOO_MANY_REQUESTS) Result.retry() else Result.failure()
        } catch (_: IOException) {
            // Network / timeout (incl. ambiguous): safe to re-enqueue (idempotent ids).
            requeue(batch)
            Result.retry()
        }
    }

    private suspend fun requeue(batch: List<PendingUpload>) =
        batch.forEach { syncRepository.markFailed(it.id, it.retryCount) }

    private fun PendingUpload.toUploadDto() = ObservationUploadDto(
        id = id,
        scenarioId = scenarioId,
        deviceCapturedAt = Instant.ofEpochMilli(deviceCapturedAt).toString(), // ISO-8601 UTC
        value = value,
        valueType = valueType,
        confidence = confidence,
        isFulfilled = isFulfilled,
        hasCrop = hasCrop,
    )

    companion object {
        const val WORK_NAME = "traxintel-observation-upload"
        const val BATCH_SIZE = 100 // server max
        private const val STATUS_ACCEPTED = "accepted"
        private const val STATUS_DUPLICATE = "duplicate"
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
