package com.aicode.app.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.app.ui.AicodeCard
import com.aicode.app.ui.AicodeTopBar
import com.aicode.app.ui.DividerLine
import com.aicode.app.ui.EmptyState
import com.aicode.app.ui.LocalIsDark
import com.aicode.app.ui.SectionTitle
import com.aicode.app.ui.appCardColor
import com.aicode.app.ui.appLineColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val fishClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}

private class TabData(
    val loading: Boolean = false,
    val error: String? = null,
    val text: String = "",
)

/**
 * 鱼世界页统一排版规范：五个页、所有卡片都按这一套走，别再各写各的数字。
 * - 页面左右 16dp；标签列 64dp，所有内容（标签行的内容、圆点列表、普通段落）都从这一列开始
 * - 同一块行距 6dp；卡片之间 10dp；分区之间 12dp
 */
private object FishLayout {
    val Gutter = 16.dp
    val LabelWidth = 64.dp
    val TimeWidth = 44.dp
    val RowGap = 6.dp
    val ItemGap = 10.dp
    val SectionGap = 12.dp
}

/** 世界页分区固定顺序：刷新多少次都按这个顺序排，新内容只替换对应分区、不重排。 */
private val WorldOrder = listOf("现在", "家", "能做", "下楼", "做饭", "衣柜", "能读的书", "这一带", "别的区")

private val FishTabs = listOf(
    "world" to "世界",
    "feed" to "动态",
    "me" to "名片",
    "talk" to "对话",
    "history" to "记录",
)

@Composable
fun FishScreen(mcpUrl: String?, mcpToken: String?, client: com.aicode.app.data.EngineClient?, cookie: String?) {
    val url = mcpUrl.orEmpty()
    val token = mcpToken.orEmpty()
    val mcpReady = url.isNotEmpty() && token.isNotEmpty()
    var tab by remember { mutableStateOf("world") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var world by remember { mutableStateOf(TabData()) }
    var feed by remember { mutableStateOf(TabData()) }
    var me by remember { mutableStateOf(TabData()) }
    var history by remember { mutableStateOf(TabData()) }
    var friends by remember { mutableStateOf(TabData()) }

    LaunchedEffect(mcpReady, url, token, tab, refreshKey) {
        if (!mcpReady) {
            // MCP 没配（设置 → MCP）：不发请求、不刷错误，页面显示「去设置填写」的提示卡片
            return@LaunchedEffect
        }
        when (tab) {
            "world" -> {
                world = TabData(loading = true)
                val r = withContext(Dispatchers.IO) { mcp(url, token, "world_now", "{\"full\":true}") }
                world = if (r.startsWith("错误")) TabData(error = r) else TabData(text = r)
            }
            "feed" -> {
                feed = TabData(loading = true)
                val r = withContext(Dispatchers.IO) { mcp(url, token, "world_feed", "{\"action\":\"read\"}") }
                feed = if (r.startsWith("错误")) TabData(error = r) else TabData(text = r)
            }
            "me" -> {
                me = TabData(loading = true)
                val r = withContext(Dispatchers.IO) { mcp(url, token, "world_profile", "{}") }
                me = if (r.startsWith("错误")) TabData(error = r) else TabData(text = r)
            }
            "talk" -> {
                friends = TabData(text = "chat")
            }
            "history" -> {
                history = TabData(loading = true)
                val r = withContext(Dispatchers.IO) { mcp(url, token, "world_history", "{}") }
                history = if (r.startsWith("错误")) TabData(error = r) else TabData(text = r)
            }
        }
    }

    val current = when (tab) {
        "feed" -> feed
        "me" -> me
        "talk" -> friends
        "history" -> history
        else -> world
    }

    Column(Modifier.fillMaxSize()) {
        AicodeTopBar(title = "鱼世界", subtitle = "world_now · 与现实同步") {
            IconButton(onClick = { refreshKey++ }, enabled = !current.loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FishTabs.forEach { (id, label) ->
                FishPill(label = label, selected = tab == id) { tab = id }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val fromIdx = FishTabs.indexOfFirst { it.first == initialState }
                    val toIdx = FishTabs.indexOfFirst { it.first == targetState }
                    val dir = if (toIdx >= fromIdx) 1 else -1
                    (slideInHorizontally(tween(210)) { width -> width * dir / 6 } + fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(tween(170)) { width -> -width * dir / 8 } + fadeOut(tween(140)))
                },
                label = "fishTab",
            ) { which ->
                val data = when (which) {
                    "feed" -> feed
                    "me" -> me
                    "talk" -> friends
                    "history" -> history
                    else -> world
                }
                when {
                    which != "talk" && !mcpReady -> McpNotConfigured()
                    data.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                    }
                    data.error != null -> Column(Modifier.fillMaxSize()) {
                        EmptyState("没读到鱼世界", data.error)
                        TextButton(onClick = { refreshKey++ }, modifier = Modifier.padding(start = 24.dp)) {
                            Text("重试")
                        }
                    }
                    else -> when (which) {
                        "world" -> WorldContent(data.text, url, token, onRefresh = { refreshKey++ })
                        "feed" -> FeedContent(data.text, url, token, onRefresh = { refreshKey++ })
                        "me" -> ProfileContent(data.text)
                        "talk" -> TalkContent(client = client, cookie = cookie)
                        else -> HistoryContent(data.text)
                    }
                }
            }
        }
    }
}

