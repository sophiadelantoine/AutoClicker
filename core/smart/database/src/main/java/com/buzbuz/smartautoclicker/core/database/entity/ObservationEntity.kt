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
package com.buzbuz.smartautoclicker.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

import com.buzbuz.smartautoclicker.core.database.OBSERVATION_TABLE

/**
 * A single on-device screen observation captured by TraxIntel, awaiting upload to the cloud.
 *
 * Has intentionally NO foreign key to scenario_table: [scenarioId] is the cloud-side scenario UUID
 * (not a local row id), so observations survive local scenario edits/deletes and remain uploadable.
 * [id] is a client-generated UUID, which doubles as the server idempotency key.
 */
@Entity(
    tableName = OBSERVATION_TABLE,
    indices = [Index("sync_state"), Index("scenario_id")],
)
data class ObservationEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "scenario_id") val scenarioId: String,
    @ColumnInfo(name = "device_captured_at") val deviceCapturedAt: Long,
    @ColumnInfo(name = "value") val value: String?,
    @ColumnInfo(name = "value_type") val valueType: String,
    @ColumnInfo(name = "confidence") val confidence: Int,
    @ColumnInfo(name = "is_fulfilled") val isFulfilled: Boolean,
    @ColumnInfo(name = "crop_path") val cropPath: String?,
    @ColumnInfo(name = "sync_state") val syncState: String,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
)
