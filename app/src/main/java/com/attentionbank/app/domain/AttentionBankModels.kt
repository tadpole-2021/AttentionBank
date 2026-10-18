package com.attentionbank.app.domain

import androidx.compose.runtime.Immutable
import kotlin.math.floor

enum class AppRole {
    NORMAL,
    COLLECT,
    ENTERTAINMENT,
}

enum class Multiplier(val value: Double, val label: String) {
    RELAXED(0.5, "1:0.5"),
    STANDARD(1.0, "1:1"),
    STRICT(2.0, "1:2"),
}

@Immutable
data class InstalledApp(
    val packageName: String,
    val appName: String,
    val role: AppRole = AppRole.NORMAL,
    val multiplier: Multiplier = Multiplier.STANDARD,
    val isSystemApp: Boolean = false,
)

@Immutable
data class BalanceSnapshot(
    val todayBalanceSeconds: Int = 600,
    val carryOverBalanceSeconds: Int = 0,
    val todayEarnedSeconds: Int = 0,
    val todaySpentSeconds: Int = 0,
    val spendRemainder: Double = 0.0,
    val activePackageName: String? = null,
    val lastLedgerUpdateMillis: Long = 0L,
    val settlementToken: Long = 0L,
) {
    val totalBalanceSeconds: Int = todayBalanceSeconds + carryOverBalanceSeconds
}

@Immutable
data class HomeUiState(
    val balance: BalanceSnapshot = BalanceSnapshot(),
    val installedAppCount: Int = 0,
    val collectAppCount: Int = 0,
    val entertainmentAppCount: Int = 0,
    val isUsageAccessGranted: Boolean = false,
    val isAccessibilityGranted: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = false,
    val serviceRunning: Boolean = false,
) {
    val showZeroBalanceWarning: Boolean = balance.totalBalanceSeconds == 0
}

data class TickResult(
    val updated: BalanceSnapshot,
    val shouldBlock: Boolean,
)

fun formatDuration(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

fun applySpend(balance: BalanceSnapshot, rawSpendSeconds: Double): BalanceSnapshot {
    val totalSpend = balance.spendRemainder + rawSpendSeconds
    val wholeSeconds = floor(totalSpend).toInt()
    val remainder = totalSpend - wholeSeconds
    if (wholeSeconds == 0) {
        return balance.copy(spendRemainder = remainder)
    }

    val spendFromCarry = minOf(balance.carryOverBalanceSeconds, wholeSeconds)
    val spendFromToday = minOf(balance.todayBalanceSeconds, wholeSeconds - spendFromCarry)
    return balance.copy(
        carryOverBalanceSeconds = balance.carryOverBalanceSeconds - spendFromCarry,
        todayBalanceSeconds = balance.todayBalanceSeconds - spendFromToday,
        todaySpentSeconds = balance.todaySpentSeconds + wholeSeconds,
        spendRemainder = remainder,
    )
}
