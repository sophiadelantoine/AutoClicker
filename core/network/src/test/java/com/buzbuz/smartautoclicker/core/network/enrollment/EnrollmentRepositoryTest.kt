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
package com.buzbuz.smartautoclicker.core.network.enrollment

import android.content.Context

import androidx.test.core.app.ApplicationProvider

import com.buzbuz.smartautoclicker.core.network.TraxIntelApiService
import com.buzbuz.smartautoclicker.core.network.auth.DeviceTokenDataSource
import com.buzbuz.smartautoclicker.core.network.dto.EnrollResponseDto
import com.buzbuz.smartautoclicker.core.observation.identity.DeviceIdentityDataSource

import io.mockk.coEvery
import io.mockk.mockk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

import retrofit2.HttpException
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class EnrollmentRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val api = mockk<TraxIntelApiService>()

    @Test
    fun success_persistsTokenReconcilesIdAndBinds() = runTest {
        val tokenStore = DeviceTokenDataSource(context, Dispatchers.IO, "enroll_ok_token")
        val identity = DeviceIdentityDataSource(context, Dispatchers.IO, "enroll_ok_id")
        coEvery { api.enroll(any()) } returns
            EnrollResponseDto("srv-dev", "tenant-1", "jwt.tok", "2027-01-01T00:00:00Z")
        val repository = EnrollmentRepository(api, tokenStore, identity)

        val result = repository.enroll("CODE123", DeviceDescriptor("Pixel 8", "14", "4.0.0-beta02"))

        assertTrue(result is EnrollmentResult.Success)
        assertEquals("srv-dev", (result as EnrollmentResult.Success).deviceId)
        assertEquals("tenant-1", result.tenantId)
        // token persisted via the P3-T02 store
        assertEquals("jwt.tok", tokenStore.current()?.deviceToken)
        assertEquals("srv-dev", tokenStore.current()?.deviceId)
        // server id reconciled as canonical + account-bound
        assertEquals("srv-dev", identity.effectiveDeviceId())
        assertTrue(identity.isAccountBound())
    }

    @Test
    fun expiredPairingCode_isTerminalAndPersistsNothing() = runTest {
        val tokenStore = DeviceTokenDataSource(context, Dispatchers.IO, "enroll_410_token")
        val identity = DeviceIdentityDataSource(context, Dispatchers.IO, "enroll_410_id")
        coEvery { api.enroll(any()) } throws expiredHttpException()
        val repository = EnrollmentRepository(api, tokenStore, identity)

        val result = repository.enroll("OLD", DeviceDescriptor(null, null, null))

        assertTrue(result is EnrollmentResult.PairingCodeExpired)
        assertNull("nothing persisted on a terminal expiry", tokenStore.current())
        assertFalse(identity.isAccountBound())
    }

    private fun expiredHttpException(): HttpException {
        val body = """{"error":"pairing_code_expired"}""".toResponseBody("application/json".toMediaType())
        return HttpException(Response.error<EnrollResponseDto>(410, body))
    }
}
