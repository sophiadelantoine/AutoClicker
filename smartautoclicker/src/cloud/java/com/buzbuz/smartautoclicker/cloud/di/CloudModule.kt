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
package com.buzbuz.smartautoclicker.cloud.di

import android.content.Context

import com.buzbuz.smartautoclicker.BuildConfig
import com.buzbuz.smartautoclicker.core.base.di.Dispatcher
import com.buzbuz.smartautoclicker.core.base.di.HiltCoroutineDispatchers
import com.buzbuz.smartautoclicker.core.capture.sync.ObservationSyncRepository
import com.buzbuz.smartautoclicker.core.database.DATABASE_VERSION
import com.buzbuz.smartautoclicker.core.database.dao.ObservationDao
import com.buzbuz.smartautoclicker.core.network.TraxIntelApiFactory
import com.buzbuz.smartautoclicker.core.network.TraxIntelApiService
import com.buzbuz.smartautoclicker.core.network.auth.DeviceTokenDataSource
import com.buzbuz.smartautoclicker.core.network.settings.CloudSyncSettingsDataSource
import com.buzbuz.smartautoclicker.core.observation.identity.DeviceIdentityDataSource

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

import kotlinx.coroutines.CoroutineDispatcher

import javax.inject.Singleton

/**
 * CLOUD-flavor-only Hilt wiring for the connectivity stack: the device token store, device identity,
 * cloud-sync toggle, the SyncState repository, and the authenticated /v1 client. Lives in src/cloud so
 * none of it (and no androidx.work/Retrofit/OkHttp) is linked into the LOCAL build.
 */
@Module
@InstallIn(SingletonComponent::class)
object CloudModule {

    // Configurable per deployment; placeholder until a backend host is provisioned.
    private const val BASE_URL = "https://api.traxintel.example/"

    @Provides
    @Singleton
    fun provideDeviceTokenDataSource(
        @ApplicationContext context: Context,
        @Dispatcher(HiltCoroutineDispatchers.IO) dispatcher: CoroutineDispatcher,
    ): DeviceTokenDataSource = DeviceTokenDataSource(context, dispatcher)

    @Provides
    @Singleton
    fun provideDeviceIdentityDataSource(
        @ApplicationContext context: Context,
        @Dispatcher(HiltCoroutineDispatchers.IO) dispatcher: CoroutineDispatcher,
    ): DeviceIdentityDataSource = DeviceIdentityDataSource(context, dispatcher)

    @Provides
    @Singleton
    fun provideCloudSyncSettings(
        @ApplicationContext context: Context,
        @Dispatcher(HiltCoroutineDispatchers.IO) dispatcher: CoroutineDispatcher,
    ): CloudSyncSettingsDataSource = CloudSyncSettingsDataSource(context, dispatcher)

    @Provides
    @Singleton
    fun provideObservationSyncRepository(observationDao: ObservationDao): ObservationSyncRepository =
        ObservationSyncRepository(observationDao)

    @Provides
    @Singleton
    fun provideTraxIntelApiService(tokenStore: DeviceTokenDataSource): TraxIntelApiService =
        TraxIntelApiFactory.createAuthenticated(
            baseUrl = BASE_URL,
            deviceTokenProvider = { tokenStore.cachedToken()?.deviceToken },
            appVersion = BuildConfig.VERSION_NAME,
            dbVersion = DATABASE_VERSION,
        )
}
