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
package com.buzbuz.smartautoclicker.core.scheduling.command

import com.buzbuz.smartautoclicker.core.network.command.CommandOutcome

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidTrackingControllerTest {

    private val gateway = mockk<LocalTrackingGateway>(relaxed = true)
    private val controller = AndroidTrackingController(gateway)

    @Test
    fun requestStart_liveProjection_armsScenarioAndApplies() = runTest {
        every { gateway.isTrackingScenario("s1") } returns false
        every { gateway.isProjectionLive() } returns true

        assertEquals(CommandOutcome.Applied, controller.requestStart("s1"))

        coVerify { gateway.armScenario("s1") }
        verify(exactly = 0) { gateway.notifyConsentRequired(any()) }
    }

    @Test
    fun requestStart_noProjection_postsConsentAndPends() = runTest {
        every { gateway.isTrackingScenario("s1") } returns false
        every { gateway.isProjectionLive() } returns false

        assertEquals(CommandOutcome.PendingConsent, controller.requestStart("s1"))

        verify { gateway.notifyConsentRequired("s1") }
        coVerify(exactly = 0) { gateway.armScenario(any()) } // no background projection start
    }

    @Test
    fun requestStart_alreadyTrackingSameScenario_isNoOp() = runTest {
        every { gateway.isTrackingScenario("s1") } returns true

        assertEquals(CommandOutcome.NoOp, controller.requestStart("s1"))

        coVerify(exactly = 0) { gateway.armScenario(any()) } // no second start issued
    }

    @Test
    fun stop_whenServiceStarted_tearsDownAndApplies() = runTest {
        every { gateway.isServiceStarted() } returns true

        assertEquals(CommandOutcome.Applied, controller.stop("s1"))

        verify { gateway.stop() }
    }

    @Test
    fun stop_whenNotStarted_isNoOp() = runTest {
        every { gateway.isServiceStarted() } returns false

        assertEquals(CommandOutcome.NoOp, controller.stop(null))

        verify(exactly = 0) { gateway.stop() }
    }
}
