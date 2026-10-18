package com.attentionbank.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attentionbank.app.AttentionBankApp
import com.attentionbank.app.MainActivity
import com.attentionbank.app.R
import com.attentionbank.app.data.AttentionDebugProbe
import com.attentionbank.app.data.PREF_DEBUG_LAST_BROADCAST_RECEIVED
import com.attentionbank.app.data.PREF_DEBUG_LAST_BROADCAST_SENT
import com.attentionbank.app.data.PREF_DEBUG_LAST_OVERLAY
import com.attentionbank.app.domain.formatDuration
import com.attentionbank.app.domain.BlockPageAction
import com.attentionbank.app.domain.blockPageAction
import com.attentionbank.app.ui.theme.AttentionBankTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AttentionServiceActions {
    const val START = "com.attentionbank.app.START_ENGINE"
    const val STOP = "com.attentionbank.app.STOP_ENGINE"
    const val CHECK_BLOCK = "com.attentionbank.app.CHECK_BLOCK"
    const val CHANNEL_ID = "attention_bank_engine"
    const val NOTIFICATION_ID = 1001
}

object BalanceExhaustedNotice {
    const val CHANNEL_ID = "attention_bank_balance_alerts"
    private const val NOTIFICATION_ID = 1002

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "余额用完提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "娱乐余额耗尽时说明拦截原因，可点击查看余额页面"
            },
        )
    }

    fun isEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun show(context: Context): String {
        ensureChannel(context)
        if (!isEnabled(context)) return "notification disabled"
        val page = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, ZeroBalanceBlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("娱乐余额已用完")
            .setContentText("娱乐应用已受限，点击查看余额并去赚取时间。")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "注意力银行已限制娱乐应用。请先使用收集时间 App 赚取余额，点击查看详情。",
            ))
            .setContentIntent(page)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        return try {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            "notification posted"
        } catch (error: SecurityException) {
            "notification denied: ${error.javaClass.simpleName}"
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

class AttentionBankForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    @Volatile private var lastNotifiedBalance: Int? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // #region debug-point D:service-command
        AttentionDebugProbe.report(
            context = this,
            hypothesisId = "D",
            location = "AttentionBankForegroundService.onStartCommand",
            msg = "foreground service command received",
            data = mapOf("action" to intent?.action),
        )
        // #endregion
        when (intent?.action) {
            AttentionServiceActions.STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                startForeground(
                    AttentionServiceActions.NOTIFICATION_ID,
                    buildNotification(lastNotifiedBalance),
                )
                startTicker()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        (application as AttentionBankApp).repository.markServiceRunning(false)
        tickerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTicker() {
        if (tickerJob?.isActive == true) {
            return
        }
        val repository = (application as AttentionBankApp).repository
        repository.markServiceRunning(true)
        tickerJob = serviceScope.launch {
            repository.initialize()
            while (isActive) {
                val result = repository.tick()
                updateBalanceNotification(result.updated.totalBalanceSeconds)
                if (result.updated.totalBalanceSeconds > 0) BalanceExhaustedNotice.cancel(this@AttentionBankForegroundService)
                // #region debug-point D:ticker-result
                AttentionDebugProbe.report(
                    context = this@AttentionBankForegroundService,
                    hypothesisId = "D",
                    location = "AttentionBankForegroundService.startTicker",
                    msg = "ticker produced result",
                    data = mapOf(
                        "balanceAfter" to result.updated.totalBalanceSeconds,
                        "shouldBlock" to result.shouldBlock,
                        "activePackage" to result.updated.activePackageName,
                    ),
                )
                // #endregion
                // Only request a fresh check; a stale tick must not launch a block page.
                sendBroadcast(Intent(AttentionServiceActions.CHECK_BLOCK).setPackage(packageName))
                AttentionDebugProbe.saveSummary(
                    context = this@AttentionBankForegroundService,
                    key = PREF_DEBUG_LAST_BROADCAST_SENT,
                    value = "CHECK_BLOCK, pkg=${result.updated.activePackageName}, block=${result.shouldBlock}",
                )
                delay(1000)
            }
        }
    }

    private fun updateBalanceNotification(balanceSeconds: Int) {
        if (balanceSeconds == lastNotifiedBalance) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        try {
            getSystemService(NotificationManager::class.java).notify(
                AttentionServiceActions.NOTIFICATION_ID,
                buildNotification(balanceSeconds),
            )
            lastNotifiedBalance = balanceSeconds
        } catch (_: SecurityException) {
            // Notification permission may be revoked mid-update; accounting must continue.
        }
    }

    private fun buildNotification(balanceSeconds: Int?): Notification {
        val balanceText = balanceSeconds?.let { "当前余额 ${formatDuration(it)}" }
            ?: "当前余额读取中…"
        val activityIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, AttentionServiceActions.CHANNEL_ID)
            .setContentTitle("注意力银行正在守护你的额度")
            .setContentText(balanceText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(balanceText))
            .setSmallIcon(R.drawable.ic_stat_name)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(activityIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            AttentionServiceActions.CHANNEL_ID,
            "注意力银行常驻服务",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }
}

class AttentionAccessibilityService : AccessibilityService() {
    private var receiver: BroadcastReceiver? = null
    private val enforcementScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null
    private var checkJob: Job? = null
    private var lastRecordedPackage: String? = null
    private var pendingPackage: String? = null
    private var launchRequestedAt = 0L
    private var fallbackSent = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        // #region debug-point E:accessibility-connected
        AttentionDebugProbe.report(
            context = this,
            hypothesisId = "E",
            location = "AttentionAccessibilityService.onServiceConnected",
            msg = "accessibility service connected",
        )
        // #endregion
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        monitorJob?.cancel()
        monitorJob = enforcementScope.launch {
            while (isActive) {
                requestEnforcementCheck()
                delay(500)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            requestEnforcementCheck()
        }
    }

    private fun isScreenAvailable(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    @Suppress("DEPRECATION")
    private fun foregroundApplication(): String? {
        // Read only the package of the top application window, never its text or children.
        // Accessibility overlays, keyboards and screenshot/system windows are not apps.
        for (window in windows.sortedByDescending { it.layer }) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            try {
                root.packageName?.toString()?.let { return it }
            } finally {
                root.recycle()
            }
        }
        return null
    }

    private fun requestEnforcementCheck() {
        if (checkJob?.isActive == true) return
        checkJob = enforcementScope.launch {
            try {
                if (!isScreenAvailable()) {
                    clearPendingLaunch()
                    return@launch
                }
                val currentPackage = foregroundApplication() ?: return@launch
                val repository = (application as AttentionBankApp).repository
                val shouldBlock = withContext(Dispatchers.IO) {
                    if (lastRecordedPackage != currentPackage) {
                        repository.recordForegroundPackage(currentPackage)
                    }
                    repository.shouldBlockPackageNow(currentPackage)
                }
                // The user may have switched apps while the database query was suspended.
                val stillCurrent = currentPackage == foregroundApplication()
                val action = blockPageAction(
                    screenAvailable = isScreenAvailable(),
                    currentPackage = currentPackage,
                    checkedPackage = if (stillCurrent) currentPackage else null,
                    shouldBlock = shouldBlock,
                    pendingPackage = pendingPackage,
                    elapsedMillis = SystemClock.elapsedRealtime() - launchRequestedAt,
                    fallbackSent = fallbackSent,
                )
                if (stillCurrent) {
                    lastRecordedPackage = currentPackage
                }
                when (action) {
                    BlockPageAction.LAUNCH -> openBlockPage(currentPackage)
                    BlockPageAction.HOME -> fallbackToHome()
                    BlockPageAction.RESET -> clearPendingLaunch()
                    BlockPageAction.WAIT -> Unit
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                recordOverlayState("check failed: ${error.javaClass.simpleName}")
            }
        }
    }

    override fun onInterrupt() {
        clearPendingLaunch()
    }

    override fun onCreate() {
        super.onCreate()
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // #region debug-point E:broadcast-received
                AttentionDebugProbe.report(
                    context = this@AttentionAccessibilityService,
                    hypothesisId = "E",
                    location = "AttentionAccessibilityService.onReceive",
                    msg = "accessibility broadcast received",
                    data = mapOf("action" to intent?.action),
                )
                AttentionDebugProbe.saveSummary(
                    context = this@AttentionAccessibilityService,
                    key = PREF_DEBUG_LAST_BROADCAST_RECEIVED,
                    value = "received ${intent?.action}",
                )
                // #endregion
                if (intent?.action == AttentionServiceActions.CHECK_BLOCK) {
                    requestEnforcementCheck()
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(AttentionServiceActions.CHECK_BLOCK)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        enforcementScope.cancel()
        clearPendingLaunch()
        receiver?.let { unregisterReceiver(it) }
        receiver = null
        super.onDestroy()
    }

    private fun openBlockPage(blockedPackage: String) {
        pendingPackage = blockedPackage
        launchRequestedAt = SystemClock.elapsedRealtime()
        fallbackSent = false
        recordOverlayState("page requested: $blockedPackage")
        AttentionDebugProbe.saveSummary(
            this,
            com.attentionbank.app.data.PREF_DEBUG_LAST_NOTICE,
            BalanceExhaustedNotice.show(this),
        )
        try {
            // The system-bound accessibility service starts our own opaque Activity.
            // Unlike an overlay, this takes foreground activity ownership from the target.
            startActivity(Intent(this, ZeroBalanceBlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
        } catch (error: RuntimeException) {
            recordOverlayState("page rejected: ${error.javaClass.simpleName}")
            fallbackToHome()
        }
    }

    private fun clearPendingLaunch() {
        pendingPackage = null
        fallbackSent = false
    }

    private fun fallbackToHome() {
        if (fallbackSent) return
        fallbackSent = true
        val accepted = performGlobalAction(GLOBAL_ACTION_HOME)
        recordOverlayState("page launch failed; home fallback: $accepted")
        // A system-rendered text toast does not need POST_NOTIFICATIONS permission.
        Toast.makeText(this, "娱乐余额已用完，请先赚取时间", Toast.LENGTH_LONG).show()
        val noticeResult = BalanceExhaustedNotice.show(this)
        AttentionDebugProbe.saveSummary(
            this,
            com.attentionbank.app.data.PREF_DEBUG_LAST_NOTICE,
            "toast requested; $noticeResult",
        )
    }

    private fun recordOverlayState(value: String) {
        AttentionDebugProbe.saveSummary(this, PREF_DEBUG_LAST_OVERLAY, value)
    }
}

class AttentionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val repository = (context.applicationContext as AttentionBankApp).repository
            if (repository.hasUsageStatsAccess() && repository.isAccessibilityServiceEnabled()) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AttentionBankForegroundService::class.java).apply {
                        action = AttentionServiceActions.START
                    },
                )
            }
        }
    }
}

