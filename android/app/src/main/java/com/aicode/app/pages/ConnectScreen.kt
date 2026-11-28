package com.aicode.app.pages

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.aicode.app.data.ServerConfig
import com.aicode.app.ui.AicodeCard
import com.aicode.app.ui.AicodePrimaryButton
import com.aicode.app.ui.AicodeTheme
import androidx.activity.compose.rememberLauncherForActivityResult
import com.aicode.app.ui.AicodeTopBar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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
    var base by remember { mutableStateOf(TextFieldValue("")) }
    var altBase by remember { mutableStateOf(TextFieldValue(ServerConfig.baseAlt(ctx))) }
    var code by remember { mutableStateOf(TextFieldValue("")) }
    var secret by remember { mutableStateOf(TextFieldValue("")) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 扫码连接：二维码内容是「http://地址|密钥」，和连接页「粘贴连接信息」同一格式
    // （2026-11-26 用户要求「扫码就直接连」，不用手输地址和验证码）。
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result?.contents?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            val parts = raw.split("|")
            if (parts.size >= 2 && parts[0].trim().startsWith("http") && parts[1].trim().isNotEmpty()) {
                ServerConfig.save(ctx, normalizeBase(parts[0].trim()), parts[1].trim())
                ServerConfig.saveAlt(ctx, normalizeBase(altBase.text))
                onConnected()
            } else {
                error = "这个二维码不对。要包含「http://地址|密钥」。"
            }
        }
    }

    fun submit() {
        if (busy) return
        if (base.text.isBlank()) {
            error = "请填写服务器地址"
            return
        }
        if (pairMode && code.text.isBlank()) {
            error = "请填写验证码"
            return
        }
        if (!pairMode && secret.text.isBlank()) {
            error = "请填写访问凭证"
            return
        }
        val norm = normalizeBase(base.text)
        if (!pairMode) {
            // 高级入口不联网校验：存下来直接进主界面，连不通会在主界面显示离线。
            ServerConfig.save(ctx, norm, secret.text.trim())
            ServerConfig.saveAlt(ctx, normalizeBase(altBase.text))
            onConnected()
            return
        }
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { claimPair(norm, code.text.trim()) }
            busy = false
            when (result) {
                is PairResult.Ok -> {
                    ServerConfig.save(ctx, norm, result.secret)
                    ServerConfig.saveAlt(ctx, normalizeBase(altBase.text))
                    onConnected()
                }
                PairResult.BadCode -> {
                    error = "这个验证码不对，或者已经用过了。回服务器重新取一个。"
                    base = base.copy(selection = TextRange(0, base.text.length))
                    code = code.copy(selection = TextRange(0, code.text.length))
                }
                is PairResult.Fail -> {
                    error = "连不上这个地址。检查服务器开着没有、地址有没有写错。"
                    base = base.copy(selection = TextRange(0, base.text.length))
                    code = code.copy(selection = TextRange(0, code.text.length))
                }
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AicodeTopBar(title = "连接你的 AI", subtitle = "在你自己的服务器上")
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                AicodeCard {
                    FieldLabel("服务器地址")
                    OutlinedTextField(
                        value = base,
                        onValueChange = { base = it; error = null },
                        placeholder = { Text("http://<你的服务器IP>:3300") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    Spacer(Modifier.height(12.dp))
                    FieldLabel("备用地址（可选）")
                    OutlinedTextField(
                        value = altBase,
                        onValueChange = { altBase = it; error = null },
                        placeholder = { Text("主地址连不上时用，比如 http://100.x.x.x:3300") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    Text(
                        "填两个地址时，App 会自己探测：主地址通就用主地址，不通自动换备用。" +
                            "常见配法是「主=家里局域网，备用=Tailscale」。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    if (pairMode) {
                        FieldLabel("6 位数字验证码")
                        OutlinedTextField(
                            value = code,
                            onValueChange = { input ->
                                val digits = input.text.filter { it.isDigit() }.take(6)
                                // 过滤后长度变了就重建值（保持光标合法），否则保留原样
                                code = if (digits == input.text) input else TextFieldValue(digits)
                                error = null
                            },
                            placeholder = { Text("服务器给你的 6 位数字") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done,
                            ),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "在哪找？在服务器的部署目录里执行下面这条命令（或者直接问帮你部署的 AI）：\n" +
                                "docker compose exec aicode cat /data/pair-code",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FieldLabel("访问凭证")
                        OutlinedTextField(
                            value = secret,
                            onValueChange = { secret = it; error = null },
                            placeholder = { Text("服务器的访问凭证") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "在服务器的数据目录里（/data/secret），或者问帮你部署的 AI。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    AicodePrimaryButton(
                        text = if (busy) "正在连接…" else "连接",
                        loading = busy,
                        onClick = { submit() },
                    )
                    // 扫码直接连：不用手输地址和验证码（2026-11-26 用户要求）
                    TextButton(
                        onClick = {
                            error = null
                            runCatching {
                                scanLauncher.launch(
                                    ScanOptions()
                                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        .setPrompt("扫描 AIcode 连接二维码")
                                        .setBeepEnabled(false)
                                        .setOrientationLocked(true),
                                )
                            }.onFailure { error = "打不开相机：$it" }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("扫码连接", style = MaterialTheme.typography.labelMedium)
                    }
                    if (error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            error!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        TextButton(
                            onClick = {
                                pairMode = !pairMode
                                error = null
                            },
                        ) {
                            Text(
                                if (pairMode) "我有密钥（高级）" else "改用验证码",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        TextButton(
                            onClick = {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as? android.content.ClipboardManager
                                val raw = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                                if (raw.isEmpty()) {
                                    error = "没有可粘贴的内容"
                                } else {
                                    val parts = raw.split("|")
                                    if (parts.size >= 2 && parts[0].trim().startsWith("http") && parts[1].trim().isNotEmpty()) {
                                        ServerConfig.save(ctx, parts[0].trim(), parts[1].trim())
                                        ServerConfig.saveAlt(ctx, normalizeBase(altBase.text))
                                        onConnected()
                                    } else {
                                        error = "这段内容不对。要复制 AI 给你的整段（http://地址|密钥）。"
                                    }
                                }
                            },
                        ) {
                            Text("粘贴连接信息", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "连接后凭据只保存在这台手机里。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
                // 首次引导：没有服务器的人从这里去部署指南（UI-COPY 第6节）
                TextButton(
                    onClick = {
                        runCatching {
                            val i = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://cyyyourman.github.io/AICode/"),
                            )
                            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(i)
                        }
                    },
                    modifier = Modifier.padding(start = 2.dp, bottom = 24.dp),
                ) {
                    Text(
                        "还没有服务器？看怎么装",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
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
    return try {
        // url 构建也放 try 里：用户把地址写歪（叠字/缺端口）时 OkHttp 抛 IllegalArgumentException，
        // 在外面会直接崩掉整个协程（2026-11-26 真机踩到）。
        val request = Request.Builder().url(base + "/pair/claim").post(payload).build()
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
    is SocketTimeoutException -> "没响应。确认手机和服务器在同一个网络（或都开了 Tailscale）。"
    is UnknownHostException -> "找不到这个地址。看看 IP 有没有写错。"
    is ConnectException -> "连不上。确认地址后面的端口号没漏（默认 3300）。"
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
