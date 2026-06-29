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
package com.buzbuz.smartautoclicker.core.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

import com.buzbuz.smartautoclicker.core.database.OBSERVATION_TABLE

/**
 * Migration from database v21 to v22.
 *
 * TraxIntel: introduces the on-device observation capture queue ([OBSERVATION_TABLE]) plus its
 * sync_state / scenario_id indices. Additive only — no existing table is touched. The statements
 * mirror Room's exported v22 schema exactly so MigrationTestHelper validation passes.
 */
object Migration21to22 : Migration(21, 22) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `$OBSERVATION_TABLE` (" +
                "`id` TEXT NOT NULL, `device_id` TEXT NOT NULL, `scenario_id` TEXT NOT NULL, " +
                "`device_captured_at` INTEGER NOT NULL, `value` TEXT, `value_type` TEXT NOT NULL, " +
                "`confidence` INTEGER NOT NULL, `is_fulfilled` INTEGER NOT NULL, `crop_path` TEXT, " +
                "`sync_state` TEXT NOT NULL, `retry_count` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_observation_table_sync_state` ON `$OBSERVATION_TABLE` (`sync_state`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_observation_table_scenario_id` ON `$OBSERVATION_TABLE` (`scenario_id`)"
        )
    }
}
