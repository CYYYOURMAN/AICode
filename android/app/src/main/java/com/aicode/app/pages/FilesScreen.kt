package com.aicode.app.pages

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.app.R
import com.aicode.app.data.EngineClient
import com.aicode.app.ui.AicodeCard
import com.aicode.app.ui.AicodeTopBar
import com.aicode.app.ui.EmptyState
import com.aicode.app.ui.LocalIsDark
import com.aicode.app.ui.appCardColor
import com.aicode.app.ui.appLineColor
import com.aicode.app.ui.humanSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class FileEntry(val name: String, val isDir: Boolean, val size: Long)

private fun rpc(method: String, rpcId: String, args: JSONObject): JSONObject = JSONObject()
    .put("type", "client-request")
    .put("rpcId", rpcId)
    .put("method", method)
    .put("payload", JSONObject().put("args", args))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(client: EngineClient?, cookie: String?) {
    var scopeId by remember { mutableStateOf<String?>(null) }
    var workspaceRoot by remember { mutableStateOf("") }
    var path by remember { mutableStateOf(".") }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    var menuFor by remember { mutableStateOf<FileEntry?>(null) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<FileEntry?>(null) }
    var creatingDir by remember { mutableStateOf(false) }
    var newDirName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && client != null && cookie != null && scopeId != null) {
            scope.launch {
                notice = "上传中…"
                notice = withContext(Dispatchers.IO) {
                    uploadFile(ctx, client, cookie, scopeId!!, uri)
                }
                refreshKey++
            }
        }
    }

    LaunchedEffect(client, cookie) {
        // cookie 为空也照常请求：EngineClient 内部会用最近一次换到的 cookie 兜底、必要时重换，
        // 冷启动那一下没换到 cookie 时，页面也不会一直空白。
        if (client == null) {
            loading = false
            err = "正在连接引擎…"
            return@LaunchedEffect
        }
        val res = withContext(Dispatchers.IO) {
            client.post(cookie, "/api/session/list", rpc("session/list", "files-sessions", JSONObject().put("_request", JSONObject())))
        }
        val items = res?.optJSONObject("result")?.optJSONObject("value")?.optJSONArray("items")
        if (items != null) {
            var picked: String? = null
            var pickedRunning = false
            var pickedCwd = ""
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val id = o.optString("sessionId")
                if (id.isEmpty()) continue
                // 只跳过引擎没给工作目录的会话；不假设服务器路径长什么样。优先正在运行的会话。
                val cwd = o.optString("cwd")
                if (cwd.isBlank()) continue
                val running = o.optBoolean("running", false)
                if (picked == null || (running && !pickedRunning)) {
                    picked = id
                    pickedRunning = running
                    pickedCwd = cwd
                }
                if (running) break
            }
            if (picked != null) {
                scopeId = picked
                workspaceRoot = pickedCwd
            } else {
                loading = false
                err = "没有可用的会话工作区"
            }
        } else {
            loading = false
            err = "读取会话列表失败"
        }
    }

    LaunchedEffect(scopeId, path, refreshKey, client, cookie) {
        if (client == null || scopeId == null) return@LaunchedEffect
        loading = true
        err = ""
        val res = withContext(Dispatchers.IO) {
            client.post(
                cookie,
                "/api/workspaceFiles/list",
                rpc("workspaceFiles/list", "files-list", JSONObject()
                    .put("workspaceFileScopeId", scopeId!!)
                    .put("path", path)),
            )
        }
        val value = res?.optJSONObject("result")?.optJSONObject("value")
        if (value == null) {
            err = res?.optJSONObject("result")?.optJSONObject("error")?.optString("message")
                ?: "读取目录失败"
            entries = emptyList()
        } else {
            val arr = value.optJSONArray("entries")
            val list = mutableListOf<FileEntry>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(
                        FileEntry(
                            name = o.optString("name"),
                            isDir = o.optString("type") == "directory",
                            size = o.optLong("size", 0L),
                        )
                    )
                }
            }
            entries = list.sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
        }
        loading = false
    }

    val shown = if (query.isBlank()) entries else entries.filter { it.name.contains(query, ignoreCase = true) }

    Column(Modifier.fillMaxSize()) {
        AicodeTopBar(
            title = "文件",
            subtitle = if (path == ".") workspaceRoot.ifBlank { "工作区" } else path,
        ) {
            IconButton(onClick = { refreshKey++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新文件列表")
            }
            IconButton(onClick = {
                newDirName = ""
                creatingDir = true
            }) {
                Icon(Icons.Filled.Create, contentDescription = "新建文件夹")
            }
            IconButton(onClick = { launcher.launch("*/*") }, enabled = scopeId != null) {
                Icon(Icons.Filled.Add, contentDescription = "上传文件")
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("搜索文件…", style = MaterialTheme.typography.bodySmall) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "清除", modifier = Modifier.size(16.dp))
                    }
                }
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = appCardColor(),
                unfocusedContainerColor = appCardColor(),
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                unfocusedBorderColor = appLineColor(),
            ),
        )

        if (path != ".") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    path = if (path.contains('/')) path.substringBeforeLast('/') else "."
                }) {
                    Text("← 上一级", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
            }
        }

        if (notice.isNotEmpty()) {
            Text(
                notice,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        Box(Modifier.weight(1f)) {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(26.dp),
                    strokeWidth = 2.5.dp,
                )
                err.isNotEmpty() -> EmptyState("读不到目录", err)
                shown.isEmpty() -> EmptyState(
                    if (query.isBlank()) "这个目录是空的" else "没有匹配的文件",
                    if (query.isBlank()) "用右上角 + 上传文件" else null,
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
                ) {
                    items(shown, key = { it.name + (if (it.isDir) "/" else "") }) { entry ->
                        FileRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDir) {
                                    path = if (path == ".") entry.name else "$path/${entry.name}"
                                } else {
                                    val full = if (path == ".") entry.name else "$path/${entry.name}"
                                    scope.launch {
                                        val content = withContext(Dispatchers.IO) {
                                            readFile(client, cookie, scopeId, full)
                                        }
                                        preview = entry.name to content
                                    }
                                }
                            },
                            menuOpen = menuFor?.name == entry.name,
                            onMore = { menuFor = entry },
                            onDismissMenu = { menuFor = null },
                            onRename = {
                                renameText = entry.name
                                renaming = entry
                                menuFor = null
                            },
                            onDelete = {
                                deleting = entry
                                menuFor = null
                            },
                        )
                    }
                }
            }
        }
    }

    val currentDir = when {
        path == "." -> workspaceRoot
        workspaceRoot.isBlank() -> path
        else -> "$workspaceRoot/$path"
    }

    fun shellQuote(v: String): String = "'" + v.replace("'", "'\\''") + "'"

    fun shellErr(out: String): String = when {
        out.contains("exec disabled") -> "服务器未启用 exec（部署文档里设 EXEC_ENABLED=1）"
        out.contains("No such file or directory") -> "路径不存在"
        out.contains("Permission denied") -> "权限不足"
        else -> out
    }

    fun childPath(base: String, name: String): String = if (base.isBlank()) name else "$base/$name"

    fun runShellCmd(cmd: String): Pair<Int, String> = try {
        val conn = java.net.URL(com.aicode.app.data.ServerHost.httpBase() + "/exec")
            .openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", com.aicode.app.data.ServerHost.authHeader())
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        conn.outputStream.use { it.write(org.json.JSONObject().put("cmd", cmd).toString().toByteArray()) }
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val o = org.json.JSONObject(text)
        Pair(o.optInt("code", -1), o.optString("output", ""))
    } catch (e: Throwable) {
        Pair(-1, e.message ?: "网络错误")
    }

    if (creatingDir) {
        AlertDialog(
            onDismissRequest = { creatingDir = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = newDirName,
                    onValueChange = { newDirName = it },
                    singleLine = true,
                    label = { Text("文件夹名") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newDirName.trim()
                    if (name.isNotEmpty()) {
                        creatingDir = false
                        scope.launch {
                            val (code, out) = withContext(Dispatchers.IO) {
                                runShellCmd("mkdir -p " + shellQuote(childPath(currentDir, name)))
                            }
                            notice = if (code == 0) "已新建文件夹 $name" else "新建失败：${shellErr(out)}"
                            refreshKey++
                        }
                    }
                }) {
                    Text("创建")
                }
            },
            dismissButton = { TextButton(onClick = { creatingDir = false }) { Text("取消") } },
        )
    }

    val renamingEntry = renaming
    if (renamingEntry != null) {
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("新名字") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = renameText.trim()
                    if (name.isNotEmpty() && name != renamingEntry.name) {
                        renaming = null
                        scope.launch {
                            val (code, out) = withContext(Dispatchers.IO) {
                                runShellCmd(
                                    "if [ -e " + shellQuote(childPath(currentDir, name)) + " ]; then echo 目标已存在; exit 1; fi; " +
                                        "mv " + shellQuote(childPath(currentDir, renamingEntry.name)) + " " + shellQuote(childPath(currentDir, name)),
                                )
                            }
                            notice = if (code == 0) "已重命名为 $name" else "重命名失败：${shellErr(out)}"
                            refreshKey++
                        }
                    } else {
                        renaming = null
                    }
                }) {
                    Text("重命名")
                }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }

    val deletingEntry = deleting
    if (deletingEntry != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除") },
            text = { Text("确定删除「${deletingEntry.name}」吗？删除后不能恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        val (code, out) = withContext(Dispatchers.IO) {
                            runShellCmd("rm -rf " + shellQuote(childPath(currentDir, deletingEntry.name)))
                        }
                        notice = if (code == 0) "已删除 ${deletingEntry.name}" else "删除失败：${shellErr(out)}"
                        refreshKey++
                    }
                }) {
                    Text("删除")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }

    val pv = preview
    if (pv != null) {
        PreviewSheet(
            name = pv.first,
            content = pv.second,
            saving = saving,
            onDismiss = { preview = null },
            onSave = {
                saving = true
                scope.launch {
                    val msg = withContext(Dispatchers.IO) {
                        saveToDownloads(ctx, pv.second, "aicode-${pv.first}")
                    }
                    notice = msg
                    saving = false
                    preview = null
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewSheet(
    name: String,
    content: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val scroll = rememberScrollState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "预览 · 前 4000 字符",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onSave, enabled = !saving) { Text("保存到手机") }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = appLineColor())
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .verticalScroll(scroll),
            ) {
                Text(
                    content,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    onClick: () -> Unit,
    menuOpen: Boolean,
    onMore: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (entry.isDir) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else appCardColor())
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (entry.isDir) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (entry.isDir) R.drawable.ic_tab_files else R.drawable.ic_file),
                contentDescription = null,
                tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDir && entry.size > 0) {
                Text(
                    humanSize(entry.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(onClick = onMore, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多操作",
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(text = { Text("重命名") }, onClick = onRename)
                DropdownMenuItem(text = { Text("删除") }, onClick = onDelete)
            }
        }
    }
}

private fun readFile(client: EngineClient?, cookie: String?, scopeId: String?, path: String): String {
    if (client == null || scopeId == null) return "引擎未连接"
    return try {
        val payload = rpc("workspaceFiles/read", "read-1", JSONObject()
            .put("workspaceFileScopeId", scopeId)
            .put("path", path)
            .put("range", JSONObject().put("offset", 1).put("length", 8000)))
        val res = client.post(cookie, "/api/workspaceFiles/read", payload)
        val v = res?.optJSONObject("result")?.optJSONObject("value")
        v?.optString("text")?.take(4000) ?: (res?.toString()?.take(300) ?: "读取失败")
    } catch (e: Exception) {
        "错误: ${e.message}"
    }
}

private fun uploadFile(
    ctx: android.content.Context,
    client: EngineClient,
    cookie: String,
    scopeId: String,
    uri: Uri,
): String {
    return try {
        val input = ctx.contentResolver.openInputStream(uri) ?: return "无法读取文件"
        val bytes = input.readBytes()
        input.close()
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val payload = rpc("fileUploads/upload", "upload-1", JSONObject()
            .put("agentId", scopeId)
            .put("request", JSONObject().put("fileName", name).put("data", b64)))
        val res = client.post(cookie, "/api/fileUploads/upload", payload)
        val value = res?.optJSONObject("result")?.optJSONObject("value")
        if (value != null) "已上传：${value.optJSONObject("file")?.optString("name")} (${bytes.size} 字节)"
        else "上传失败：${res?.toString()?.take(200)}"
    } catch (e: Exception) {
        "上传错误: ${e.message}"
    }
}

private fun saveToDownloads(ctx: android.content.Context, content: String, name: String): String {
    return try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "无法创建文件"
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
            "已保存到下载：$name"
        } else {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS)
            val f = java.io.File(dir, name)
            f.writeText(content)
            "已保存：${f.absolutePath}"
        }
    } catch (e: Exception) {
        "保存失败: ${e.message}"
    }
}