/** 未配置 MCP 时的提示卡片：告诉用户去设置里填，不发请求、不刷错误。 */
@Composable
private fun McpNotConfigured() {
    Column(Modifier.fillMaxSize().padding(horizontal = FishLayout.Gutter)) {
        AicodeCard {
            Text(
                "未配置 MCP",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "去「设置 → MCP」填写地址和密钥（可用鱼世界模板）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "地址和密钥只保存在本机；填好后回到这里会自动加载。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun FishPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        label,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else appCardColor())
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

// ---------------- 世界 ----------------

private data class WorldSection(val title: String, val lines: List<String>)

private fun cleanWorld(line: String): String = line
    .replace(Regex("（[^）]*world_[^）]*）"), "")
    .split(" · ")
    .filter { !it.contains("world_") && !it.contains("填 ") }
    .joinToString(" · ")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun stripEnglish(input: String): String {
    var t = input
    t = t.replace(Regex("\\s[a-z][a-z0-9]*(?:-[a-z0-9]+)*"), "")
    t = t.replace(Regex("（\\s*[a-z][a-z0-9 -]*\\s*）"), "")
    t = t.replace(':', '：')
    t = t.replace(Regex("\\s+"), " ")
    t = t.replace(Regex("\\s+([，、；。：）)])"), "$1")
    t = t.replace(Regex("([（(])\\s+"), "$1")
    return t.trim()
}

private fun splitWorld(raw: String): List<WorldSection> {
    val sections = mutableListOf<WorldSection>()
    var title = "现在"
    val lines = mutableListOf<String>()
    fun flush() {
        if (lines.isNotEmpty()) {
            sections.add(WorldSection(title, lines.toList()))
            lines.clear()
        }
    }
    for (rawLine in raw.lines()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val next = when {
            line.startsWith("你的家（") -> "家"
            line.startsWith("在这里能做（") -> "能做"
            line.startsWith("做饭（") -> "做饭"
            line.startsWith("衣柜（") -> "衣柜"
            line.startsWith("能读的书（") -> "能读的书"
            line.startsWith("这一带（") -> "这一带"
            line.startsWith("别的区（") -> "别的区"
            line.startsWith("下楼（") -> "下楼"
            else -> null
        }
        if (next != null) {
            flush()
            title = next
        }
        lines.add(line)
    }
    flush()
    return sections
}

private fun extractWorldId(line: String): String? {
    val t = line.trim().removePrefix("·").trim()
    val m = Regex("^\\S+\\s+([a-z0-9][a-z0-9-]*)").find(t) ?: return null
    return m.groupValues[1]
}

@Composable
fun WorldContent(text: String, mcpUrl: String, mcpToken: String, onRefresh: () -> Unit) {
    val mcpReady = mcpUrl.isNotEmpty() && mcpToken.isNotEmpty()
    val sections = remember(text) {
        splitWorld(text).sortedBy { section ->
            WorldOrder.indexOf(section.title).let { if (it >= 0) it else WorldOrder.size }
        }
    }
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun worldAction(tool: String, argsJson: String, label: String) {
        if (busy || !mcpReady) return
        busy = true
        notice = "正在$label…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { mcp(mcpUrl, mcpToken, tool, argsJson) }
            busy = false
            notice = r.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(120) ?: "完成"
            onRefresh()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FishLayout.Gutter),
    ) {
        if (notice.isNotEmpty()) {
            Text(
                notice,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
            )
        }
        sections.forEach { section ->
            val tool = when (section.title) {
                "能做", "下楼", "衣柜" -> "world_do"
                "这一带", "别的区" -> "world_go"
                else -> null
            }
            if (section.title == "现在") {
                AicodeCard {
                    SectionBody(section.lines, compact = true, title = section.title, onAction = null)
                }
            } else {
                CollapsibleSection(
                    section.title,
                    section.lines,
                    onAction = if (tool != null && mcpReady) {
                        { id ->
                            val args = if (tool == "world_go") {
                                "{\"place\":${JSONObject.quote(id)}}"
                            } else {
                                "{\"id\":${JSONObject.quote(id)}}"
                            }
                            worldAction(tool, args, if (tool == "world_go") "出发" else "做事")
                        }
                    } else {
                        null
                    },
                )
            }
            Spacer(Modifier.height(FishLayout.ItemGap))
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SectionBody(
    lines: List<String>,
    compact: Boolean = false,
    title: String? = null,
    onAction: ((String) -> Unit)? = null,
) {
    Column {
        lines.forEach { rawLine ->
            val cleanRaw = cleanWorld(rawLine)
            val rawId = extractWorldId(cleanRaw)
            var text = stripEnglish(cleanRaw)
            if (title != null) {
                text = text.removePrefix("$title：").removePrefix("$title:").trim()
            }
            if (text.isEmpty()) return@forEach
            if (text.endsWith("：") && text.length <= 12) return@forEach
            // 时间线行（「14：05 …」）：时间放左列、内容从同一列开始，别被当成「标签：内容」拆开
            val clock = Regex("^(\\d{1,2})[：:]\\s*(\\d{2})\\s+(.+)$").find(text)
            if (clock != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = FishLayout.RowGap / 2, bottom = FishLayout.RowGap / 2),
                ) {
                    Text(
                        "%02d:%02d".format(
                            clock.groupValues[1].toIntOrNull() ?: 0,
                            clock.groupValues[2].toIntOrNull() ?: 0,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.width(FishLayout.LabelWidth).padding(top = 2.dp),
                    )
                    Text(
                        clock.groupValues[3],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
                return@forEach
            }
            when {
                text.startsWith("· ") -> {
                    val id = rawId
                    val actionable = onAction != null && id != null
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (actionable) {
                                    Modifier
                                        .semantics { contentDescription = "做：$text" }
                                        .clickable { onAction!!.invoke(id!!) }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(start = 4.dp, top = FishLayout.RowGap / 2, bottom = FishLayout.RowGap / 2),
                    ) {
                        Text(
                            "·",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(FishLayout.LabelWidth),
                        )
                        Text(
                            text.removePrefix("· "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (actionable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                else -> {
                    val idx = text.indexOf("：")
                    if (idx in 1..7) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, end = 0.dp, top = FishLayout.RowGap, bottom = FishLayout.RowGap),
                        ) {
                            Text(
                                text.substring(0, idx).replace(Regex("（[^）]*）"), "").trim(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(FishLayout.LabelWidth),
                            )
                            Text(
                                text.substring(idx + 1),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else if (text.length > 90 && text.contains(" · ")) {
                        cleanRaw.split(" · ").forEach { rawPart ->
                            val p = stripEnglish(rawPart.trim())
                            val id = if (onAction != null) extractWorldId(rawPart.trim()) else null
                            Text(
                                "· " + p,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (id != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (id != null) Modifier.clickable { onAction!!.invoke(id!!) } else Modifier)
                                    .padding(start = FishLayout.LabelWidth, top = 2.dp, bottom = 2.dp),
                            )
                        }
                    } else {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = FishLayout.LabelWidth, top = FishLayout.RowGap / 2, bottom = FishLayout.RowGap / 2),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    lines: List<String>,
    onAction: ((String) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    AicodeCard(modifier = Modifier.animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            DividerLine()
            Spacer(Modifier.height(6.dp))
            SectionBody(lines, title = title, onAction = onAction)
        }
    }
}

// ---------------- 动态 ----------------

private data class FeedItem(
    val id: String,
    val author: String,
    val time: String,
    val place: String,
    val weather: String,
    val body: String,
    val likes: String,
)

private fun parseFeed(raw: String): List<FeedItem> {
    val items = mutableListOf<FeedItem>()
    var cur: FeedItem? = null
    fun flush() {
        cur?.let { if (it.body.isNotBlank()) items.add(it) }
    }
    for (line0 in raw.lines()) {
        val line = line0.trimEnd()
        val trimmed = line.trim()
        if (trimmed.startsWith("· 动态 ")) {
            flush()
            val parts = trimmed.removePrefix("· 动态 ").split(" · ")
            cur = FeedItem(
                id = parts.getOrNull(0)?.trim() ?: "",
                author = parts.getOrNull(1)?.trim() ?: "",
                time = parts.getOrNull(2)?.trim() ?: "",
                place = parts.getOrNull(3)?.trim() ?: "",
                weather = parts.getOrNull(4)?.trim() ?: "",
                body = "",
                likes = "",
            )
        } else if (cur != null) {
            when {
                trimmed.startsWith(">") -> cur = cur!!.copy(
                    body = (cur!!.body + "\n" + trimmed.removePrefix(">").trim()).trim(),
                )
                trimmed.contains("赞了") -> cur = cur!!.copy(likes = trimmed)
            }
        }
    }
    flush()
    return items
}

@Composable
private fun FeedContent(text: String, mcpUrl: String, mcpToken: String, onRefresh: () -> Unit) {
    val mcpReady = mcpUrl.isNotEmpty() && mcpToken.isNotEmpty()
    val items = remember(text) { parseFeed(text) }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    fun feedAction(json: String, okMsg: String) {
        if (busy || !mcpReady) return
        busy = true
        notice = "处理中…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { mcp(mcpUrl, mcpToken, "world_feed", json) }
            busy = false
            notice = if (r.startsWith("错误")) "失败：$r" else okMsg
            if (!r.startsWith("错误")) onRefresh()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = FishLayout.Gutter),
        verticalArrangement = Arrangement.spacedBy(FishLayout.ItemGap),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        item {
            AicodeCard {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("说点什么给全城听…", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (notice.isNotEmpty()) {
                        Text(
                            notice,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    } else if (!mcpReady) {
                        Text(
                            "未配置 MCP · 去「设置 → MCP」填写后才能发布",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = {
                            val t = draft.trim()
                            if (t.isNotEmpty()) {
                                feedAction(
                                    "{\"action\":\"post\",\"text\":${JSONObject.quote(t)}}",
                                    "已发布",
                                )
                                draft = ""
                            }
                        },
                        enabled = !busy && mcpReady && draft.isNotBlank(),
                    ) {
                        Text("发布")
                    }
                }
            }
        }
        if (items.isEmpty()) {
            item {
                EmptyState("还没有动态", "往下滑一页试试")
            }
        }
        items(items) { item ->
            AicodeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "#${item.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.author,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        item.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    listOf(item.place, item.weather).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    item.body,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.likes.ifEmpty { "" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    val postId = item.id.toIntOrNull()
                    if (postId != null && mcpReady) {
                        Text(
                            "赞",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(enabled = !busy) {
                                    feedAction("{\"action\":\"like\",\"post\":$postId}", "已点赞")
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                        Text(
                            "取消赞",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(enabled = !busy) {
                                    feedAction("{\"action\":\"unlike\",\"post\":$postId}", "已取消")
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------- 名片 ----------------

private data class ProfileRow(val label: String?, val value: String, val sub: Boolean = false)

private data class ProfileCard(val name: String, val status: String?, val rows: List<ProfileRow>)

private fun cleanProfileLabel(label: String): String {
    val idx = label.indexOf("（")
    return (if (idx > 0) label.substring(0, idx) else label).trim()
}

private fun parseProfile(raw: String): ProfileCard {
    var name = ""
    var status: String? = null
    val rows = mutableListOf<ProfileRow>()
    for (line0 in raw.lines()) {
        val line = line0.trimEnd()
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        if (trimmed.startsWith("——")) {
            status = stripEnglish(trimmed.trimStart('—').trim()).ifEmpty { null }
            continue
        }
        val sub = line.startsWith("  ") || line.startsWith("\t")
        val cleaned = stripEnglish(trimmed)
        val idx = cleaned.indexOf("：")
        if (idx in 1..12) {
            rows.add(ProfileRow(cleanProfileLabel(cleaned.substring(0, idx)), cleaned.substring(idx + 1).trim(), sub))
        } else if (name.isEmpty() && !sub) {
            name = cleaned
        } else {
            rows.add(ProfileRow(null, cleaned, sub))
        }
    }
    return ProfileCard(name, status, rows)
}

@Composable
private fun ProfileContent(text: String) {
    val card = remember(text) { parseProfile(text) }
    val shape = remember(card) { card.rows.filter { !it.sub && (it.label == "外貌" || it.label == "穿着") } }
    val home = remember(card) { card.rows.filter { it.sub || it.label == "家" } }
    val bag = remember(card) {
        card.rows.filter { !it.sub && it.label != "外貌" && it.label != "穿着" && it.label != "家" }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        AicodeCard {
            Text(
                card.name.ifEmpty { "我" },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
            if (!card.status.isNullOrEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    card.status,
                    style = MaterialTheme.typography.labelMedium.copy(lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(FishLayout.SectionGap))
        ProfileGroup("样子", shape)
        ProfileGroup("家", home, address = true)
        ProfileGroup("随身", bag)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ProfileGroup(title: String, rows: List<ProfileRow>, address: Boolean = false) {
    val place = if (address) rows.firstOrNull { it.label == "家" }?.value else null
    val body = if (address) rows.filter { it.label != "家" } else rows
    if (place == null && body.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    SectionTitle(title)
    AicodeCard {
        if (place != null) {
            Text(
                place,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (body.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                DividerLine()
                Spacer(Modifier.height(10.dp))
            }
        }
        body.forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.height(if (row.sub) 3.dp else 7.dp))
            if (row.sub) {
                Row(Modifier.fillMaxWidth()) {
                    if (row.label != null) {
                        Text(
                            row.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(FishLayout.LabelWidth),
                        )
                    }
                    Text(
                        row.value,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth()) {
                    if (row.label != null) {
                        Text(
                            row.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(FishLayout.LabelWidth),
                        )
                    }
                    Text(
                        row.value,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ---------------- 对话（跟鱼世界里的你） ----------------

private const val FISH_SESSION_ID = "session-aicode-fish"

private data class ChatLine(val id: Long, val text: String, val mine: Boolean)

private fun dshCall(
    client: com.aicode.app.data.EngineClient,
    cookie: String,
    path: String,
    rpcId: String,
    args: JSONObject,
): JSONObject? {
    val payload = JSONObject()
        .put("type", "client-request")
        .put("rpcId", rpcId)
        .put("method", path.removePrefix("/api/"))
        .put("payload", JSONObject().put("args", JSONObject().put("request", args)))
    return client.post(cookie, path, payload)
}

private fun dshSend(
    client: com.aicode.app.data.EngineClient,
    cookie: String,
    text: String,
    requestId: String,
    lastMessageId: String?,
): Pair<String, String?> {
    // 会话可能被归档：归档后 prompt 会被接受但不会执行，wait 只会反复返回旧消息（表现为「重复最后一句」）。
    // 每次发消息前先取消归档，确保会话能正常跑新回合。
    runCatching {
        dshCall(
            client, cookie, "/api/workspace/unarchiveSession", "fish-unarchive-$requestId",
            JSONObject().put("sessionId", FISH_SESSION_ID),
        )
    }
    dshCall(
        client, cookie, "/api/session/create", "fish-create",
        JSONObject().put("sessionId", FISH_SESSION_ID),
    )
    val prompt = dshCall(
        client, cookie, "/api/session/prompt", "fish-prompt",
        JSONObject()
            .put("sessionId", FISH_SESSION_ID)
            .put("requestId", requestId)
            .put("mode", "queue")
            .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text))),
    )
    val accepted = prompt?.optJSONObject("result")?.optBoolean("ok") == true
    if (!accepted) {
        val msg = prompt?.optJSONObject("result")?.optJSONObject("error")?.optString("message")
        return Pair("（没送出去：${msg ?: "未知错误"}）", lastMessageId)
    }
    var lastReason = ""
    repeat(3) {
        val wait = dshCall(
            client, cookie, "/api/session/wait", "fish-wait-$it",
            JSONObject().put("sessionId", FISH_SESSION_ID).put("timeoutMs", 25000).put("maxMessages", 2),
        )
        val value = wait?.optJSONObject("result")?.optJSONObject("value")
            ?: return Pair("（没等到回复）", lastMessageId)
        lastReason = value.optString("reason")
        val (reply, msgId) = extractAssistantMessage(value)
        // 只认新出现的消息；如果返回的还是上一轮的旧消息，继续等
        if (reply.isNotBlank() && msgId != lastMessageId) return Pair(reply, msgId)
        if (lastReason == "idle" && reply.isNotBlank() && msgId == lastMessageId) {
            return Pair("（这次好像没接上，再发一次试试）", lastMessageId)
        }
        if (lastReason == "idle") return Pair("（她说完了，但这次没有文字）", lastMessageId)
    }
    return Pair(
        if (lastReason == "timeout") "（还在想，过一会儿再问一次）" else "（没等到回复）",
        lastMessageId,
    )
}

private fun extractAssistantMessage(value: JSONObject): Pair<String, String?> {
    val records = value.optJSONObject("output")?.optJSONArray("records") ?: return Pair("", null)
    for (i in 0 until records.length()) {
        val event = records.optJSONObject(i)?.optJSONObject("event") ?: continue
        if (event.optString("type") != "assistant/message") continue
        val message = event.optJSONObject("data")?.optJSONObject("message") ?: continue
        val content = message.optJSONArray("content") ?: continue
        val sb = StringBuilder()
        for (j in 0 until content.length()) {
            val part = content.optJSONObject(j) ?: continue
            if (part.optString("type") == "text") {
                val t = part.optString("text")
                if (t.isNotBlank()) sb.append(t)
            }
        }
        if (sb.isNotBlank()) return Pair(sb.toString().trim(), message.optString("id"))
    }
    return Pair("", null)
}

private const val CHAT_PREFS = "aicode"
private const val CHAT_KEY = "fish_chat"

private fun loadChat(ctx: android.content.Context): List<ChatLine> {
    val raw = ctx.getSharedPreferences(CHAT_PREFS, android.content.Context.MODE_PRIVATE)
        .getString(CHAT_KEY, "") ?: return emptyList()
    if (raw.isEmpty()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ChatLine(o.optLong("id"), o.optString("text"), o.optBoolean("mine"))
        }
    }.getOrDefault(emptyList())
}

private fun saveChat(ctx: android.content.Context, lines: List<ChatLine>) {
    val arr = org.json.JSONArray()
    lines.takeLast(200).forEach {
        arr.put(JSONObject().put("id", it.id).put("text", it.text).put("mine", it.mine))
    }
    ctx.getSharedPreferences(CHAT_PREFS, android.content.Context.MODE_PRIVATE)
        .edit().putString(CHAT_KEY, arr.toString()).apply()
}

@Composable
private fun TalkContent(client: com.aicode.app.data.EngineClient?, cookie: String?) {
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var lastBotId by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val lines = remember {
        mutableStateListOf<ChatLine>().also { it.addAll(loadChat(ctx)) }
    }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        val c = client ?: return
        val ck = cookie ?: return
        input = ""
        lines.add(ChatLine(System.nanoTime(), text, mine = true))
        saveChat(ctx, lines)
        // 自己发的消息也要立刻滚到底，不然停在上面看不见
        scope.launch { runCatching { listState.animateScrollToItem((lines.size - 1).coerceAtLeast(0)) } }
        sending = true
        scope.launch {
            val (reply, newId) = withContext(Dispatchers.IO) {
                dshSend(c, ck, text, "fish-req-${System.currentTimeMillis()}", lastBotId)
            }
            if (newId != null) lastBotId = newId
            lines.add(ChatLine(System.nanoTime(), reply, mine = false))
            saveChat(ctx, lines)
            sending = false
            runCatching { listState.animateScrollToItem((lines.size - 1).coerceAtLeast(0)) }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (lines.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "跟鱼世界里的你聊两句",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "阿墨在银杏里，你在这里说，那边都听得见",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(lines, key = { it.id }) { line ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (line.mine) Arrangement.End else Arrangement.Start,
                    ) {
                        Box(
                            Modifier
                                .widthIn(max = 280.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (line.mine) MaterialTheme.colorScheme.primary
                                    else appCardColor()
                                )
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Text(
                                line.text,
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                                color = if (line.mine) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
        if (sending) {
            Text(
                "阿墨正在想…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
            )
        }
        Row(
            // 输入框自己再抬一次：外层 Column 的 imePadding 在个别布局下不生效时，
            // 至少保证输入框和发送按钮不会被键盘挡住（发不出去就是这么来的）
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("说点什么…", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { send() },
                enabled = input.isNotBlank() && !sending && client != null && cookie != null,
                modifier = Modifier.size(52.dp),
            ) {
                Icon(Icons.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

// ---------------- 记录 ----------------

private data class HistRow(val text: String, val status: Boolean)
private data class HistTime(val time: String, val rows: List<HistRow>)
private data class HistDay(val day: String, val times: List<HistTime>)

private val histDayRe = Regex("^(?:(前天|昨天|今天)|(\\d{1,2}-\\d{1,2}\\s*周[一二三四五六日天]))\\s+(\\d{1,2})[：:](\\d{2})\\s+(.+)$")
private val histPlainRe = Regex("^(\\d{1,2})[：:](\\d{2})\\s+(.+)$")

// world_history 原文是时间正序（最早在上）。这里按「天 → 时刻」分组后整体倒序：
// 最新的天在最上面，同一天里最新的时刻在上，同一时刻内保持事情发生的先后。
private fun parseHistory(raw: String): List<HistDay> {
    data class Raw(val day: String, val time: String, val text: String)
    val raws = mutableListOf<Raw>()
    for (line0 in raw.lines()) {
        val trimmed = line0.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("——")) continue
        val text: String
        val day: String
        val time: String
        val m = histDayRe.find(trimmed)
        if (m != null) {
            day = m.groupValues[1].ifEmpty { m.groupValues[2].trim() }
            time = "%02d:%02d".format(m.groupValues[3].toIntOrNull() ?: 0, m.groupValues[4].toIntOrNull() ?: 0)
            text = stripEnglish(m.groupValues[5])
        } else {
            val p = histPlainRe.find(trimmed) ?: continue
            day = "今天"
            time = "%02d:%02d".format(p.groupValues[1].toIntOrNull() ?: 0, p.groupValues[2].toIntOrNull() ?: 0)
            text = stripEnglish(p.groupValues[3])
        }
        if (text.isEmpty()) continue
        raws.add(Raw(day, time, text))
    }
    if (raws.isEmpty()) return emptyList()

    val days = mutableListOf<HistDay>()
    var curDay = ""
    var curTime = ""
    var timeRows = mutableListOf<HistRow>()
    var timeList = mutableListOf<HistTime>()
    fun flushTime() {
        if (curTime.isNotEmpty() && timeRows.isNotEmpty()) timeList.add(HistTime(curTime, timeRows.toList()))
        curTime = ""
        timeRows = mutableListOf()
    }
    fun flushDay() {
        flushTime()
        if (timeList.isNotEmpty()) days.add(HistDay(curDay, timeList.toList()))
        timeList = mutableListOf()
    }
    for (r in raws) {
        if (r.day != curDay) {
            flushDay()
            curDay = r.day
        }
        if (r.time != curTime) {
            flushTime()
            curTime = r.time
        }
        timeRows.add(HistRow(r.text, r.text.startsWith("状态")))
    }
    flushDay()

    return days.reversed().map { d -> HistDay(d.day, d.times.reversed()) }
}

@Composable
private fun HistoryContent(raw: String) {
    val days = remember(raw) { parseHistory(raw) }
    if (days.isEmpty()) {
        EmptyState("还没有记录", "鱼世界里的动静会出现在这里")
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        days.forEach { day ->
            SectionTitle(day.day)
            AicodeCard {
                day.times.forEachIndexed { index, slot ->
                    if (index > 0) Spacer(Modifier.height(9.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            slot.time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.width(FishLayout.TimeWidth).padding(top = 3.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            slot.rows.forEachIndexed { rowIndex, row ->
                                if (rowIndex > 0) Spacer(Modifier.height(FishLayout.RowGap / 2))
                                Text(
                                    row.text.removePrefix("状态："),
                                    style = if (row.status) {
                                        MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp)
                                    } else {
                                        MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp)
                                    },
                                    color = if (row.status) {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(FishLayout.SectionGap))
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun mcp(url: String, token: String, name: String, argsJson: String): String {
    if (url.isBlank() || token.isBlank()) return "错误：未配置 MCP"
    return try {
        val body = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"$name","arguments":$argsJson}}"""
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("content-type", "application/json")
            .header("accept", "application/json, text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        fishClient.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) return "错误：HTTP ${r.code}"
            val o = try {
                JSONObject(s)
            } catch (_: Exception) {
                return "错误：返回不是 JSON（${s.take(80)}）"
            }
            val arr = o.optJSONObject("result")?.optJSONArray("content")
            if (arr == null || arr.length() == 0) return "错误：返回为空"
            arr.optJSONObject(0)?.optString("text") ?: "错误：没有文本内容"
        }
    } catch (e: Exception) {
        "错误：${e.message}"
    }
}
