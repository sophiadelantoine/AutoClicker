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
package com.buzbuz.smartautoclicker.core.scheduling.command

/**
 * Device-side seam the [AndroidTrackingController] drives. The concrete implementation (app src/cloud,
 * over LocalServiceProvider + SmartProcessingRepository) is the only part that touches MediaProjection /
 * AccessibilityService; keeping it behind this interface makes the controller's branch logic unit-testable.
 */
interface LocalTrackingGateway {

    /** Whether the local foreground service is running at all. */
    fun isServiceStarted(): Boolean

    /** AccessibilityService connected AND a projection session is live (can arm without a new grant). */
    fun isProjectionLive(): Boolean

    /** True if detection is currently running for [scenarioId]. */
    fun isTrackingScenario(scenarioId: String): Boolean

    /**
     * Assign the scenario via SmartProcessingRepository.setScenarioId(Identifier) and trigger the
     * service-bound start (startScreenRecord/startDetection). startDetection takes NO scenarioId argument.
     */
    suspend fun armScenario(scenarioId: String)

    /** Post a high-priority "tap to start remote tracking for <scenario>" consent notification. */
    fun notifyConsentRequired(scenarioId: String)

    /** Tear down detection/projection (unattended). */
    fun stop()
}
