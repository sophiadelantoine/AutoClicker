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
import com.buzbuz.smartautoclicker.core.network.auth.DeviceToken
import com.buzbuz.smartautoclicker.core.network.auth.DeviceTokenDataSource
import com.buzbuz.smartautoclicker.core.network.settings.CloudSyncSettingsDataSource
import com.buzbuz.smartautoclicker.core.observation.identity.DeviceIdentityDataSource
import com.buzbuz.smartautoclicker.core.network.dto.EnrollRequestDto
import com.buzbuz.smartautoclicker.core.network.dto.EnrollResponseDto
import com.buzbuz.smartautoclicker.core.network.dto.ObservationBatchRequestDto
import com.buzbuz.smartautoclicker.core.network.dto.ObservationBatchResponseDto
import com.buzbuz.smartautoclicker.core.network.dto.ObservationResultDto
import com.buzbuz.smartautoclicker.core.observation.sync.PendingUpload

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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

    /** An identity mock reporting the device as enrolled (account-bound). */
    private fun enrolledIdentity() =
        mockk<DeviceIdentityDataSource>(relaxed = true).also { coEvery { it.isAccountBound() } returns true }

    /** A settings mock reporting cloud sync enabled. */
    private fun syncEnabledSettings() =
        mockk<CloudSyncSettingsDataSource>(relaxed = true).also { coEvery { it.isEnabled() } returns true }

    private fun buildWorker(
        repo: ObservationSyncRepository,
        api: TraxIntelApiService,
        tokenStore: DeviceTokenDataSource = mockk(relaxed = true),
        identity: DeviceIdentityDataSource = enrolledIdentity(),
        cloudSync: CloudSyncSettingsDataSource = syncEnabledSettings(),
    ): ObservationUploadWorker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TestListenableWorkerBuilder<ObservationUploadWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(c: Context, name: String, params: WorkerParameters) =
                    ObservationUploadWorker(c, params, repo, api, tokenStore, identity, cloudSync)
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
    fun revoked401IsTerminal_clearsCredentialsAndLeavesRowsRecoverable() = runTest {
        val repo = mockk<ObservationSyncRepository>(relaxed = true)
        coEvery { repo.getUploadBatch(any()) } returns listOf(pending("a"))
        val tokenStore = mockk<DeviceTokenDataSource>(relaxed = true)
        // a token IS present (hydrated + snapshot) -> a 401 means genuine revocation
        val token = DeviceToken("jwt", "t", "d", "2027-01-01T00:00:00Z")
        coEvery { tokenStore.current() } returns token
        every { tokenStore.cachedToken() } returns token
        val identity = enrolledIdentity()
        val api = FakeApiService().apply { responder = { throw httpException(401) } }

        val result = buildWorker(repo, api, tokenStore, identity).doWork()

        // terminal: failure, NOT retry (no backoff scheduled)
        assertTrue(result is ListenableWorker.Result.Failure)
        coVerify { tokenStore.clear() }
        coVerify { identity.clearAccountBinding() }
        // in-flight row left re-uploadable (not stuck UPLOADING)
        coVerify { repo.markFailed("a", 0) }
    }

    @Test
    fun unauthenticated401WithNoTokenAttached_retriesWithoutClearingCredentials() = runTest {
        // cold-process cache miss: no token was sent, so a 401 is transient, not a revocation
        val repo = mockk<ObservationSyncRepository>(relaxed = true)
        coEvery { repo.getUploadBatch(any()) } returns listOf(pending("a"))
        val tokenStore = mockk<DeviceTokenDataSource>(relaxed = true)
        coEvery { tokenStore.current() } returns null
        every { tokenStore.cachedToken() } returns null // no token was attached
        val api = FakeApiService().apply { responder = { throw httpException(401) } }

        val result = buildWorker(repo, api, tokenStore).doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        coVerify(exactly = 0) { tokenStore.clear() }
    }

    @Test
    fun recoversStuckUploadingRowsBeforeFetchingBatch() = runTest {
        val repo = mockk<ObservationSyncRepository>(relaxed = true)
        coEvery { repo.getUploadBatch(any()) } returns emptyList()

        buildWorker(repo, FakeApiService()).doWork()

        coVerify { repo.recoverStuckUploads() }
    }

    @Test
    fun notAccountBound_isNoOp_noNetworkNoWork() = runTest {
        val repo = mockk<ObservationSyncRepository>(relaxed = true)
        val api = FakeApiService()
        val identity = mockk<DeviceIdentityDataSource>(relaxed = true)
        coEvery { identity.isAccountBound() } returns false // not enrolled

        val result = buildWorker(repo, api, identity = identity).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue("no network call", api.postedIdSets.isEmpty())
        coVerify(exactly = 0) { repo.getUploadBatch(any()) } // no SyncState work
    }

    @Test
    fun accountBoundButSyncDisabled_isNoOp() = runTest {
        val repo = mockk<ObservationSyncRepository>(relaxed = true)
        val api = FakeApiService()
        val settings = mockk<CloudSyncSettingsDataSource>(relaxed = true)
        coEvery { settings.isEnabled() } returns false // sync toggle off

        val result = buildWorker(repo, api, identity = enrolledIdentity(), cloudSync = settings).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(api.postedIdSets.isEmpty())
        coVerify(exactly = 0) { repo.getUploadBatch(any()) }
    }

    @Test
    fun bothGatesTrue_proceedsToFetchBatch() = runTest {
        val repo = mockk<ObservationSyncRepository>(relaxed = true)
        coEvery { repo.getUploadBatch(any()) } returns emptyList()

        buildWorker(repo, FakeApiService()).doWork() // defaults: enrolled + sync enabled

        coVerify { repo.getUploadBatch(any()) }
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
