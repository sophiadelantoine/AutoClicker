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
package com.buzbuz.smartautoclicker.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

import com.buzbuz.smartautoclicker.core.database.OBSERVATION_TABLE
import com.buzbuz.smartautoclicker.core.database.entity.ObservationEntity

import kotlinx.coroutines.flow.Flow

/** Access to the on-device [ObservationEntity] capture/upload queue. */
@Dao
interface ObservationDao {

    /**
     * Insert a captured observation. Uses IGNORE so a duplicate client-generated id is a no-op,
     * returning -1 (idempotent capture). A successful insert returns the inserted row id.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(observation: ObservationEntity): Long

    /** The oldest not-yet-uploaded observations (PENDING or FAILED), oldest first. */
    @Query(
        "SELECT * FROM $OBSERVATION_TABLE " +
            "WHERE sync_state IN ('PENDING', 'FAILED') " +
            "ORDER BY device_captured_at ASC LIMIT :limit"
    )
    suspend fun getUploadBatch(limit: Int): List<ObservationEntity>

    /** Update the sync state and retry count of a single observation. Returns the number of rows updated. */
    @Query("UPDATE $OBSERVATION_TABLE SET sync_state = :syncState, retry_count = :retryCount WHERE id = :id")
    suspend fun markSyncState(id: String, syncState: String, retryCount: Int): Int

    /** Batch state-only transition (e.g. marking a pulled batch UPLOADING in-flight; retry preserved). */
    @Query("UPDATE $OBSERVATION_TABLE SET sync_state = :syncState WHERE id IN (:ids)")
    suspend fun markStateForIds(ids: List<String>, syncState: String): Int

    /** Batch update of state + retry count for a set of observations. */
    @Query("UPDATE $OBSERVATION_TABLE SET sync_state = :syncState, retry_count = :retryCount WHERE id IN (:ids)")
    suspend fun markSyncState(ids: List<String>, syncState: String, retryCount: Int): Int

    /**
     * Crash recovery: reset rows left UPLOADING (worker killed mid-flight) back to FAILED so they are
     * re-uploadable. Safe because the uploader runs serially. Returns the number of rows recovered.
     */
    @Query("UPDATE $OBSERVATION_TABLE SET sync_state = 'FAILED' WHERE sync_state = 'UPLOADING'")
    suspend fun resetStuckUploadingToFailed(): Int

    /** Observable per-state counts for UI / sync-heartbeat consumers. */
    @Query("SELECT sync_state AS state, COUNT(*) AS count FROM $OBSERVATION_TABLE GROUP BY sync_state")
    fun observeSyncStateCounts(): Flow<List<SyncStateCount>>

    /**
     * Crop file paths safe to prune: those of SYNCED observations older than [before] whose crop is
     * no longer referenced by any not-yet-synced observation (reference-counted, so a crop shared by
     * a still-pending observation is never deleted out from under it).
     */
    @Query(
        "SELECT DISTINCT crop_path FROM $OBSERVATION_TABLE " +
            "WHERE crop_path IS NOT NULL AND sync_state = 'SYNCED' AND device_captured_at < :before " +
            "AND crop_path NOT IN (" +
            "  SELECT crop_path FROM $OBSERVATION_TABLE WHERE crop_path IS NOT NULL AND sync_state != 'SYNCED'" +
            ")"
    )
    suspend fun getPrunableCropPaths(before: Long): List<String>
}

/** Projection of observation counts grouped by sync state. */
data class SyncStateCount(
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "count") val count: Int,
)
