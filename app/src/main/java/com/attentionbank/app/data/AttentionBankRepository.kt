package com.attentionbank.app.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import com.attentionbank.app.domain.AppRole
import com.attentionbank.app.domain.BalanceSnapshot
import com.attentionbank.app.domain.HomeUiState
import com.attentionbank.app.domain.InstalledApp
import com.attentionbank.app.domain.Multiplier
import com.attentionbank.app.domain.TickResult
import com.attentionbank.app.domain.applySpend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val BASE_DAILY_BALANCE_SECONDS = 600
private const val RESET_HOUR = 1
private const val PREFS_NAME = "attention_bank_prefs"
private const val PREF_FOREGROUND_PACKAGE = "foreground_package"
private const val PREF_SERVICE_RUNNING = "service_running"
private const val PREF_DEBUG_EVENTS = "debug_recent_events"
private const val PREF_DEBUG_UPDATED_AT = "debug_updated_at"
const val PREF_DEBUG_LAST_FOREGROUND = "debug_last_foreground"
const val PREF_DEBUG_LAST_SPEND = "debug_last_spend"
const val PREF_DEBUG_LAST_BLOCK = "debug_last_block"
const val PREF_DEBUG_LAST_BROADCAST_SENT = "debug_last_broadcast_sent"
const val PREF_DEBUG_LAST_BROADCAST_RECEIVED = "debug_last_broadcast_received"
const val PREF_DEBUG_LAST_OVERLAY = "debug_last_overlay"
const val PREF_DEBUG_LAST_NOTICE = "debug_last_notice"

object AttentionDebugProbe {
    @Synchronized
    fun report(
        context: Context,
        hypothesisId: String,
        location: String,
        msg: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
        val line = buildString {
            append(time)
            append(" | ")
            append(hypothesisId)
            append(" | ")
            append(location)
            append(" | ")
            append(msg)
            if (data.isNotEmpty()) {
                append(" | ")
                append(
                    data.entries.joinToString(", ") { (key, value) ->
                        "$key=${value ?: "null"}"
                    },
                )
            }
        }
        val existing = prefs.getString(PREF_DEBUG_EVENTS, "").orEmpty()
        val updated = (listOf(line) + existing.lines().filter { it.isNotBlank() })
            .take(8)
            .joinToString("\n")
        prefs.edit {
            putString(PREF_DEBUG_EVENTS, updated)
            putLong(PREF_DEBUG_UPDATED_AT, now)
        }
    }

    fun saveSummary(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(key, value)
            putLong(PREF_DEBUG_UPDATED_AT, System.currentTimeMillis())
        }
    }
}

