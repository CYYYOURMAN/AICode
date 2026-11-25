package com.aicode.app.pages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicode.app.ControlService
import com.aicode.app.OverlayCapsule
import com.aicode.app.R
import com.aicode.app.data.AppUpdateInfo
import com.aicode.app.data.AppUpdater
import com.aicode.app.data.EngineEndpoint
import com.aicode.app.data.ServerConfig
import com.aicode.app.ui.AicodeCard
import com.aicode.app.ui.AicodeTopBar
import com.aicode.app.ui.DividerLine
import com.aicode.app.ui.LocalIsDark
import com.aicode.app.ui.SectionTitle
import com.aicode.app.ui.SettingRow
import com.aicode.app.ui.StatusBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    endpoint: EngineEndpoint?,
    engineLoaded: Boolean,
    engineVersion: com.aicode.app.data.EngineVersion? = null,
    onRefresh: () -> Unit,
    onThemeChange: (Boolean) -> Unit,
    onReconnect: () -> Unit = {},
) {
    val dark = LocalIsDark.current
    val ctx = LocalContext.current
    // 无障碍状态读系统全局设置：测试版（com.aicode.app.beta）本包服务没开时，靠稳定版
    // （com.aicode.app）的无障碍服务干活，只看本进程 instance 会把它误报成「未开启」。
    var a11yState by remember { mutableStateOf(readA11yState(ctx)) }
    val a11yEnabled = a11yState != A11yState.OFF
    val a11yDetail = when (a11yState) {
        A11yState.ON -> "已开启，可以执行全局操作"
        A11yState.VIA_STABLE -> "已开启（由稳定版 AIcode 提供）"
        A11yState.OFF -> "未开启，点下面按钮去开启"
    }
    val a11yBadge = when (a11yState) {
        A11yState.ON -> "运行中"
        A11yState.VIA_STABLE -> "由稳定版提供"
        A11yState.OFF -> "未开启"
    }
    val powerManager = remember {
        ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
    }
    var batteryUnrestricted by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true)
    }
    LaunchedEffect(Unit) {
        while (true) {
            a11yState = readA11yState(ctx)
            batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
            kotlinx.coroutines.delay(2000)
        }
    }
    val overlayOk = OverlayCapsule.canDraw(ctx)


    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var watchStatus by remember { mutableStateOf("还没开始监听") }
    var repairing by remember { mutableStateOf(false) }
    var repairResult by remember { mutableStateOf<String?>(null) }
    // 服务器配置进页面读一次即可：「重新配对」清掉后会由 MainActivity 切回连接页
    val serverBase = remember { ServerConfig.base(ctx) }
    val currentVersion = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" }.getOrDefault("?")
    }
    LaunchedEffect(Unit) {
        while (true) {
            watchStatus = runCatching {
                ctx.getSharedPreferences("aicode-watch", android.content.Context.MODE_PRIVATE)
                    .getString("status", "") ?: ""
            }.getOrDefault("").ifEmpty { "还没开始监听" }
            kotlinx.coroutines.delay(3000)
        }
    }

    fun currentVersionCode(): Int = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)

    Column(Modifier.fillMaxSize()) {
        AicodeTopBar(title = "设置", subtitle = "引擎连接 · 外观 · 手机控制")
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionTitle("引擎")
            AicodeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DSH 连接", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                endpoint != null ->
                                    endpoint.base
                                engineLoaded -> "未连接"
                                else -> "正在获取…"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusBadge(active = endpoint != null, text = if (endpoint != null) "已连接" else "离线")
                }
                Spacer(Modifier.height(12.dp))
                DividerLine()
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_web),
                    title = "当前 token",
                    subtitle = if (endpoint != null) "已获取 · 与服务器同步" else "无",
                )
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_settings),
                    title = "引擎版本",
                    subtitle = when {
                        engineVersion == null -> "未获取"
                        engineVersion.latest.isNotEmpty() && engineVersion.latest != engineVersion.current ->
                            "有新版本 ${engineVersion.latest}（服务器更新引擎后重启生效）"
                        else -> "已是最新"
                    },
                    value = engineVersion?.current ?: "—",
                )
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_terminal),
                    title = "Node.js",
                    subtitle = engineVersion?.npm?.takeIf { it.isNotEmpty() }?.let { "npm $it" },
                    value = engineVersion?.node?.takeIf { it.isNotEmpty() } ?: "—",
                )
                TextButton(onClick = onRefresh, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重新获取 token")
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("外观")
            AicodeCard {
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_settings),
                    title = "深色主题",
                    subtitle = if (dark) "当前：深色" else "当前：浅色",
                    trailing = {
                        Switch(checked = dark, onCheckedChange = onThemeChange)
                    },
                )
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("手机控制")
            AicodeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("无障碍服务", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            a11yDetail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusBadge(active = a11yEnabled, text = a11yBadge)
                }
                if (!a11yEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("打开无障碍设置")
                    }
                    TextButton(
                        onClick = {
                            if (!repairing) {
                                scope.launch {
                                    repairing = true
                                    repairResult = null
                                    val ok = withContext(Dispatchers.IO) {
                                        ControlService.repairAccessibility(ctx)
                                    }
                                    repairing = false
                                    repairResult = if (ok) {
                                        "已修复，几秒后会自动连上"
                                    } else {
                                        "没成功：需要 root。到 KernelSU 管理器允许 AIcode 使用 root 后重试"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (repairing) "修复中…" else "一键修复（需要 root）")
                    }
                    if (repairResult != null) {
                        Text(
                            repairResult!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ControlAction("返回", Icons.Filled.ArrowBack, Modifier.weight(1f)) { ControlService.back() }
                        ControlAction("主页", Icons.Filled.Home, Modifier.weight(1f)) { ControlService.home() }
                        ControlAction("最近任务", Icons.Filled.List, Modifier.weight(1f)) { ControlService.recents() }
                    }
                }
                Spacer(Modifier.height(12.dp))
                DividerLine()
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_settings),
                    title = "操控状态提示",
                    subtitle = if (overlayOk) "操控时屏幕顶部显示「AI 操作中」" else "需要「显示在其他应用上层」权限",
                    value = if (overlayOk) "已开启" else "未开启",
                )
                if (!overlayOk) {
                    TextButton(
                        onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        },
                    ) {
                        Text("打开悬浮窗设置")
                    }
                }
                Spacer(Modifier.height(12.dp))
                DividerLine()
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_settings),
                    title = "后台不被清理",
                    subtitle = if (batteryUnrestricted) "已允许后台运行，手机控制更稳"
                    else "系统省电可能清理后台，手机控制会断",
                    value = if (batteryUnrestricted) "已允许" else "未允许",
                )
                if (!batteryUnrestricted) {
                    TextButton(
                        onClick = {
                            val pkg = ctx.packageName
                            val direct = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$pkg"),
                            )
                            runCatching {
                                ctx.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }
                        },
                    ) {
                        Text("允许后台运行")
                    }
                }
                Spacer(Modifier.height(12.dp))
                DividerLine()
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_files),
                    title = "回合完成通知",
                    subtitle = watchStatus,
                )
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("服务器")
            AicodeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("网关地址", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (serverBase.isNotEmpty()) serverBase else "未配置",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusBadge(
                        active = serverBase.isNotEmpty(),
                        text = if (serverBase.isNotEmpty()) "已配置" else "未配置",
                    )
                }
                Spacer(Modifier.height(10.dp))
                DividerLine()
                Spacer(Modifier.height(10.dp))
                Text(
                    "密钥只保存在本机，不会在这里显示。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        ServerConfig.clear(ctx)
                        onReconnect()
                    },
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重新配对")
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("关于")
            AicodeCard {
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_terminal),
                    title = "AIcode",
                    subtitle = "Compose 重写版",
                    value = currentVersion,
                )
                DividerLine()
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_settings),
                    title = "检查更新",
                    subtitle = when {
                        checking -> "正在检查…"
                        update != null -> "发现新版本 ${update?.versionName}"
                        updateError != null -> updateError
                        checked -> "已是最新版本"
                        else -> "从服务器检查最新版本"
                    },
                    onClick = {
                        if (!checking && !downloading) {
                            scope.launch {
                                checking = true
                                updateError = null
                                checked = false
                                val info = withContext(Dispatchers.IO) { AppUpdater.check(ctx) }
                                checking = false
                                checked = true
                                if (info == null) {
                                    updateError = "连不上服务器（不在家时需连 Tailscale）"
                                    update = null
                                } else if (info.versionCode > currentVersionCode()) {
                                    update = info
                                } else {
                                    update = null
                                }
                            }
                        }
                    },
                )
                if (update != null) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            if (!downloading) {
                                scope.launch {
                                    downloading = true
                                    progress = 0
                                    updateError = null
                                    val apk = withContext(Dispatchers.IO) {
                                        AppUpdater.download(ctx) { percent -> scope.launch { progress = percent } }
                                    }
                                    downloading = false
                                    if (apk == null) {
                                        updateError = "下载失败"
                                    } else {
                                        runCatching { AppUpdater.install(ctx, apk) }
                                            .onFailure { updateError = "拉起安装失败：${it.message}" }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (downloading) "下载中 ${progress}%" else "下载并安装 ${update?.versionName}")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Token 由本地 token 服务下发；服务器地址和密钥在上面的「服务器」区块里修改。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun ControlAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .height(76.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.09f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** 无障碍服务的三种状态：本包服务开着 / 只有稳定版开着 / 都没开。 */
private enum class A11yState { ON, VIA_STABLE, OFF }

/** 系统里已启用的无障碍服务组件全名（冒号分隔）。 */
private fun globalA11yServices(ctx: Context): String =
    runCatching {
        android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services") ?: ""
    }.getOrDefault("")

private fun readA11yState(ctx: Context): A11yState {
    val enabled = globalA11yServices(ctx)
        .split(':')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    // applicationId 可能是 com.aicode.app 或 com.aicode.app.beta，但类名始终是 com.aicode.app.ControlService
    val own = ctx.packageName + "/com.aicode.app.ControlService"
    val stable = "com.aicode.app/com.aicode.app.ControlService"
    return when {
        enabled.contains(own) -> A11yState.ON
        enabled.contains(stable) -> A11yState.VIA_STABLE
        else -> A11yState.OFF
    }
}
