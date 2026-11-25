package com.aicode.app.pages

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import com.aicode.app.ui.AicodeTopBar
import org.json.JSONArray
import com.aicode.app.ui.LocalIsDark
import com.aicode.app.ui.appCardColor
import com.aicode.app.ui.appLineColor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class TermTab {
    var output by mutableStateOf("AIcode 服务器终端\n输入命令后回车运行。\n")
    var input by mutableStateOf("")
    val history = mutableListOf<String>()
    var histIndex by mutableStateOf(-1)
    val scroll = ScrollState(0)
}

@Composable
fun TerminalScreen(onOpenWeb: () -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("aicode-terminal", Context.MODE_PRIVATE) }
    val savedHistory = remember {
        runCatching {
            val raw = prefs.getString("history", "") ?: ""
            if (raw.isEmpty()) {
                emptyList<String>()
            } else {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).ifEmpty { null } }
            }
        }.getOrDefault(emptyList())
    }
    val tabs = remember {
        mutableStateListOf(
            TermTab().also { first ->
                first.history.addAll(savedHistory)
                first.histIndex = first.history.size
            },
        )
    }
    var current by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val safeIndex = current.coerceIn(0, tabs.size - 1)
    val tab = tabs[safeIndex]
    val scroll = tab.scroll

    fun runCommand() {
        val cmd = tab.input.trim()
        if (cmd.isEmpty()) return
        tab.input = ""
        if (tab.history.lastOrNull() != cmd) tab.history.add(cmd)
        tab.histIndex = tab.history.size
        // 命令历史持久化：所有标签合并、去重，最多 300 条
        runCatching {
            val merged = tabs.flatMap { it.history }.distinct().takeLast(300)
            prefs.edit().putString("history", JSONArray(merged).toString()).apply()
        }
        tab.output += "\n$ $cmd\n"
        scope.launch {
            val result = withContext(Dispatchers.IO) { runRemote(cmd) }
            tab.output += result
            delay(60)
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    Column(Modifier.fillMaxSize()) {
        AicodeTopBar(title = "终端", subtitle = "服务器 · ${tabs.size} 个标签 · 单条命令") {
            IconButton(onClick = {
                tabs.add(TermTab())
                current = tabs.size - 1
            }) {
                Icon(Icons.Filled.Add, contentDescription = "新建标签")
            }
        }

        // 真正的交互式终端在 DSH 网页版里（xterm.js）；这里只是单条命令的快捷输入
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "交互式终端在网页版：新建会话 →「新建终端」（支持 bash / vim）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenWeb) { Text("去网页版") }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEachIndexed { i, _ ->
                TabPill(
                    label = "终端 ${i + 1}",
                    selected = i == safeIndex,
                    onClick = { current = i },
                    onClose = if (tabs.size > 1) {
                        {
                            tabs.removeAt(i)
                            current = current.coerceAtMost(tabs.size - 1)
                        }
                    } else {
                        null
                    },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (LocalIsDark.current) Color(0xFF070B11) else Color(0xFFF1F4FA))
                .border(1.dp, appLineColor(), RoundedCornerShape(16.dp)),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp)) {
                Text(
                    tab.output,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 19.sp,
                    ),
                    color = if (LocalIsDark.current) Color(0xFFC9D4E6) else Color(0xFF1A2233),
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolChip("↑") {
                if (tab.history.isNotEmpty()) {
                    tab.histIndex = (tab.histIndex - 1).coerceAtLeast(0)
                    tab.input = tab.history[tab.histIndex]
                }
            }
            ToolChip("↓") {
                if (tab.history.isNotEmpty()) {
                    tab.histIndex = (tab.histIndex + 1).coerceAtMost(tab.history.size - 1)
                    tab.input = tab.history[tab.histIndex]
                }
            }
            ToolChip("清屏") { tab.output = "" }
            Spacer(Modifier.weight(1f))
            Text(
                "${tab.history.size} 条历史",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = tab.input,
                onValueChange = { tab.input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "输入命令…（在服务器上执行单条命令，非交互式）",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 3,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { runCommand() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = appCardColor(),
                    unfocusedContainerColor = appCardColor(),
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    unfocusedBorderColor = appLineColor(),
                ),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { runCommand() },
                enabled = tab.input.isNotBlank(),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(Icons.Filled.Send, contentDescription = "运行")
            }
        }
    }
}

@Composable
private fun TabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onClose: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else appCardColor()
            )
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else appLineColor(), shape)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = if (onClose != null) 8.dp else 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onClose != null) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.Close,
                contentDescription = "关闭标签",
                modifier = Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolChip(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(shape)
            .background(appCardColor())
            .border(1.dp, appLineColor(), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private val termClient: okhttp3.OkHttpClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(65, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

private fun runRemote(cmd: String): String = try {
    val body = org.json.JSONObject().put("cmd", cmd).toString()
        .toRequestBody("application/json".toMediaType())
    val req = okhttp3.Request.Builder()
        .url(com.aicode.app.data.ServerHost.httpBase() + "/exec")
        .header("Authorization", com.aicode.app.data.ServerHost.authHeader())
        .post(body)
        .build()
    termClient.newCall(req).execute().use { r ->
        val raw = r.body?.string().orEmpty()
        if (raw.isEmpty()) return "（空响应）"
        val o = org.json.JSONObject(raw)
        val out = o.optString("output")
            .replace(Regex("\\u001B\\[[;\\d]*[ -/]*[@-~]"), "")
            .replace(Regex("\\u001B\\][^\\u0007]*\\u0007"), "")
        out.trim().ifEmpty { "（无输出）" }
    }
} catch (e: Exception) {
    "错误：${e.message}"
}
