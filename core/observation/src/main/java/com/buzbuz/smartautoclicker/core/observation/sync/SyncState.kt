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
package com.buzbuz.smartautoclicker.core.observation.sync

/**
 * The upload lifecycle of a captured observation (stored as the sync_state string column).
 * Legal transitions: PENDING/FAILED -> UPLOADING -> SYNCED|FAILED. SYNCED is terminal and the table
 * is append-only (no delete-on-sync).
 */
enum class SyncState {
    PENDING,
    UPLOADING,
    SYNCED,
    FAILED,
    ;

    fun canTransitionTo(target: SyncState): Boolean = target in when (this) {
        PENDING -> setOf(UPLOADING)
        FAILED -> setOf(UPLOADING)
        UPLOADING -> setOf(SYNCED, FAILED)
        SYNCED -> emptySet()
    }
}
