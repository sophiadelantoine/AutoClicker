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
}

android {
    namespace = "com.buzbuz.smartautoclicker.core.capture"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    // base (Identifier) and detection-models (OCRAlphabet) are implementation deps of domain and
    // thus not exposed transitively; the adapter references them directly.
    implementation(project(":core:common:base"))
    implementation(project(":core:observation"))
    implementation(project(":core:smart:database"))
    implementation(project(":core:smart:detection-models"))
    implementation(project(":core:smart:domain"))
    implementation(project(":core:smart:processing"))

    testImplementation(libs.androidx.room.ktx)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
