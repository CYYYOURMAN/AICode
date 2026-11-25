# 自定义 MCP 集成

AIcode 的「鱼世界」页是一个**直连 MCP 服务器**的示例：App 用 HTTP JSON-RPC 调
[MCP](https://modelcontextprotocol.io/) 工具，不依赖 DSH 引擎。

## MCP 是什么

MCP（Model Context Protocol）是「工具服务器」的标准协议：客户端发送 `tools/call`，
服务器返回工具结果。传输可以是 stdio 或 HTTP（Streamable HTTP）。

## App 里的示例（FishScreen）

代码位置：`android/app/src/main/java/com/aicode/app/pages/FishScreen.kt`

```kotlin
private const val MCP_URL = "https://api.yuyuworld.app/v1/mcp"

private fun mcp(token: String, name: String, argsJson: String): String {
    val body = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"$name","arguments":$argsJson}}"""
    val req = Request.Builder()
        .url(MCP_URL)
        .header("Authorization", "Bearer $token")
        .header("content-type", "application/json")
        .header("accept", "application/json, text/event-stream")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()
    ...
}
```

调用方式（`world_now` 是该 MCP 的一个工具）：

```
POST https://api.yuyuworld.app/v1/mcp
Authorization: Bearer <你的 token>
Content-Type: application/json
Accept: application/json, text/event-stream

{"jsonrpc":"2.0","id":2,"method":"tools/call",
 "params":{"name":"world_now","arguments":{"full":true}}}
```

返回在 `result.content[0].text`（通常是 JSON 字符串）。

## 换成你自己的 MCP

1. 把 `MCP_URL` 改成你的 MCP 服务器地址；
2. 把 token 来源从「DSH 的 fishToken」改成你自己的配置（例如在 `ServerConfig` 里加一个 `mcpToken`，
   或先写死在本地调试用）；
3. 把 `world_now` / `world_feed` 这些工具名换成你的 MCP 提供的工具；
4. 页面 UI 可以按你的数据结构改（FishScreen 是一个完整的模板：tab 切换、列表、发送）。

## 和 DSH 引擎侧 MCP 的区别

- **App 直连 MCP**（本文档）：适合「手机上一个独立的小面板」，和对话引擎无关。
- **DSH 引擎侧 MCP**：给 AI 对话提供工具（在 DSH 的 Settings → MCP 里注册），
  这样对话里的 agent 能调用你的 MCP。配置方式见 DSH 文档。

两者可以同时用：App 面板负责看/发，引擎侧负责让 AI 也能操作。
