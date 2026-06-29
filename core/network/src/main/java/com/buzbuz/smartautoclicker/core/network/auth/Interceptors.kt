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
package com.buzbuz.smartautoclicker.core.network.auth

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the device-scoped bearer token to /v1 requests. The enrollment call carries no token
 * (the device has none yet), so it is left unauthenticated.
 */
class BearerAuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath.endsWith(ENROLL_PATH_SUFFIX)) return chain.proceed(request)
        val token = tokenProvider() ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header("Authorization", "Bearer $token").build(),
        )
    }

    private companion object {
        const val ENROLL_PATH_SUFFIX = "devices:enroll"
    }
}

/**
 * Sets X-TraxIntel-Client: <appVersion>/<dbVersion>/<protocol> on every request. The inputs are
 * injected (constructor), not hard-coded at the call site; protocol is the literal wire version.
 */
class TraxIntelClientInterceptor(
    appVersion: String,
    dbVersion: Int,
    protocol: Int = PROTOCOL_VERSION,
) : Interceptor {

    private val headerValue: String = "$appVersion/$dbVersion/$protocol"

    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header(HEADER_NAME, headerValue).build())

    companion object {
        const val HEADER_NAME = "X-TraxIntel-Client"
        const val PROTOCOL_VERSION = 1
    }
}
