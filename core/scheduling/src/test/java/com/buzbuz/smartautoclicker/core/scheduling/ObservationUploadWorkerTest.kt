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
package com.buzbuz.smartautoclicker.core.scheduling

import android.content.Context

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder

import com.buzbuz.smartautoclicker.core.capture.sync.ObservationSyncRepository
import com.buzbuz.smartautoclicker.core.network.TraxIntelApiService
import com.buzbuz.smartautoclicker.core.network.dto.EnrollRequestDto
import com.buzbuz.smartautoclicker.core.network.dto.EnrollResponseDto
import com.buzbuz.smartautoclicker.core.network.dto.ObservationBatchRequestDto
import com.buzbuz.smartautoclicker.core.network.dto.ObservationBatchResponseDto
import com.buzbuz.smartautoclicker.core.network.dto.ObservationResultDto
import com.buzbuz.smartautoclicker.core.observation.sync.PendingUpload

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

import kotlinx.coroutines.test.runTest

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

import retrofit2.HttpException
import retrofit2.Response

import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class ObservationUploadWorkerTest {

    /** Records each POSTed observation-id set; its response is configurable per test. */
    private class FakeApiService : TraxIntelApiService {
        val postedIdSets = mutableListOf<Set<String>>()
        var responder: (ObservationBatchRequestDto) -> ObservationBatchResponseDto = { req ->
            ObservationBatchResponseDto(req.observations.map { ObservationResultDto(it.id, "accepted") })
        }

        override suspend fun enroll(request: EnrollRequestDto): EnrollResponseDto = error("not used")

        override suspend fun uploadObservations(request: ObservationBatchRequestDto): ObservationBatchResponseDto {
            postedIdSets.add(request.observations.map { it.id }.toSet())
            return responder(request)
        }
    }

    private fun pending(id: String, retry: Int = 0) =
        PendingUpload(id, "scn", "dev", 1_000L, "v", "TEXT", 90, isFulfilled = true, hasCrop = false, retryCount = retry)

    private fun buildWorker(repo: ObservationSyncRepository, api: TraxIntelApiService): ObservationUploadWorker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TestListenableWorkerBuilder<ObservationUploadWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(c: Context, name: String, params: WorkerParameters) =
                    ObservationUploadWorker(c, params, repo, api)
            })
            .build()
    }

    private fun httpException(code: Int) =
        HttpException(Response.error<ObservationBatchResponseDto>(code, "{}".toResponseBody("application/json".toMediaType())))

    @Test
    fun fullBatchSyncedOn2xx() = runTest {
        val repo = mockk<ObservationSyncRepository>(relaxed = true)
        coEvery { repo.getUploadBatch(any()) } returns listOf(pending("a"), pending("b"))
        val result = buildWorker(repo, FakeApiService()).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify { repo.markUploading(listOf("a", "b")) }
        coVerify { repo.markSynced("a") }
        coVerify { repo.markSynced("b") }
    }

    @Test
    fun duplicateStatusCountsAsSynced() = runTest {
        val repo = mockk<ObservationSyncRepository>(relaxed = true)
        coEvery { repo.getUploadBatch(any()) } returns listOf(pending("a"), pending("b"))
        val api = FakeApiService().apply {
            responder = {
                ObservationBatchResponseDto(
                    listOf(ObservationResultDto("a", "accepted"), ObservationResultDto("b", "duplicate")),
                )
            }
        }
        val result = buildWorker(repo, api).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify { repo.markSynced("a") }
        coVerify { repo.markSynced("b") }
    }

    @Test
    fun retryable5xxRequeuesAndRetries() = runTest {
        val repo = mockk<ObservationSyncRepository>(relaxed = true)
        coEvery { repo.getUploadBatch(any()) } returns listOf(pending("a", retry = 1))
        val api = FakeApiService().apply { responder = { throw httpException(503) } }
        val result = buildWorker(repo, api).doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        // worker passes the row's current retryCount; the repository performs the +1 internally
        coVerify { repo.markFailed("a", 1) }
    }

    @Test
    fun ioExceptionRetries() = runTest {
        val repo = mockk<ObservationSyncRepository>(relaxed = true)
        coEvery { repo.getUploadBatch(any()) } returns listOf(pending("a"))
        val api = FakeApiService().apply { responder = { throw IOException("network down") } }
        assertTrue(buildWorker(repo, api).doWork() is ListenableWorker.Result.Retry)
    }

    @Test
    fun http429Retries() = runTest {
        val repo = mockk<ObservationSyncRepository>(relaxed = true)
        coEvery { repo.getUploadBatch(any()) } returns listOf(pending("a"))
        val api = FakeApiService().apply { responder = { throw httpException(429) } }
        assertTrue(buildWorker(repo, api).doWork() is ListenableWorker.Result.Retry)
    }

    @Test
    fun rerunAfterMidFlightFailureProducesSameIdSet_noDuplicates() = runTest {
        val repo = mockk<ObservationSyncRepository>(relaxed = true)
        // same batch returned on both runs (rows still PENDING/FAILED after a mid-flight failure)
        coEvery { repo.getUploadBatch(any()) } returns listOf(pending("a"), pending("b"))
        val api = FakeApiService()

        buildWorker(repo, api).doWork()
        buildWorker(repo, api).doWork()

        assertEquals(2, api.postedIdSets.size)
        assertEquals(api.postedIdSets[0], api.postedIdSets[1]) // ids never mutated between attempts
        assertEquals(setOf("a", "b"), api.postedIdSets[0]) // no duplicate / extra ids
    }
}
