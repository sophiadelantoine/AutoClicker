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
package com.buzbuz.smartautoclicker.core.network

import kotlinx.serialization.json.Json

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient

import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Builds the typed /v1 client. [baseUrl] must end with '/' (e.g. https://api.example.com/). */
object TraxIntelApiFactory {

    val json: Json = Json {
        ignoreUnknownKeys = true // tolerate server-added fields
        explicitNulls = false // omit null optionals (model/osVersion/...) from request bodies
    }

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    fun create(baseUrl: String, client: OkHttpClient = OkHttpClient()): TraxIntelApiService =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
            .build()
            .create(TraxIntelApiService::class.java)
}