class AttentionBankRepository(
    private val context: Context,
    private val database: AttentionBankDatabase,
) {
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val installedAppsFlow = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val serviceRunningFlow = MutableStateFlow(isServiceRunning())

    fun observeHomeUiState(): Flow<HomeUiState> {
        return combine(
            database.balanceStateDao().observe().map { it?.toDomain() ?: defaultSnapshot() },
            database.appSelectionDao().observeAll(),
            serviceRunningFlow,
        ) { balance, selections, serviceRunning ->
            HomeUiState(
                balance = balance,
                installedAppCount = installedAppsFlow.value.size,
                collectAppCount = selections.count { it.role == AppRole.COLLECT.name },
                entertainmentAppCount = selections.count { it.role == AppRole.ENTERTAINMENT.name },
                isUsageAccessGranted = hasUsageStatsAccess(),
                isAccessibilityGranted = isAccessibilityServiceEnabled(),
                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations(),
                serviceRunning = serviceRunning,
            )
        }
    }

    fun observeConfiguredApps(): Flow<List<InstalledApp>> {
        return combine(
            installedAppsFlow,
            database.appSelectionDao().observeAll(),
        ) { installedApps, selections ->
            val selectionMap = selections.associateBy { it.packageName }
            installedApps.map { app ->
                selectionMap[app.packageName]?.let { entity ->
                    app.copy(
                        role = AppRole.valueOf(entity.role),
                        multiplier = Multiplier.valueOf(entity.multiplier),
                    )
                } ?: app
            }
        }
    }

    suspend fun initialize() {
        refreshInstalledApps()
        ensureBalanceState()
    }

    suspend fun refreshInstalledApps() {
        installedAppsFlow.value = loadInstalledApps()
    }

    suspend fun assignRole(app: InstalledApp, role: AppRole) {
        if (isProtectedPackage(app.packageName) && role == AppRole.ENTERTAINMENT) {
            return
        }
        if (role == AppRole.NORMAL) {
            database.appSelectionDao().delete(app.packageName)
        } else {
            database.appSelectionDao().upsert(
                AppSelectionEntity(
                    packageName = app.packageName,
                    appName = app.appName,
                    role = role.name,
                    multiplier = app.multiplier.name,
                    isSystemApp = app.isSystemApp,
                ),
            )
        }
    }

    suspend fun updateMultiplier(packageName: String, multiplier: Multiplier) {
        val existing = database.appSelectionDao().getAll().firstOrNull { it.packageName == packageName } ?: return
        database.appSelectionDao().upsert(existing.copy(multiplier = multiplier.name))
    }

    suspend fun recordForegroundPackage(packageName: String?) {
        prefs.edit {
            putString(PREF_FOREGROUND_PACKAGE, packageName)
        }
        // #region debug-point A:foreground-event
        AttentionDebugProbe.report(
            context = context,
            hypothesisId = "A",
            location = "AttentionBankRepository.recordForegroundPackage",
            msg = "foreground package recorded",
            data = mapOf("packageName" to packageName),
        )
        AttentionDebugProbe.saveSummary(
            context = context,
            key = PREF_DEBUG_LAST_FOREGROUND,
            value = packageName ?: "null",
        )
        // #endregion
    }

    fun getForegroundPackage(): String? = prefs.getString(PREF_FOREGROUND_PACKAGE, null)

    fun markServiceRunning(running: Boolean) {
        prefs.edit {
            putBoolean(PREF_SERVICE_RUNNING, running)
        }
        serviceRunningFlow.value = running
        // #region debug-point D:service-state
        AttentionDebugProbe.report(
            context = context,
            hypothesisId = "D",
            location = "AttentionBankRepository.markServiceRunning",
            msg = "service running state updated",
            data = mapOf("running" to running),
        )
        // #endregion
    }

    fun readDebugEvents(): String = prefs.getString(PREF_DEBUG_EVENTS, "").orEmpty()

    fun readDebugSummary(): String = listOfNotNull(
        prefs.getString(PREF_DEBUG_LAST_FOREGROUND, null)?.let { "最近前台：$it" },
        prefs.getString(PREF_DEBUG_LAST_SPEND, null)?.let { "最近扣减：$it" },
        prefs.getString(PREF_DEBUG_LAST_BLOCK, null)?.let { "最近拦截判定：$it" },
        prefs.getString(PREF_DEBUG_LAST_BROADCAST_SENT, null)?.let { "最近发送广播：$it" },
        prefs.getString(PREF_DEBUG_LAST_BROADCAST_RECEIVED, null)?.let { "最近收到广播：$it" },
        prefs.getString(PREF_DEBUG_LAST_OVERLAY, null)?.let { "最近拦截页面：$it" },
        prefs.getString(PREF_DEBUG_LAST_NOTICE, null)?.let { "最近余额提醒：$it" },
    ).joinToString("\n")

    fun refreshRuntimeState() {
        serviceRunningFlow.value = isServiceRunning()
    }

    suspend fun shouldBlockPackageNow(
        packageName: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean = mutex.withLock {
        ensureBalanceState(nowMillis)
        val selection = database.appSelectionDao().getAll().firstOrNull { it.packageName == packageName }
        val balance = database.balanceStateDao().get()?.toDomain() ?: defaultSnapshot()
        val shouldBlock = selection?.role == AppRole.ENTERTAINMENT.name &&
            balance.totalBalanceSeconds == 0 &&
            !isProtectedPackage(packageName)

        // #region debug-point C:direct-block-check
        AttentionDebugProbe.report(
            context = context,
            hypothesisId = "C",
            location = "AttentionBankRepository.shouldBlockPackageNow",
            msg = "direct block check",
            data = mapOf(
                "packageName" to packageName,
                "role" to selection?.role,
                "balance" to balance.totalBalanceSeconds,
                "shouldBlock" to shouldBlock,
            ),
        )
        // #endregion
        shouldBlock
    }

    suspend fun tick(nowMillis: Long = System.currentTimeMillis()): TickResult = mutex.withLock {
        ensureBalanceState(nowMillis)
        val balanceDao = database.balanceStateDao()
        val appMap = database.appSelectionDao().getAll().associateBy { it.packageName }
        val currentPackage = resolveCurrentPackage(nowMillis)
        val currentState = balanceDao.get()?.toDomain() ?: defaultSnapshot()
        val elapsedSeconds = ((nowMillis - currentState.lastLedgerUpdateMillis) / 1000L)
            .coerceIn(0L, 30L)
            .toInt()

        var updated = currentState.copy(
            activePackageName = currentPackage,
            lastLedgerUpdateMillis = nowMillis,
        )
        var shouldBlock = false

        // #region debug-point A:tick-entry
        AttentionDebugProbe.report(
            context = context,
            hypothesisId = "A",
            location = "AttentionBankRepository.tick",
            msg = "tick entry",
            data = mapOf(
                "elapsedSeconds" to elapsedSeconds,
                "currentPackage" to currentPackage,
                "balanceBefore" to currentState.totalBalanceSeconds,
                "serviceRunning" to isServiceRunning(),
            ),
        )
        // #endregion

        if (elapsedSeconds > 0 && currentPackage != null) {
            val selection = appMap[currentPackage]
            when (selection?.role) {
                AppRole.COLLECT.name -> {
                    updated = updated.copy(
                        todayBalanceSeconds = updated.todayBalanceSeconds + elapsedSeconds,
                        todayEarnedSeconds = updated.todayEarnedSeconds + elapsedSeconds,
                    )
                    database.usageLedgerDao().insert(
                        UsageLedgerEntity(
                            packageName = currentPackage,
                            eventType = "earn",
                            balanceDeltaSeconds = elapsedSeconds,
                            actualUsageSeconds = elapsedSeconds,
                            happenedAtMillis = nowMillis,
                        ),
                    )
                }

                AppRole.ENTERTAINMENT.name -> {
                    val multiplier = Multiplier.valueOf(selection.multiplier)
                    val spentBefore = updated.totalBalanceSeconds
                    updated = applySpend(updated, elapsedSeconds * multiplier.value)
                    val spentSeconds = spentBefore - updated.totalBalanceSeconds
                    if (spentSeconds > 0) {
                        database.usageLedgerDao().insert(
                            UsageLedgerEntity(
                                packageName = currentPackage,
                                eventType = "spend",
                                balanceDeltaSeconds = -spentSeconds,
                                actualUsageSeconds = elapsedSeconds,
                                happenedAtMillis = nowMillis,
                            ),
                        )
                    }
                    shouldBlock = updated.totalBalanceSeconds == 0 && !isProtectedPackage(currentPackage)
                    // #region debug-point B:spend-branch
                    AttentionDebugProbe.report(
                        context = context,
                        hypothesisId = "B",
                        location = "AttentionBankRepository.tick",
                        msg = "entertainment spend branch",
                        data = mapOf(
                            "packageName" to currentPackage,
                            "multiplier" to selection.multiplier,
                            "spentBefore" to spentBefore,
                            "spentSeconds" to spentSeconds,
                            "balanceAfter" to updated.totalBalanceSeconds,
                            "shouldBlock" to shouldBlock,
                        ),
                    )
                    AttentionDebugProbe.saveSummary(
                        context = context,
                        key = PREF_DEBUG_LAST_SPEND,
                        value = "pkg=$currentPackage, before=$spentBefore, spent=$spentSeconds, after=${updated.totalBalanceSeconds}, block=$shouldBlock",
                    )
                    // #endregion
                }
            }
        } else {
            // #region debug-point A:no-active-package
            AttentionDebugProbe.report(
                context = context,
                hypothesisId = "A",
                location = "AttentionBankRepository.tick",
                msg = "tick skipped",
                data = mapOf(
                    "elapsedSeconds" to elapsedSeconds,
                    "currentPackage" to currentPackage,
                ),
            )
            // #endregion
        }

        balanceDao.upsert(updated.toEntity())
        // #region debug-point C:tick-exit
        AttentionDebugProbe.report(
            context = context,
            hypothesisId = "C",
            location = "AttentionBankRepository.tick",
            msg = "tick exit",
            data = mapOf(
                "activePackage" to updated.activePackageName,
                "balanceAfter" to updated.totalBalanceSeconds,
                "shouldBlock" to shouldBlock,
            ),
        )
        AttentionDebugProbe.saveSummary(
            context = context,
            key = PREF_DEBUG_LAST_BLOCK,
            value = "pkg=${updated.activePackageName}, after=${updated.totalBalanceSeconds}, block=$shouldBlock",
        )
        // #endregion
        TickResult(updated = updated, shouldBlock = shouldBlock)
    }

    suspend fun settleIfNeeded(nowMillis: Long = System.currentTimeMillis()) {
        mutex.withLock {
            ensureBalanceState(nowMillis)
        }
    }

    fun hasUsageStatsAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "${context.packageName}/com.attentionbank.app.service.AttentionAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    fun accessibilityIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    fun batteryOptimizationIntent(): Intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = android.net.Uri.parse("package:${context.packageName}")
    }

    fun canAutoStartEngine(): Boolean = hasUsageStatsAccess()

    private suspend fun ensureBalanceState(nowMillis: Long = System.currentTimeMillis()) {
        val balanceDao = database.balanceStateDao()
        var current = balanceDao.get()?.toDomain()
        val currentToken = settlementToken(nowMillis)

        if (current == null) {
            current = defaultSnapshot().copy(
                lastLedgerUpdateMillis = nowMillis,
                settlementToken = currentToken,
            )
            balanceDao.upsert(current.toEntity())
            return
        }

        val existing = current ?: return
        if (existing.settlementToken < currentToken) {
            var migrated = existing
            while (migrated.settlementToken < currentToken) {
                migrated = migrated.copy(
                    carryOverBalanceSeconds = migrated.todayBalanceSeconds,
                    todayBalanceSeconds = BASE_DAILY_BALANCE_SECONDS,
                    todayEarnedSeconds = 0,
                    todaySpentSeconds = 0,
                    spendRemainder = 0.0,
                    activePackageName = null,
                    lastLedgerUpdateMillis = nowMillis,
                    settlementToken = migrated.settlementToken + 1,
                )
            }
            balanceDao.upsert(migrated.toEntity())
        } else if (existing.lastLedgerUpdateMillis == 0L) {
            balanceDao.upsert(existing.copy(lastLedgerUpdateMillis = nowMillis).toEntity())
        }
    }

    private fun loadInstalledApps(): List<InstalledApp> {
        return context.packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { appInfo ->
                context.packageManager.getLaunchIntentForPackage(appInfo.packageName) != null
            }
            .map { appInfo ->
                val appName = context.packageManager.getApplicationLabel(appInfo).toString()
                InstalledApp(
                    packageName = appInfo.packageName,
                    appName = appName,
                    isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .filterNot { isProtectedPackage(it.packageName) && it.packageName == context.packageName }
            .sortedBy { it.appName.lowercase() }
            .toList()
    }

    private suspend fun resolveCurrentPackage(nowMillis: Long): String? {
        val storedPackage = getForegroundPackage()
        val resolvedPackage = resolveCurrentPackageFromUsageStats(nowMillis)
        val currentPackage = resolvedPackage ?: storedPackage
        if (resolvedPackage != null && resolvedPackage != storedPackage) {
            recordForegroundPackage(resolvedPackage)
        }
        return currentPackage
    }

    @Suppress("DEPRECATION")
    private fun resolveCurrentPackageFromUsageStats(nowMillis: Long): String? {
        if (!hasUsageStatsAccess()) {
            return null
        }
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val events = usageStatsManager.queryEvents(nowMillis - 15_000L, nowMillis)
        val event = UsageEvents.Event()
        var latestPackage: String? = null
        var latestTimestamp = Long.MIN_VALUE

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForegroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            if (isForegroundEvent && !event.packageName.isNullOrBlank() && event.timeStamp >= latestTimestamp) {
                latestPackage = event.packageName
                latestTimestamp = event.timeStamp
            }
        }
        return latestPackage
    }

    private fun isServiceRunning(): Boolean = prefs.getBoolean(PREF_SERVICE_RUNNING, false)

    private fun defaultSnapshot(): BalanceSnapshot = BalanceSnapshot(
        todayBalanceSeconds = BASE_DAILY_BALANCE_SECONDS,
    )

    private fun isProtectedPackage(packageName: String): Boolean {
        if (packageName == context.packageName) {
            return true
        }
        return protectedPackagePrefixes.any { prefix -> packageName.startsWith(prefix) }
    }

    private fun settlementToken(nowMillis: Long): Long {
        val zoneId = ZoneId.systemDefault()
        val localDateTime = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDateTime()
        val businessDate = if (localDateTime.hour < RESET_HOUR) {
            localDateTime.toLocalDate().minusDays(1)
        } else {
            localDateTime.toLocalDate()
        }
        return businessDate.toEpochDay()
    }

    private fun BalanceStateEntity.toDomain(): BalanceSnapshot = BalanceSnapshot(
        todayBalanceSeconds = todayBalanceSeconds,
        carryOverBalanceSeconds = carryOverBalanceSeconds,
        todayEarnedSeconds = todayEarnedSeconds,
        todaySpentSeconds = todaySpentSeconds,
        spendRemainder = spendRemainder,
        activePackageName = activePackageName,
        lastLedgerUpdateMillis = lastLedgerUpdateMillis,
        settlementToken = settlementToken,
    )

    private fun BalanceSnapshot.toEntity(): BalanceStateEntity = BalanceStateEntity(
        todayBalanceSeconds = todayBalanceSeconds,
        carryOverBalanceSeconds = carryOverBalanceSeconds,
        todayEarnedSeconds = todayEarnedSeconds,
        todaySpentSeconds = todaySpentSeconds,
        spendRemainder = spendRemainder,
        activePackageName = activePackageName,
        lastLedgerUpdateMillis = lastLedgerUpdateMillis,
        settlementToken = settlementToken,
    )

    companion object {
        private val protectedPackagePrefixes = listOf(
            "com.android.launcher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.android.settings",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.phone",
            "com.google.android.apps.messaging",
            "com.android.mms",
        )
    }
}
