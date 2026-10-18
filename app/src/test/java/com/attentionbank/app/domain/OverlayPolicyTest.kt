package com.attentionbank.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPolicyTest {
    @Test
    fun zeroBalanceShowsOverlay() {
        assertEquals(OverlayAction.SHOW, decide(visible = false, block = true))
    }

    @Test
    fun repeatedWindowAndTickEventsKeepSameOverlay() {
        repeat(100) {
            assertEquals(OverlayAction.KEEP, decide(visible = true, block = true))
        }
    }

    @Test
    fun leavingForHomeOrOrdinaryAppHidesOverlay() {
        for (destination in listOf("launcher", "bank", "reader", "settings", "phone")) {
            assertEquals(
                OverlayAction.HIDE,
                overlayAction(true, true, destination, destination, false),
            )
        }
    }

    @Test
    fun restoredBalanceHidesOverlay() {
        assertEquals(OverlayAction.HIDE, decide(visible = true, block = false))
    }

    @Test
    fun oldBlockingResultCannotCoverNewApp() {
        assertEquals(OverlayAction.KEEP, overlayAction(false, true, "reader", "video", true))
    }

    @Test
    fun oldNonBlockingResultCannotDismissOverlay() {
        assertEquals(OverlayAction.KEEP, overlayAction(true, true, "video", "bank", false))
    }

    @Test
    fun temporaryMissingWindowDoesNotFlicker() {
        assertEquals(OverlayAction.KEEP, overlayAction(true, true, null, null, false))
        assertEquals(OverlayAction.KEEP, overlayAction(false, true, null, null, true))
    }

    @Test
    fun screenOffOrLockedRemovesOverlayEvenWithoutWindow() {
        assertEquals(OverlayAction.HIDE, overlayAction(true, false, null, null, true))
        assertEquals(OverlayAction.KEEP, overlayAction(false, false, "video", "video", true))
    }

    @Test
    fun reopeningRestrictedAppShowsOverlayAgain() {
        assertEquals(OverlayAction.HIDE, decide(visible = true, block = false))
        assertEquals(OverlayAction.SHOW, decide(visible = false, block = true))
    }

    private fun decide(visible: Boolean, block: Boolean) =
        overlayAction(visible, true, "video", "video", block)
}
