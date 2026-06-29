/*
 * Copyright (C) 2025 Kevin Buzeau
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
package com.buzbuz.gradle.convention.model

/** Gradle build types for Klick'r */
enum class KlickrBuildType(val buildTypeName: String) {
    DEBUG("debug"),
    RELEASE("release");
}

/** Gradle flavour dimensions. VERSION stays first so it leads the variant name (e.g. fDroidLocalDebug). */
enum class KlickrDimension(val flavourDimensionName: String) {
    VERSION("version"),
    CONNECTIVITY("connectivity");
}

/** Gradle flavours for Klick'r. */
enum class KlickrFlavour(val flavourName: String, val dimension: KlickrDimension) {
    /** Gradle flavour for FOSS fDroid Klick'r. */
    F_DROID("fDroid", KlickrDimension.VERSION),
    /** Gradle flavour for PlayStore Klick'r (with in app purchase, ads and crashlytics). */
    PLAY_STORE("playStore", KlickrDimension.VERSION),

    /** Local-only build: no networking, GPLv3-clean. Declared first in its dimension so it is the default. */
    LOCAL("local", KlickrDimension.CONNECTIVITY),
    /** Cloud-connected build: networking + observation sync enabled. */
    CLOUD("cloud", KlickrDimension.CONNECTIVITY);
}