class ZeroBalanceBlockActivity : ComponentActivity() {
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val audioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
    }
    private var hasAudioFocus = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        setContent {
            BackHandler { returnToDesktop() }
            val repository = (application as AttentionBankApp).repository
            val state by repository.observeHomeUiState().collectAsStateWithLifecycle(
                initialValue = com.attentionbank.app.domain.HomeUiState(
                    balance = com.attentionbank.app.domain.BalanceSnapshot(todayBalanceSeconds = 0),
                ),
            )
            LaunchedEffect(Unit) { repository.settleIfNeeded() }
            AttentionBankTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = if (state.balance.totalBalanceSeconds == 0) "余额已用完" else "余额已恢复",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "当前余额 ${formatDuration(state.balance.totalBalanceSeconds)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "去打开一个收集时间 App，再把娱乐时间赚回来。",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                startActivity(Intent(this@ZeroBalanceBlockActivity, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                })
                                finishAndRemoveTask()
                            },
                        ) {
                            Text("去首页查看")
                        }
                        Button(onClick = { returnToDesktop() }) {
                            Text("返回桌面")
                        }
                    }
                }
            }
        }
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (!isTopResumedActivity) {
            releaseAudioFocus()
            return
        }
        BalanceExhaustedNotice.cancel(this)
        // Request from the visible Activity (required when targeting Android 15).
        // GAIN sends permanent loss to cooperative players instead of automatic resume.
        val result = audioManager.requestAudioFocus(audioFocusRequest)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        AttentionDebugProbe.saveSummary(
            this, PREF_DEBUG_LAST_OVERLAY, "page resumed; audioFocusGranted=$hasAudioFocus",
        )
    }

    override fun onPause() {
        releaseAudioFocus()
        super.onPause()
    }

    private fun releaseAudioFocus() {
        if (hasAudioFocus) audioManager.abandonAudioFocusRequest(audioFocusRequest)
        hasAudioFocus = false
    }

    private fun returnToDesktop() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finishAndRemoveTask()
    }
}

fun Context.startAttentionEngine() {
    ContextCompat.startForegroundService(
        this,
        Intent(this, AttentionBankForegroundService::class.java).apply {
            action = AttentionServiceActions.START
        },
    )
}

fun Context.stopAttentionEngine() {
    startService(
        Intent(this, AttentionBankForegroundService::class.java).apply {
            action = AttentionServiceActions.STOP
        },
    )
}
