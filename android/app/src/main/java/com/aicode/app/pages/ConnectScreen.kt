package com.aicode.app.pages

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aicode.app.data.ServerConfig
import com.aicode.app.ui.AicodeCard
import com.aicode.app.ui.AicodeTheme
import com.aicode.app.ui.AicodeTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 首次启动、或设置页点了「重新配对」之后的连接页。
 *
 * 主路径：填网关地址（3300 端口）→ `POST /pair/claim` 用配对码换密钥 → 存进 [ServerConfig]。
 * 备用路径（「我只想输入密钥」）：地址 + 管理员密钥直接保存，不联网校验，连不通在主界面报错。
 *
 * 服务端约定：配对成功返回 `{"ok":true,"secret":"..."}`，配对码不存在/用过返回 HTTP 401。
 */
@Composable
fun ConnectScreen(onConnected: () -> Unit) {
    // 未配置时 MainActivity 在 AicodeTheme 外面直接渲染本页，所以这里自己套一层主题；
    // 深色偏好从同一个 prefs 读，颜色跟设置页保持一致。
    val ctx = LocalContext.current.applicationContext
    val dark = remember {
        ctx.getSharedPreferences("aicode", Context.MODE_PRIVATE).getBoolean("dark", true)
    }
    AicodeTheme(dark = dark) {
        ConnectForm(ctx = ctx, onConnected = onConnected)
    }
}

@Composable
private fun ConnectForm(ctx: Context, onConnected: () -> Unit) {
    // true = 配对码模式（默认），false = 直接粘密钥（高级入口）
    var pairMode by remember { mutableStateOf(true) }
    var base by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        if (base.isBlank()) {
            error = "请填写服务器地址"
            return
        }
        if (pairMode && code.isBlank()) {
            error = "请填写配对码"
            return
        }
        if (!pairMode && secret.isBlank()) {
            error = "请填写密钥"
            return
        }
        val norm = normalizeBase(base)
        if (!pairMode) {
            // 高级入口不联网校验：存下来直接进主界面，连不通会在主界面显示离线。
            ServerConfig.save(ctx, norm, secret.trim())
            onConnected()
            return
        }
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { claimPair(norm, code.trim()) }
            busy = false
            when (result) {
                is PairResult.Ok -> {
                    ServerConfig.save(ctx, norm, result.secret)
                    onConnected()
                }
                PairResult.BadCode -> error = "配对码错误或已使用"
                is PairResult.Fail -> error = "连不上服务器：" + result.reason
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AicodeTopBar(title = "连接服务器", subtitle = "输入网关地址和配对码")
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(6.dp))
                AicodeCard {
                    FieldLabel("服务器地址")
                    OutlinedTextField(
                        value = base,
                        onValueChange = { base = it; error = null },
                        placeholder = { Text("http://<你的服务器IP>:3300") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    Spacer(Modifier.height(14.dp))
                    if (pairMode) {
                        FieldLabel("配对码")
                        OutlinedTextField(
                            value = code,
                            onValueChange = { input ->
                                code = input.filter { it.isDigit() }.take(6)
                                error = null
                            },
                            placeholder = { Text("6 位数字") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done,
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "配对码在服务器上：Docker 部署执行 docker compose exec aicode cat /data/pair-code，" +
                                "自建服务看部署时的数据目录。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FieldLabel("密钥")
                        OutlinedTextField(
                            value = secret,
                            onValueChange = { secret = it; error = null },
                            placeholder = { Text("服务器上的 token 服务密钥") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "密钥在服务器数据目录（/data/secret，或部署时配置）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { submit() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("连接中…")
                        } else {
                            Text("连接")
                        }
                    }
                    if (error != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            error!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(
                        onClick = {
                            pairMode = !pairMode
                            error = null
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(if (pairMode) "我只想输入密钥" else "改用配对码")
                    }
                    TextButton(
                        onClick = {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            val raw = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                            if (raw.isEmpty()) {
                                error = "剪贴板是空的"
                            } else {
                                val parts = raw.split("|")
                                if (parts.size >= 2 && parts[0].trim().startsWith("http") && parts[1].trim().isNotEmpty()) {
                                    ServerConfig.save(ctx, parts[0].trim(), parts[1].trim())
                                    onConnected()
                                } else {
                                    error = "连接信息格式不对（应为 服务器地址|密钥）"
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("从剪贴板导入连接")
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "连接成功后 App 用这份配置向服务器换取访问 token，密钥只保存在本机。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** 配对请求结果：成功带密钥 / 配对码不对 / 其它失败带原因。 */
private sealed class PairResult {
    class Ok(val secret: String) : PairResult()
    object BadCode : PairResult()
    class Fail(val reason: String) : PairResult()
}

/** 独立客户端：连接/读写各 8 秒，服务器不在家时不要卡界面太久。 */
private val pairClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()
}

/** `POST {base}/pair/claim`，body `{"code":"..."}`；只在 IO 线程调用。 */
private fun claimPair(base: String, code: String): PairResult {
    val payload = JSONObject().put("code", code).toString()
        .toRequestBody("application/json; charset=utf-8".toMediaType())
    val request = Request.Builder().url(base + "/pair/claim").post(payload).build()
    return try {
        pairClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.code == 401 -> PairResult.BadCode
                !response.isSuccessful -> PairResult.Fail("HTTP " + response.code)
                else -> {
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    val secret = json?.optString("secret").orEmpty()
                    if (json?.optBoolean("ok") == true && secret.isNotEmpty()) {
                        PairResult.Ok(secret)
                    } else {
                        PairResult.Fail("服务器返回格式不对")
                    }
                }
            }
        }
    } catch (e: IOException) {
        PairResult.Fail(friendlyError(e))
    } catch (e: Throwable) {
        PairResult.Fail(e.message ?: e.javaClass.simpleName)
    }
}

private fun friendlyError(e: IOException): String = when (e) {
    is SocketTimeoutException -> "连接超时，检查地址和网络"
    is UnknownHostException -> "找不到这个地址"
    is ConnectException -> "连不上，检查端口（默认 3300）"
    else -> e.message?.takeIf { it.isNotBlank() } ?: "网络错误"
}

/** 与 ServerConfig.save 内部的规范化保持一致：去空格、补 http://、去尾部斜杠。 */
private fun normalizeBase(raw: String): String {
    var base = raw.trim()
    if (base.isEmpty()) return ""
    val lower = base.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) base = "http://$base"
    return base.trimEnd('/')
}
