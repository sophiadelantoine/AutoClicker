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

import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * No-op WorkManager worker proving the Hilt-Work pipeline: it is constructed by the HiltWorkerFactory,
 * resolves an injected [SchedulingMarker], and succeeds. The real batched uploader (P3-T06) replaces
 * the body; the foreground-service lifecycle is unaffected.
 */
@HiltWorker
class NoOpUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val marker: SchedulingMarker,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        marker.markRan()
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "traxintel-observation-upload"
    }
}
