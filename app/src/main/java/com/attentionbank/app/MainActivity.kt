@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.attentionbank.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attentionbank.app.domain.AppRole
import com.attentionbank.app.domain.HomeUiState
import com.attentionbank.app.domain.InstalledApp
import com.attentionbank.app.domain.Multiplier
import com.attentionbank.app.domain.formatDuration
import com.attentionbank.app.service.startAttentionEngine
import com.attentionbank.app.service.stopAttentionEngine
import com.attentionbank.app.service.BalanceExhaustedNotice
import com.attentionbank.app.ui.theme.AttentionBankTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var balanceNoticesEnabled by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        balanceNoticesEnabled = BalanceExhaustedNotice.isEnabled(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BalanceExhaustedNotice.ensureChannel(this)
        balanceNoticesEnabled = BalanceExhaustedNotice.isEnabled(this)
        val repository = (application as AttentionBankApp).repository
        setContent {
            AttentionBankTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AttentionBankAppScreen(
                        homeStateFlow = repository.observeHomeUiState(),
                        appsFlow = repository.observeConfiguredApps(),
                        canAutoStartEngine = repository::canAutoStartEngine,
                        onInitialize = { repository.initialize() },
                        onRefreshRuntime = {
                            repository.refreshRuntimeState()
                            repository.refreshInstalledApps()
                            repository.settleIfNeeded()
                        },
                        onAssignRole = repository::assignRole,
                        onUpdateMultiplier = repository::updateMultiplier,
                        onOpenUsageSettings = { startActivity(repository.usageAccessIntent()) },
                        onOpenAccessibilitySettings = { startActivity(repository.accessibilityIntent()) },
                        onOpenBatteryOptimizationSettings = {
                            startActivity(repository.batteryOptimizationIntent())
                        },
                        balanceNoticesEnabled = balanceNoticesEnabled,
                        onEnableBalanceNotices = ::enableBalanceNotices,
                        readDebugEvents = repository::readDebugEvents,
                        readDebugSummary = repository::readDebugSummary,
                        onStartEngine = { startAttentionEngine() },
                        onStopEngine = { stopAttentionEngine() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        balanceNoticesEnabled = BalanceExhaustedNotice.isEnabled(this)
    }

    private fun enableBalanceNotices() {
        val prefs = getSharedPreferences("notification_permissions", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("requested", false)
        ) {
            prefs.edit().putBoolean("requested", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channelDisabled = manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(BalanceExhaustedNotice.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        startActivity(Intent(
            if (channelDisabled) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        ).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            if (channelDisabled) putExtra(Settings.EXTRA_CHANNEL_ID, BalanceExhaustedNotice.CHANNEL_ID)
        })
    }
}

private enum class SelectorMode(
    val title: String,
    val role: AppRole,
) {
    COLLECT("选择收集时间 App", AppRole.COLLECT),
    ENTERTAINMENT("选择娱乐 App", AppRole.ENTERTAINMENT),
}

@Composable
private fun AttentionBankAppScreen(
    homeStateFlow: kotlinx.coroutines.flow.Flow<HomeUiState>,
    appsFlow: kotlinx.coroutines.flow.Flow<List<InstalledApp>>,
    canAutoStartEngine: () -> Boolean,
    onInitialize: suspend () -> Unit,
    onRefreshRuntime: suspend () -> Unit,
    onAssignRole: suspend (InstalledApp, AppRole) -> Unit,
    onUpdateMultiplier: suspend (String, Multiplier) -> Unit,
    onOpenUsageSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenBatteryOptimizationSettings: () -> Unit,
    balanceNoticesEnabled: Boolean,
    onEnableBalanceNotices: () -> Unit,
    readDebugEvents: () -> String,
    readDebugSummary: () -> String,
    onStartEngine: () -> Unit,
    onStopEngine: () -> Unit,
) {
    val homeState by homeStateFlow.collectAsStateWithLifecycle(initialValue = HomeUiState())
    val apps by appsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var selectorMode by remember { mutableStateOf<SelectorMode?>(null) }
    var selectorSearchQuery by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        onInitialize()
    }

    LaunchedEffect(selectorMode) {
        selectorSearchQuery = ""
    }

    LaunchedEffect(homeState.serviceRunning, homeState.isUsageAccessGranted) {
        if (!homeState.serviceRunning && canAutoStartEngine()) {
            onStartEngine()
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    onRefreshRuntime()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val collectApps = remember(apps) { apps.filter { it.role == AppRole.COLLECT } }
    val entertainmentApps = remember(apps) { apps.filter { it.role == AppRole.ENTERTAINMENT } }
    val debugEvents by produceState(initialValue = "") {
        while (true) {
            value = readDebugEvents()
            delay(1000)
        }
    }
    val debugSummary by produceState(initialValue = "") {
        while (true) {
            value = readDebugSummary()
            delay(1000)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        if (selectorMode == null) {
            SinglePageHome(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                state = homeState,
                collectApps = collectApps,
                entertainmentApps = entertainmentApps,
                onOpenCollectSelector = { selectorMode = SelectorMode.COLLECT },
                onOpenEntertainmentSelector = { selectorMode = SelectorMode.ENTERTAINMENT },
                onOpenUsageSettings = onOpenUsageSettings,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenBatteryOptimizationSettings = onOpenBatteryOptimizationSettings,
                balanceNoticesEnabled = balanceNoticesEnabled,
                onEnableBalanceNotices = onEnableBalanceNotices,
                debugSummary = debugSummary,
                debugEvents = debugEvents,
            )
        } else {
            val mode = selectorMode ?: return@Scaffold
            val filteredApps = remember(apps, selectorSearchQuery) {
                val query = selectorSearchQuery.trim().lowercase()
                if (query.isBlank()) {
                    apps
                } else {
                    apps.filter {
                        it.appName.lowercase().contains(query) || it.packageName.lowercase().contains(query)
                    }
                }
            }

            AppSelectorScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                mode = mode,
                apps = filteredApps,
                searchQuery = selectorSearchQuery,
                onSearchQueryChanged = { selectorSearchQuery = it },
                onBack = { selectorMode = null },
                onAssignRole = { app, role ->
                    scope.launch {
                        onAssignRole(app, role)
                    }
                },
                onUpdateMultiplier = { packageName, multiplier ->
                    scope.launch {
                        onUpdateMultiplier(packageName, multiplier)
                    }
                },
            )
        }
    }
}

@Composable
private fun SinglePageHome(
    modifier: Modifier,
    state: HomeUiState,
    collectApps: List<InstalledApp>,
    entertainmentApps: List<InstalledApp>,
    onOpenCollectSelector: () -> Unit,
    onOpenEntertainmentSelector: () -> Unit,
    onOpenUsageSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenBatteryOptimizationSettings: () -> Unit,
    balanceNoticesEnabled: Boolean,
    onEnableBalanceNotices: () -> Unit,
    debugSummary: String,
    debugEvents: String,
) {
    val shouldShowPermissionCard = !state.isUsageAccessGranted ||
        !state.isAccessibilityGranted ||
        !state.isIgnoringBatteryOptimizations ||
        !balanceNoticesEnabled

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("总余额", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = formatDuration(state.balance.totalBalanceSeconds),
                    style = MaterialTheme.typography.displaySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("结转余额：${formatDuration(state.balance.carryOverBalanceSeconds)}")
                Text("今日已赚：${formatDuration(state.balance.todayEarnedSeconds)}")
                Text("今日已花：${formatDuration(state.balance.todaySpentSeconds)}")
            }
        }

        AppSelectionEntryCard(
            title = "收集时间 App",
            subtitle = "点击选择哪些 App 用来赚取娱乐时间",
            apps = collectApps,
            onClick = onOpenCollectSelector,
        )

        AppSelectionEntryCard(
            title = "娱乐 App",
            subtitle = "点击选择哪些 App 需要消耗余额",
            apps = entertainmentApps,
            onClick = onOpenEntertainmentSelector,
        )

        if (state.showZeroBalanceWarning) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("当前余额为 0", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("打开娱乐 App 时会立即触发拦截，请先使用收集时间 App 赚取余额。")
                }
            }
        }

        if (shouldShowPermissionCard) {
            PermissionCard(
                title = "权限与保活",
                description = "使用前请完成识别与保活授权。开启余额提醒后，即使系统阻止弹出页面，也可通过通知了解拦截原因；允许横幅通知可让提醒更醒目。",
                items = listOf(
                    PermissionItem("使用情况访问", state.isUsageAccessGranted, onOpenUsageSettings),
                    PermissionItem("无障碍服务", state.isAccessibilityGranted, onOpenAccessibilitySettings),
                    PermissionItem("忽略电池优化", state.isIgnoringBatteryOptimizations, onOpenBatteryOptimizationSettings),
                    PermissionItem("余额提醒通知", balanceNoticesEnabled, onEnableBalanceNotices),
                ),
            )
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("调试面板", style = MaterialTheme.typography.titleMedium)
                Text(
                    "复现余额耗尽问题时看这里，截图发我即可。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = if (debugSummary.isBlank()) "关键摘要：暂时还没有。" else debugSummary,
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                Text(
                    text = if (debugEvents.isBlank()) "暂时还没有调试事件，先打开一个娱乐 App 复现一次。" else debugEvents,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AppSelectionEntryCard(
    title: String,
    subtitle: String,
    apps: List<InstalledApp>,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            if (apps.isEmpty()) {
                Text("还没有选择任何 App", style = MaterialTheme.typography.bodyMedium)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    apps.forEach { app ->
                        SelectedAppChip(app = app)
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedAppChip(app: InstalledApp) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppIcon(packageName = app.packageName, appName = app.appName, size = 18.dp)
            Text(
                text = app.appName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private data class PermissionItem(
    val title: String,
    val granted: Boolean,
    val action: () -> Unit,
)

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    items: List<PermissionItem>,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
            items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(item.title)
                        Text(
                            text = if (item.granted) "已开启" else "未开启",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = item.action) {
                        Icon(Icons.Outlined.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (item.granted) "查看" else "去开启")
                    }
                }
                if (index != items.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AppSelectorScreen(
    modifier: Modifier,
    mode: SelectorMode,
    apps: List<InstalledApp>,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onBack: () -> Unit,
    onAssignRole: (InstalledApp, AppRole) -> Unit,
    onUpdateMultiplier: (String, Multiplier) -> Unit,
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = mode.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("返回")
            }
            Button(onClick = onBack) {
                Text("完成")
            }
        }

        Text(
            text = "同一个 App 只能属于一类，重新勾选会自动移动到当前分类。",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null)
            },
            label = { Text("搜索 App 名称或包名") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(apps, key = { it.packageName }) { app ->
                SelectorAppRow(
                    app = app,
                    mode = mode,
                    onAssignRole = onAssignRole,
                    onUpdateMultiplier = onUpdateMultiplier,
                )
            }
        }
    }
}

@Composable
private fun SelectorAppRow(
    app: InstalledApp,
    mode: SelectorMode,
    onAssignRole: (InstalledApp, AppRole) -> Unit,
    onUpdateMultiplier: (String, Multiplier) -> Unit,
) {
    val selected = app.role == mode.role
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(packageName = app.packageName, appName = app.appName, size = 40.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(app.appName, style = MaterialTheme.typography.titleMedium)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                    if (!selected && app.role != AppRole.NORMAL) {
                        Text(
                            text = "当前已在${app.role.toGroupLabel()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Checkbox(
                    checked = selected,
                    onCheckedChange = { checked ->
                        onAssignRole(app, if (checked) mode.role else AppRole.NORMAL)
                    },
                )
            }

            if (mode == SelectorMode.ENTERTAINMENT && selected) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Multiplier.entries.forEach { multiplier ->
                        FilterChip(
                            selected = app.multiplier == multiplier,
                            onClick = { onUpdateMultiplier(app.packageName, multiplier) },
                            label = { Text(multiplier.label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(
    packageName: String,
    appName: String,
    size: androidx.compose.ui.unit.Dp,
) {
    val context = LocalContext.current
    val imageBitmap by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager
                    .getApplicationIcon(packageName)
                    .toBitmap(width = 96, height = 96)
                    .asImageBitmap()
            }.getOrNull()
        }
    }

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap!!,
            contentDescription = appName,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Apps,
                contentDescription = appName,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun AppRole.toGroupLabel(): String = when (this) {
    AppRole.NORMAL -> "普通 App"
    AppRole.COLLECT -> "收集时间 App"
    AppRole.ENTERTAINMENT -> "娱乐 App"
}
