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

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class InterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(token: String?) = OkHttpClient.Builder()
        .addInterceptor(BearerAuthInterceptor { token })
        .addInterceptor(TraxIntelClientInterceptor(appVersion = "4.0.0-beta02", dbVersion = 22))
        .build()

    @Test
    fun batchRequest_carriesBearerAndClientHeader() {
        server.enqueue(MockResponse().setResponseCode(200))
        client("JWT123").newCall(Request.Builder().url(server.url("/v1/observations:batch")).build())
            .execute().close()

        val recorded = server.takeRequest()
        assertEquals("Bearer JWT123", recorded.getHeader("Authorization"))
        assertEquals("4.0.0-beta02/22/1", recorded.getHeader(TraxIntelClientInterceptor.HEADER_NAME))
    }

    @Test
    fun enrollRequest_omitsBearerButKeepsClientHeader() {
        server.enqueue(MockResponse().setResponseCode(200))
        client("JWT123").newCall(Request.Builder().url(server.url("/v1/devices:enroll")).build())
            .execute().close()

        val recorded = server.takeRequest()
        assertNull("enroll must not be authenticated", recorded.getHeader("Authorization"))
        assertEquals("4.0.0-beta02/22/1", recorded.getHeader(TraxIntelClientInterceptor.HEADER_NAME))
    }

    @Test
    fun noToken_omitsBearer() {
        server.enqueue(MockResponse().setResponseCode(200))
        client(null).newCall(Request.Builder().url(server.url("/v1/observations:batch")).build())
            .execute().close()

        assertNull(server.takeRequest().getHeader("Authorization"))
    }
}
