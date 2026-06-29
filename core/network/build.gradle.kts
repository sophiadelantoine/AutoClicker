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

plugins {
    alias(libs.plugins.buzbuz.androidLibrary)
    alias(libs.plugins.buzbuz.androidUnitTest)
    alias(libs.plugins.buzbuz.flavour)
    alias(libs.plugins.buzbuz.kotlinSerialization)
}

android {
    namespace = "com.buzbuz.smartautoclicker.core.network"
}

// Cloud-only HTTP boundary to the (non-GPL) TraxIntel backend. Owns its own @Serializable wire DTOs;
// it MUST NOT import GPL domain entities (Observation/Scenario/Condition) — the /v1 contract is the seam.
dependencies {
    implementation(libs.square.retrofit)
    implementation(libs.square.okhttp)
    implementation(libs.square.retrofit.converter.kotlinxSerialization)
    implementation(libs.kotlinx.serialization.json)
}
