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

import com.buzbuz.smartautoclicker.core.network.TraxIntelApiService
import com.buzbuz.smartautoclicker.core.network.auth.DeviceToken
import com.buzbuz.smartautoclicker.core.network.auth.DeviceTokenDataSource
import com.buzbuz.smartautoclicker.core.network.dto.EnrollRequestDto
import com.buzbuz.smartautoclicker.core.observation.identity.DeviceIdentityDataSource

import retrofit2.HttpException

/** Device descriptor sent at enrollment (no client deviceId — the server assigns it). */
data class DeviceDescriptor(val model: String?, val osVersion: String?, val appVersion: String?)

sealed interface EnrollmentResult {
    data class Success(val deviceId: String, val tenantId: String) : EnrollmentResult

    /** 410: the pairing code expired. Terminal — no retry, nothing persisted. */
    data object PairingCodeExpired : EnrollmentResult

    /** 404: unknown pairing code. Terminal — no retry, nothing persisted. */
    data object PairingCodeInvalid : EnrollmentResult

    /** Network/5xx/unknown error — caller may retry; nothing persisted. */
    data class Error(val cause: Throwable) : EnrollmentResult
}

/**
 * Enroll this device with a pairing code. On success: persist the device token, reconcile the
 * server deviceId as the canonical identity, and mark the device account-bound. 410/404 are terminal
 * and persist nothing.
 */
class EnrollmentRepository(
    private val api: TraxIntelApiService,
    private val tokenStore: DeviceTokenDataSource,
    private val identity: DeviceIdentityDataSource,
) {

    suspend fun enroll(pairingCode: String, descriptor: DeviceDescriptor): EnrollmentResult =
        try {
            val response = api.enroll(
                EnrollRequestDto(
                    pairingCode = pairingCode,
                    model = descriptor.model,
                    osVersion = descriptor.osVersion,
                    appVersion = descriptor.appVersion,
                ),
            )
            tokenStore.save(
                DeviceToken(
                    deviceToken = response.deviceToken,
                    tenantId = response.tenantId,
                    deviceId = response.deviceId,
                    tokenExpiresAt = response.tokenExpiresAt,
                ),
            )
            // Adopt the server deviceId as canonical and set KEY_ACCOUNT_BOUND.
            identity.reconcileWithServer(response.deviceId)
            EnrollmentResult.Success(deviceId = response.deviceId, tenantId = response.tenantId)
        } catch (e: HttpException) {
            when (e.code()) {
                410 -> EnrollmentResult.PairingCodeExpired
                404 -> EnrollmentResult.PairingCodeInvalid
                else -> EnrollmentResult.Error(e)
            }
        } catch (e: Exception) {
            EnrollmentResult.Error(e)
        }
}
