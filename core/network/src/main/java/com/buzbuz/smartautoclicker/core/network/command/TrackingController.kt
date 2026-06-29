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
package com.buzbuz.smartautoclicker.core.network.command

import com.buzbuz.smartautoclicker.core.network.dto.AckReason

/**
 * Consent-aware seam for executing remote START/STOP commands, so the command-pull path depends only
 * on this interface (no edge onto core:scheduling or the local service).
 *
 * - START is consent-gated: arm immediately if a projection session is already live, otherwise post a
 *   notification and return [CommandOutcome.PendingConsent] (never start MediaProjection in background).
 * - STOP is always unattended (tears down detection/projection).
 *
 * [scenarioId] is the assignment identifier passed to setScenarioId on the implementation side — it is
 * NOT a startDetection parameter (that API takes none).
 */
interface TrackingController {

    suspend fun requestStart(scenarioId: String): CommandOutcome

    suspend fun stop(scenarioId: String?): CommandOutcome
}

/** Outcome of a command, carrying the [ackReason] used verbatim in the canonical ack body (P4-T00). */
sealed class CommandOutcome(val ackReason: String) {
    data object Applied : CommandOutcome(AckReason.APPLIED)
    data object PendingConsent : CommandOutcome(AckReason.PENDING_CONSENT)
    data object NoOp : CommandOutcome(AckReason.NOOP)
}
