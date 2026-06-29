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

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager

import java.util.concurrent.TimeUnit

/**
 * Schedules remote command polling. The periodic poll is the fDroidCloud fallback (no FCM); fetchNow is
 * the wake/heartbeat-driven one-shot. Enqueued from the cloud-only Application after enrollment, so it is
 * absent from LOCAL builds (no WorkManager linked there).
 */
object CommandSyncScheduler {

    const val FETCH_NOW_TAG = "traxintel-command-pull-now"

    private val connectedConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<CommandPullWorker>(POLL_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(connectedConstraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CommandPullWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun fetchNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CommandPullWorker>()
            .setConstraints(connectedConstraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(FETCH_NOW_TAG)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    private const val POLL_INTERVAL_MINUTES = 15L
}
