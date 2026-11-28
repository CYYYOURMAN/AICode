package com.aicode.app.pages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    onNavigate: (String) -> Unit = {},
    resetSignal: Int = 0,
    followSystem: Boolean = false,
    onFollowSystemChange: (Boolean) -> Unit = {},
    accent: com.aicode.app.ui.AppAccent = com.aicode.app.ui.AppAccent.Blue,
    onAccentChange: (com.aicode.app.ui.AppAccent) -> Unit = {},
    fontSize: com.aicode.app.ui.AppFontSize = com.aicode.app.ui.AppFontSize.Medium,
    onFontSizeChange: (com.aicode.app.ui.AppFontSize) -> Unit = {},
    density: com.aicode.app.ui.AppDensity = com.aicode.app.ui.AppDensity.Standard,
    onDensityChange: (com.aicode.app.ui.AppDensity) -> Unit = {},
) {
    val dark = LocalIsDark.current
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("aicode", Context.MODE_PRIVATE) }
    var reloadMinutes by remember { mutableStateOf(prefs.getInt("webReloadMinutes", 10)) }
    var showReloadDialog by remember { mutableStateOf(false) }
    // 无障碍状态读系统全局设置：测试版（com.aicode.app.beta）本包服务没开时，靠稳定版
    // （com.aicode.app）的无障碍服务干活，只看本进程 instance 会把它误报成「未开启」。
    var a11yState by remember { mutableStateOf(readA11yState(ctx)) }
    val a11yEnabled = a11yState != A11yState.OFF
    // 副标题说「能干什么」，徽章说「开没开」——别两处都写「已开启」（2026-11-26 用户反馈重复）
    val a11yDetail = when (a11yState) {
        A11yState.ON -> "可以帮你在别的应用里点按"
        A11yState.VIA_STABLE -> "可以帮你在别的应用里点按"
        A11yState.OFF -> "还没开启，点下面打开"
    }
    val a11yBadge = when (a11yState) {
        A11yState.ON -> "运行中"
        A11yState.VIA_STABLE -> "已开启"
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
    // 一级/二级菜单：null = 一级列表，否则是二级页 key（2026-11-26 用户要求分两级）
    var subPage by remember { mutableStateOf<String?>(null) }
    // 点底栏「我的」回到一级（MainActivity 传进来，递增即重置）
    androidx.compose.runtime.LaunchedEffect(resetSignal) { if (resetSignal > 0) subPage = null }
    // 二级页按系统返回键：先回一级，别一下就跳回对话页
    androidx.activity.compose.BackHandler(enabled = subPage != null) { subPage = null }
    var repairing by remember { mutableStateOf(false) }
    var repairResult by remember { mutableStateOf<String?>(null) }
    // 服务器配置进页面读一次即可：「重新配对」清掉后会由 MainActivity 切回连接页
    val serverBase = remember { ServerConfig.base(ctx) }
    // MCP 配置（地址/密钥/开关）：设置页可改，鱼世界页重新进入后生效
    var mcpUrl by remember { mutableStateOf(ServerConfig.mcpUrl(ctx)) }
    var hubUrl by remember { mutableStateOf(ServerConfig.hubUrl(ctx)) }
    var hubToken by remember { mutableStateOf(ServerConfig.hubToken(ctx)) }
    var showHubDialog by remember { mutableStateOf(false) }
    var mcpToken by remember { mutableStateOf(ServerConfig.mcpToken(ctx)) }
    var mcpEnabled by remember { mutableStateOf(ServerConfig.mcpEnabled(ctx)) }
    var showMcpDialog by remember { mutableStateOf(false) }
    var mcpNotice by remember { mutableStateOf("") }
    val currentVersion = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" }.getOrDefault("?")
    }
    LaunchedEffect(Unit) {
        while (true) {
            val raw = runCatching {
                ctx.getSharedPreferences("aicode-watch", android.content.Context.MODE_PRIVATE)
                    .getString("status", "") ?: ""
            }.getOrDefault("").ifEmpty { "还没开始监听" }
            // 通知状态只保留「上次通知 HH:MM:SS」这段，不显示会话标题（隐私/简洁）
            watchStatus = if (raw.startsWith("上次通知")) raw.substringBefore("：") else raw
            kotlinx.coroutines.delay(3000)
        }
    }

    fun currentVersionCode(): Int = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)

    Column(Modifier.fillMaxSize()) {
        AicodeTopBar(
            title = when (subPage) {
                "connect" -> "连接"
                "appearance" -> "外观"
                "phone" -> "让 AI 操作手机"
                "more" -> "更多功能"
                "advanced" -> "高级"
                "about" -> "关于"
                else -> "我的"
            },
            subtitle = if (subPage == null) "连接 · 外观 · AI" else null,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
                        if (subPage == null) {
                // 一级：只列入口，每行带一句状态
                SectionTitle("设置")
                AicodeCard {
                    SettingRow(
                        icon = painterResource(R.drawable.ic_tab_settings),
                        title = "外观",
                        subtitle = if (dark) "深色" else "浅色",
                        value = "›",
                        onClick = { subPage = "appearance" },
                    )
                    DividerLine()
                    SettingRow(
                        icon = painterResource(R.drawable.ic_tab_fish),
                        title = "鱼世界",
                        subtitle = "另一个世界",
                        value = "›",
                        onClick = { onNavigate("fish") },
                    )
                    DividerLine()
                    SettingRow(
                        icon = painterResource(R.drawable.ic_tab_terminal),
                        title = "更多功能",
                        subtitle = "终端 · 文件 · 手机控制",
                        value = "›",
                        onClick = { subPage = "more" },
                    )
                    DividerLine()
                    SettingRow(
                        icon = painterResource(R.drawable.ic_tab_control),
                        title = "高级",
                        subtitle = if (endpoint != null) "已连接" else "未连接",
                        value = "›",
                        onClick = { subPage = "advanced" },
                    )
                    DividerLine()
                    SettingRow(
                        icon = painterResource(R.drawable.ic_tab_web),
                        title = "关于",
                        subtitle = "版本 $currentVersion",
                        value = "›",
                        onClick = { subPage = "about" },
                    )
                }
            } else {
                // 只留箭头：二级页左上角一个 ‹，点了回一级
                androidx.compose.material3.IconButton(onClick = { subPage = null }) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(22.dp),
                    )
                }
                when (subPage) {
                    "connect" -> {
            AicodeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("连接状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        // 地址挪到「高级」里了，这里只留一句人话状态
                        Text(
                            when {
                                endpoint != null -> "你的 AI 在自己的服务器上运行"
                                engineLoaded -> "还没连上服务器"
                                else -> "正在连接…"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusBadge(active = endpoint != null, text = when { endpoint != null -> "已连接"; engineLoaded -> "未连接"; else -> "正在连接…" })
                }
                Spacer(Modifier.height(12.dp))
                DividerLine()

                TextButton(onClick = onRefresh, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重新同步")
                }
            }

            Spacer(Modifier.height(18.dp))

                    }
                    "appearance" -> {
            AicodeCard {
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_settings),
                    title = "深色模式",
                    subtitle = if (dark) "当前：深色" else "当前：浅色",
                    trailing = {
                        Switch(checked = dark, onCheckedChange = onThemeChange)
                    },
                )
                Spacer(Modifier.height(12.dp))
                DividerLine()
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_web),
                    title = "自动刷新",
                    subtitle = if (reloadMinutes == 0) "关闭（长会话可能变卡）"
                    else "离开超过 $reloadMinutes 分钟刷新一次",
                    onClick = { showReloadDialog = true },
                )
                Spacer(Modifier.height(12.dp))
                DividerLine()
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_settings),
                    title = "跟随系统",
                    subtitle = if (followSystem) "跟着手机的深浅设置走" else "自己选深浅",
                    trailing = {
                        Switch(checked = followSystem, onCheckedChange = onFollowSystemChange)
                    },
                )
                Spacer(Modifier.height(12.dp))
                DividerLine()
                Column(Modifier.padding(vertical = 10.dp)) {
                    Text(
                        "主题色",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        com.aicode.app.ui.AppAccent.entries.forEach { a ->
                            val selected = a == accent
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onAccentChange(a) }
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(com.aicode.app.ui.accentColor(a, dark))
                                        .border(
                                            width = if (selected) 3.dp else 0.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape,
                                        ),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    a.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                DividerLine()
                ChoiceRow(
                    title = "字号",
                    options = com.aicode.app.ui.AppFontSize.entries.map { it.key to it.label },
                    selected = fontSize.key,
                    onSelect = { k -> onFontSizeChange(com.aicode.app.ui.AppFontSize.of(k)) },
                )
                DividerLine()
                ChoiceRow(
                    title = "密度",
                    options = com.aicode.app.ui.AppDensity.entries.map { it.key to it.label },
                    selected = density.key,
                    onSelect = { k -> onDensityChange(com.aicode.app.ui.AppDensity.of(k)) },
                )
            }
            if (showReloadDialog) {
                AlertDialog(
                    onDismissRequest = { showReloadDialog = false },
                    title = { Text("自动刷新") },
                    text = {
                        Column {
                            Text(
                                "离开太久再回来会刷新一次，长对话更流畅。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            listOf(5 to "5 分钟", 10 to "10 分钟（默认）", 30 to "30 分钟", 0 to "不自动刷新").forEach { (m, label) ->
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (m == reloadMinutes) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            reloadMinutes = m
                                            prefs.edit().putInt("webReloadMinutes", m).apply()
                                            showReloadDialog = false
                                        }
                                        .padding(vertical = 12.dp),
                                )
                            }
                        }
                    },
                    confirmButton = {},
                )
            }

            Spacer(Modifier.height(18.dp))

                    }
                    "phone" -> {
            AicodeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("操作权限", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                        Text("去开启")
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
                }
                Spacer(Modifier.height(12.dp))
                DividerLine()
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_settings),
                    title = "显示操作提示",
                    subtitle = if (overlayOk) "AI 操作时屏幕顶部显示提示条" else "需要「显示在其他应用上层」权限",
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
                        Text("去开启")
                    }
                }
                Spacer(Modifier.height(12.dp))
                DividerLine()
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_settings),
                    title = "后台保持运行",
                    subtitle = if (batteryUnrestricted) "已允许，AI 操作更稳"
                    else "系统省电可能清掉后台，AI 会连不上",
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
                        Text("保持后台运行")
                    }
                }
                Spacer(Modifier.height(12.dp))
                DividerLine()
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_files),
                    title = "完成时通知我",
                    subtitle = "有新回合完成时提醒你",
                    onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }

            Spacer(Modifier.height(18.dp))
            // 底部只留「对话 / 我的」两个标签，终端、文件、鱼世界从这里进

                    }
                    "more" -> {
            AicodeCard {
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_terminal),
                    title = "终端",
                    subtitle = "在服务器上执行命令",
                    onClick = { onNavigate("terminal") },
                )
                DividerLine()
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_files),
                    title = "文件",
                    subtitle = "看服务器上的文件",
                    onClick = { onNavigate("files") },
                )
                DividerLine()
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_control),
                    title = "AI 中枢",
                    subtitle = if (hubUrl.isNotBlank()) hubUrl else "控制台：任务、设备、审计",
                    value = "›",
                    onClick = { onNavigate("hub") },
                )
                DividerLine()
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_settings),
                    title = "AI 中枢地址",
                    subtitle = if (hubUrl.isNotBlank()) "已自定义" else "自动（服务器 8310 端口）",
                    value = "›",
                    onClick = { showHubDialog = true },
                )
                DividerLine()
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_control),
                    title = "让 AI 操作手机",
                    subtitle = if (a11yEnabled) "已开启" else "未开启",
                    onClick = { subPage = "phone" },
                )
            }

                    }
                    "advanced" -> {
            AicodeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("服务器地址", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (serverBase.isNotEmpty()) serverBase else "未连接",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusBadge(
                        active = serverBase.isNotEmpty(),
                        text = if (serverBase.isNotEmpty()) "已连接" else "未连接",
                    )
                }
                Text(
                    "App 的入口：配对、发访问凭证、手机控制、检查更新都走这里。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                DividerLine()
                Spacer(Modifier.height(10.dp))
                // AI 引擎地址：故意和网关地址分开列，各带一句说明，避免「两个网址不知道是干嘛的」
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI 引擎地址", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            endpoint?.base ?: "未获取",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "真正跑 AI 的地方：聊天页面连的是这个地址。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                DividerLine()
                Spacer(Modifier.height(10.dp))
                Text(
                    "访问凭证只保存在本机，不会在这里显示。",
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
                    Text("重新连接")
                }
                TextButton(
                    onClick = {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        val text = ServerConfig.base(ctx) + "|" + ServerConfig.secret(ctx)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("aicode-connect", text))
                        android.widget.Toast.makeText(
                            ctx, "已复制连接信息（含访问凭证，别发给别人）", android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                ) {
                    Text("复制连接信息")
                }
                Spacer(Modifier.height(10.dp))
                DividerLine()
                Spacer(Modifier.height(6.dp))
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_settings),
                    title = "AI 版本",
                    subtitle = when {
                        engineVersion == null -> "未获取"
                        engineVersion.latest.isNotEmpty() && engineVersion.latest != engineVersion.current ->
                            "服务器上有新版本 ${engineVersion.latest}，重启后生效"
                        else -> "已是最新"
                    },
                    value = engineVersion?.current ?: "—",
                )
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_terminal),
                    title = "运行环境",
                    value = engineVersion?.node?.takeIf { it.isNotEmpty() } ?: "—",
                )
            }

            Spacer(Modifier.height(18.dp))

            SectionTitle("MCP")
            AicodeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自定义 MCP", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "地址：${if (mcpUrl.isNotEmpty()) mcpUrl else "未配置"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = mcpEnabled,
                        onCheckedChange = { on ->
                            mcpEnabled = on
                            ServerConfig.saveMcp(ctx, mcpUrl, mcpToken, on)
                            mcpNotice = if (on) "MCP 已启用" else "MCP 已停用"
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                DividerLine()
                Spacer(Modifier.height(10.dp))
                Text(
                    "MCP 是外部工具服务（鱼世界就是一个 MCP）。地址和密钥只保存在本机，密钥不会在这里显示。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showMcpDialog = true }) {
                    Text("配置 MCP")
                }
                if (mcpNotice.isNotEmpty()) {
                    Text(
                        mcpNotice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

                    }
                    "about" -> {
            AicodeCard {
                SettingRow(
                    icon = painterResource(R.drawable.ic_tab_terminal),
                    title = "AIcode",
                    subtitle = "手机版",
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


                    }
                }
            }

            if (subPage == null || subPage == "connect" || subPage == "advanced") {
            Spacer(Modifier.height(24.dp))
            Text(
                "访问凭证由服务器下发；服务器地址在「高级」里改。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 100.dp),
            )
            }
        }
    }

    if (showHubDialog) {
        var urlDraft by remember { mutableStateOf(hubUrl) }
        var tokenDraft by remember { mutableStateOf(hubToken) }
        AlertDialog(
            onDismissRequest = { showHubDialog = false },
            title = { Text("AI 中枢") },
            text = {
                Column {
                    OutlinedTextField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        label = { Text("地址") },
                        placeholder = { Text("http://192.168.1.21:8310") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tokenDraft,
                        onValueChange = { tokenDraft = it },
                        label = { Text("令牌（可留空，用当前账号换）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "留空地址时自动用服务器的 8310 端口；令牌留空会尝试用当前登录换一个。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ServerConfig.saveHub(ctx, urlDraft, tokenDraft)
                    hubUrl = urlDraft.trim()
                    hubToken = tokenDraft.trim()
                    showHubDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showHubDialog = false }) { Text("取消") }
            },
        )
    }

    if (showMcpDialog) {
        // 弹窗每次打开时从当前配置预填；remember 放在 if 内，关闭即丢弃草稿
        var urlDraft by remember { mutableStateOf(mcpUrl) }
        var tokenDraft by remember { mutableStateOf(mcpToken) }
        AlertDialog(
            onDismissRequest = { showMcpDialog = false },
            title = { Text("MCP 配置") },
            text = {
                Column {
                    OutlinedTextField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        label = { Text("地址") },
                        placeholder = { Text("https://…/mcp") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tokenDraft,
                        onValueChange = { tokenDraft = it },
                        label = { Text("密钥") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { urlDraft = ServerConfig.FISH_WORLD_MCP_URL }) {
                        Text("填入鱼世界模板")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ServerConfig.saveMcp(ctx, urlDraft, tokenDraft, mcpEnabled)
                        mcpUrl = urlDraft.trim()
                        mcpToken = tokenDraft.trim()
                        showMcpDialog = false
                        mcpNotice = "已保存，鱼世界页重新进入后生效"
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMcpDialog = false }) {
                    Text("取消")
                }
            },
        )
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

/** 外观页的小横排选择器：一组胶囊按钮，选中一个立刻生效。 */
@Composable
private fun ChoiceRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { (key, label) ->
                val on = key == selected
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable { onSelect(key) }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}
