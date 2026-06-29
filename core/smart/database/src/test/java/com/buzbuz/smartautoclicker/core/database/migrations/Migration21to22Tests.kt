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

import android.content.ContentValues
import android.content.Context
import android.os.Build

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import com.buzbuz.smartautoclicker.core.database.ClickDatabase
import com.buzbuz.smartautoclicker.core.database.EVENT_TABLE
import com.buzbuz.smartautoclicker.core.database.OBSERVATION_TABLE
import com.buzbuz.smartautoclicker.core.database.SCENARIO_TABLE

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

import org.robolectric.annotation.Config

/** Tests the manual Migration21to22 (adds observation_table). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class Migration21to22Tests {

    private companion object {
        private const val OLD_DB_VERSION = 21
        private const val NEW_DB_VERSION = 22
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ClickDatabase::class.java,
    )

    private lateinit var dbPath: String

    @Before
    fun setUp() {
        dbPath = ApplicationProvider
            .getApplicationContext<Context>()
            .getDatabasePath("migration-test").path
    }

    @Test
    fun migrate_createsObservationTable_andPreservesExistingData() {
        // Given: a v21 database with a scenario and an event
        helper.createDatabase(dbPath, OLD_DB_VERSION).use { db ->
            db.insertTestScenario(1L)
            db.insertTestEvent(1L, scenarioId = 1L)
        }

        // When
        helper.runMigrationsAndValidate(dbPath, NEW_DB_VERSION, true, Migration21to22).use { db ->
            // Then: observation_table now exists and is empty
            db.query("SELECT COUNT(*) FROM $OBSERVATION_TABLE").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            // And: existing scenario/event rows are untouched
            db.query("SELECT name FROM $SCENARIO_TABLE WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Scenario 1", cursor.getString(0))
            }
            db.query("SELECT COUNT(*) FROM $EVENT_TABLE WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate_observationTable_acceptsInsert() {
        helper.createDatabase(dbPath, OLD_DB_VERSION).close()

        helper.runMigrationsAndValidate(dbPath, NEW_DB_VERSION, true, Migration21to22).use { db ->
            db.insert(OBSERVATION_TABLE, 0, ContentValues().apply {
                put("id", "obs-1")
                put("device_id", "dev-1")
                put("scenario_id", "scn-1")
                put("device_captured_at", 1_000L)
                put("value", "42")
                put("value_type", "NUMBER")
                put("confidence", 90)
                put("is_fulfilled", 1)
                put("sync_state", "PENDING")
                put("retry_count", 0)
            })

            db.query("SELECT sync_state FROM $OBSERVATION_TABLE WHERE id = 'obs-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("PENDING", cursor.getString(0))
            }
        }
    }

    // ---- Helpers ----

    private fun SupportSQLiteDatabase.insertTestScenario(id: Long) {
        insert(SCENARIO_TABLE, 0, ContentValues().apply {
            put("id", id)
            put("name", "Scenario $id")
            put("detection_quality", 1200)
            put("compute_rate", 0.0)
            put("randomize", 0)
            put("keep_screen_on", 0)
        })
    }

    private fun SupportSQLiteDatabase.insertTestEvent(id: Long, scenarioId: Long) {
        insert(EVENT_TABLE, 0, ContentValues().apply {
            put("id", id)
            put("scenario_id", scenarioId)
            put("name", "Event $id")
            put("operator", 0)
            put("priority", 0)
            put("enabled_on_start", 1)
            put("type", "IMAGE_EVENT")
        })
    }
}
