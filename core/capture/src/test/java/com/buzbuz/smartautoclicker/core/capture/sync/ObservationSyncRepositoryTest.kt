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
package com.buzbuz.smartautoclicker.core.capture.sync

import com.buzbuz.smartautoclicker.core.database.dao.ObservationDao
import com.buzbuz.smartautoclicker.core.database.entity.ObservationEntity

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationSyncRepositoryTest {

    private val dao = mockk<ObservationDao>(relaxed = true)
    private val repository = ObservationSyncRepository(dao)

    @Test
    fun markUploading_usesStateOnlyBatchTransition() = runTest {
        repository.markUploading(listOf("a", "b"))
        coVerify { dao.markStateForIds(listOf("a", "b"), "UPLOADING") }
    }

    @Test
    fun markUploading_emptyIsNoOp() = runTest {
        repository.markUploading(emptyList())
        coVerify(exactly = 0) { dao.markStateForIds(any(), any()) }
    }

    @Test
    fun markSynced_setsSyncedStateOnly() = runTest {
        repository.markSynced("a")
        coVerify { dao.markStateForIds(listOf("a"), "SYNCED") }
    }

    @Test
    fun markFailed_incrementsRetryCount() = runTest {
        repository.markFailed("a", currentRetryCount = 1)
        coVerify { dao.markSyncState("a", "FAILED", 2) }
    }

    @Test
    fun recoverStuckUploads_delegatesToDao() = runTest {
        coEvery { dao.resetStuckUploadingToFailed() } returns 3
        assertEquals(3, repository.recoverStuckUploads())
        coVerify { dao.resetStuckUploadingToFailed() }
    }

    @Test
    fun getUploadBatch_mapsEntities_hasCropFromCropPath() = runTest {
        coEvery { dao.getUploadBatch(10) } returns listOf(
            ObservationEntity("a", "dev", "scn", 100, "v", "TEXT", 90, true, "/crops/a.bin", "PENDING", 0),
            ObservationEntity("b", "dev", "scn", 200, null, "NUMBER", 50, false, null, "FAILED", 4),
        )

        val batch = repository.getUploadBatch(10)

        assertEquals(2, batch.size)
        assertTrue(batch[0].hasCrop)
        assertFalse(batch[1].hasCrop)
        assertEquals(4, batch[1].retryCount)
        assertEquals("scn", batch[0].scenarioId)
    }
}
