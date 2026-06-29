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
package com.buzbuz.smartautoclicker.cloud.command

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.processing.domain.SmartProcessingRepository
import com.buzbuz.smartautoclicker.core.scheduling.command.LocalTrackingGateway
import com.buzbuz.smartautoclicker.localservice.LocalServiceProvider

/**
 * Real device-side gateway over LocalServiceProvider + SmartProcessingRepository. The branch LOGIC that
 * consumes this is unit-tested in AndroidTrackingControllerTest; this implementation itself is
 * device-runtime (MediaProjection / AccessibilityService / notifications) and is validated on-device / CI,
 * not in JVM unit tests. Points needing on-device verification are flagged below.
 */
class AndroidLocalTrackingGateway(
    private val context: Context,
    private val processingRepository: SmartProcessingRepository,
) : LocalTrackingGateway {

    override fun isServiceStarted(): Boolean = LocalServiceProvider.isServiceStarted()

    // FLAG (device-runtime): approximates "projection live" via the foreground service being up (the
    // service owns the projection session). Precise RECORDING/DETECTING discrimination via
    // detectionState needs on-device verification.
    override fun isProjectionLive(): Boolean = LocalServiceProvider.isServiceStarted()

    override fun isTrackingScenario(scenarioId: String): Boolean =
        processingRepository.isRunning() &&
            processingRepository.getScenarioId()?.databaseId?.toString() == scenarioId

    // FLAG (device-runtime): the cloud assignment id -> local scenario Identifier mapping is best-effort
    // (numeric id). Full cloud<->local scenario reconciliation is data-model work to verify on-device.
    // startDetection takes NO scenarioId argument (projection is assumed already live).
    override suspend fun armScenario(scenarioId: String) {
        val localId = scenarioId.toLongOrNull() ?: return
        processingRepository.setScenarioId(Identifier(databaseId = localId), markAsUsed = true)
        processingRepository.startDetection(context, liveDebugging = false, generateReport = false)
    }

    override fun notifyConsentRequired(scenarioId: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CONSENT_CHANNEL_ID, "TraxIntel remote start", NotificationManager.IMPORTANCE_HIGH),
        )
        val notification = NotificationCompat.Builder(context, CONSENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Tap to start remote tracking")
            .setContentText("Tap to start remote tracking for $scenarioId")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        // FLAG (device-runtime): requires POST_NOTIFICATIONS at runtime; a tap PendingIntent that resumes
        // setup is wired with the onboarding flow (P5-T05).
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(scenarioId.hashCode(), notification)
        }
    }

    override fun stop() {
        LocalServiceProvider.getLocalService { it?.stop() }
    }

    private companion object {
        const val CONSENT_CHANNEL_ID = "traxintel_remote_consent"
    }
}
