package com.aicode.app

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 本地回环转发：127.0.0.1:3080 -> 引擎（Tailscale 或局域网里的 3080）。
 * 目的：让 WebView 里的页面 origin 是回环地址，满足 DeepSeek 平台 OAuth 回调只认 127.0.0.1/localhost 的限制。
 * 纯 TCP 转发，对上层 HTTP/SSE 透明。
 */
object LocalProxy {
    private const val LISTEN_HOST = "127.0.0.1"
    private const val LISTEN_PORT = 3080

    /** bind 失败后重试次数（首次之外），每次间隔 300ms。 */
    private const val BIND_RETRIES = 3
    private const val RETRY_INTERVAL_MS = 300L

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var targetHost: String = ""
    @Volatile
    private var targetPort: Int = 0

    /** 最近一次绑定/转发失败的原因；绑定成功后清空。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 是否真的在监听：running 且 serverSocket 已绑定。 */
    fun healthy(): Boolean = running && serverSocket != null

    /** 启动（或切换目标）本地转发；已在跑且目标相同则忽略。 */
    fun start(host: String, port: Int) {
        if (running && host == targetHost && port == targetPort) return
        stop()
        targetHost = host
        targetPort = port
        lastError = null
        running = true
        val ready = java.util.concurrent.CountDownLatch(1)
        thread(name = "aicode-localproxy", isDaemon = true) {
            // bind 偶尔会在开机/连接切换瞬间失败（端口还没释放），重试几次再判死。
            var bound: ServerSocket? = null
            var last: String? = null
            for (attempt in 0..BIND_RETRIES) {
                if (!running) break
                val ss = ServerSocket()
                try {
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(LISTEN_HOST, LISTEN_PORT))
                    bound = ss
                    break
                } catch (e: Throwable) {
                    runCatching { ss.close() }
                    last = e.message ?: e.toString()
                    if (attempt < BIND_RETRIES) {
                        try {
                            Thread.sleep(RETRY_INTERVAL_MS)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }
            val ss = bound
            if (ss == null) {
                // stop() 主动取消时不算绑定失败，避免把「切目标」误报成错误。
                if (running) {
                    lastError = "绑定 " + LISTEN_HOST + ":" + LISTEN_PORT + " 失败：" + (last ?: "未知原因")
                    running = false
                }
                serverSocket = null
                ready.countDown()
                return@thread
            }
            serverSocket = ss
            lastError = null
            ready.countDown()
            try {
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (_: IOException) {
                        break
                    }
                    thread(name = "aicode-localproxy-conn", isDaemon = true) {
                        handle(client)
                    }
                }
            } catch (e: Throwable) {
                lastError = "本地转发异常：" + (e.message ?: e.toString())
            }
        }
        // 后台线程调用时等端口真正 bind 完再返回：否则紧随其后的 token 交换会打到还没监听的端口上，
        // 拿到空 cookie，之后所有引擎请求 401（文件页空白、鱼世界发送「未知错误」）。主线程那次（onCreate 预热）不等。
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            ready.await(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            upstream = Socket()
            upstream.connect(InetSocketAddress(targetHost, targetPort), 5000)
            val up = upstream
            val t1 = thread(isDaemon = true) { pump(client, up) }
            val t2 = thread(isDaemon = true) { pump(up, client) }
            t1.join()
            t2.join()
        } catch (_: Throwable) {
        } finally {
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
            runCatching { to.shutdownOutput() }
        } catch (_: Throwable) {
        }
    }
}
