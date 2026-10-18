package com.attentionbank.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockPagePolicyTest {
    @Test
    fun firstZeroBalanceCheckLaunchesPage() {
        assertEquals(BlockPageAction.LAUNCH, decide())
    }

    @Test
    fun repeatedChecksDoNotRelaunchWhileTransitioning() {
        for (elapsed in listOf(0L, 500L, 1000L, 1499L)) {
            assertEquals(BlockPageAction.WAIT, decide(pending = "video", elapsed = elapsed))
        }
    }

    @Test
    fun silentLaunchDenialFallsBackOnce() {
        assertEquals(BlockPageAction.HOME, decide(pending = "video", elapsed = 1500))
        assertEquals(BlockPageAction.WAIT, decide(pending = "video", elapsed = 3000, fallback = true))
    }

    @Test
    fun fallbackNoticeIsNotRequestedAgainBySubsequentTicks() {
        for (elapsed in 2000L..10000L step 500L) {
            assertEquals(
                BlockPageAction.WAIT,
                decide(pending = "video", elapsed = elapsed, fallback = true),
            )
        }
    }

    @Test
    fun arrivingAtBankOrHomeClearsPendingLaunch() {
        for (destination in listOf("bank", "launcher", "reader", "settings", "phone")) {
            assertEquals(
                BlockPageAction.RESET,
                decide(current = destination, checked = destination, block = false, pending = "video"),
            )
        }
    }

    @Test
    fun zeroBalanceReentryLaunchesAgain() {
        assertEquals(BlockPageAction.RESET, decide(block = false, pending = "video"))
        assertEquals(BlockPageAction.LAUNCH, decide())
    }

    @Test
    fun anotherRestrictedAppGetsItsOwnLaunch() {
        assertEquals(BlockPageAction.LAUNCH, decide(pending = "otherVideo", fallback = true))
    }

    @Test
    fun oldQueryCannotInterruptNewApp() {
        assertEquals(BlockPageAction.WAIT, decide(current = "reader", checked = "video"))
    }

    @Test
    fun unknownWindowDoesNotTriggerLaunchOrHome() {
        assertEquals(BlockPageAction.WAIT, decide(current = null, checked = null, elapsed = 5000))
    }

    @Test
    fun lockScreenClearsPendingLaunch() {
        assertEquals(
            BlockPageAction.RESET,
            blockPageAction(false, null, null, true, "video", 2000, false),
        )
    }

    @Test
    fun restoredBalanceDoesNotFallBackToHome() {
        assertEquals(BlockPageAction.RESET, decide(block = false, pending = "video", elapsed = 3000))
    }

    private fun decide(
        current: String? = "video",
        checked: String? = "video",
        block: Boolean = true,
        pending: String? = null,
        elapsed: Long = 0,
        fallback: Boolean = false,
    ) = blockPageAction(true, current, checked, block, pending, elapsed, fallback)
}
