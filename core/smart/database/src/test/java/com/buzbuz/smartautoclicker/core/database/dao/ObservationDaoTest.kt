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

import android.os.Build

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4

import com.buzbuz.smartautoclicker.core.database.ClickDatabase
import com.buzbuz.smartautoclicker.core.database.entity.ObservationEntity

import kotlinx.coroutines.test.runTest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import org.robolectric.annotation.Config

/** Tests for [ObservationDao] backed by an in-memory [ClickDatabase] (schema v22). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ObservationDaoTest {

    private lateinit var database: ClickDatabase
    private lateinit var dao: ObservationDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ClickDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.observationDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun observation(id: String, syncState: String = "PENDING", capturedAt: Long = 1_000L) =
        ObservationEntity(
            id = id,
            deviceId = "dev-1",
            scenarioId = "scn-1",
            deviceCapturedAt = capturedAt,
            value = "42",
            valueType = "NUMBER",
            confidence = 90,
            isFulfilled = true,
            cropPath = null,
            syncState = syncState,
        )

    @Test
    fun insert_isIdempotentOnDuplicateId() = runTest {
        val first = dao.insert(observation("obs-1"))
        val duplicate = dao.insert(observation("obs-1"))

        assertNotEquals("first insert should persist a row", -1L, first)
        assertEquals("duplicate client id must be a no-op (IGNORE)", -1L, duplicate)
    }

    @Test
    fun getUploadBatch_returnsPendingAndFailedOldestFirst() = runTest {
        dao.insert(observation("a", capturedAt = 200L))
        dao.insert(observation("b", capturedAt = 100L))
        dao.insert(observation("failed", syncState = "FAILED", capturedAt = 50L))
        dao.insert(observation("synced", syncState = "SYNCED", capturedAt = 10L))

        val batch = dao.getUploadBatch(10)

        // SYNCED excluded; PENDING + FAILED ordered by device_captured_at ascending.
        assertEquals(listOf("failed", "b", "a"), batch.map { it.id })
    }
}
