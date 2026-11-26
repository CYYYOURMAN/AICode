# MCP 示例：接一个自定义工具服务

AIcode 的 App 支持把 **MCP（Model Context Protocol）服务**当工具用：对话里 AI 可以调用它们。
地址和密钥只保存在手机本地（设置 →「MCP」区块），不会上传到任何服务器。

## 配置（App 内）

1. 打开 App → 设置 → MCP 区块 →「配置 MCP」；
2. 填**地址**（Streamable HTTP 端点，通常以 `/mcp` 结尾）和**密钥**；
3. 打开「启用」开关；
4. 重新进入鱼世界页 / 对话即可生效。

## 案例：鱼世界（开发者自用）

鱼世界是开发者接的一个 MCP（世界/动态/名片/对话/记录），配置：

- 地址：`https://api.yuyuworld.app/v1/mcp`
- 密钥：在 https://yuyuworld.app/connect 获取（按页面提示复制）
- App 设置页有「填入鱼世界模板」按钮，一键填地址。

## 自己做一个 MCP 服务

MCP 是标准协议（JSON-RPC 2.0 over HTTP，Streamable HTTP 传输）。最小服务端要支持：

- `initialize`：握手、声明能力；
- `tools/list`：返回工具列表（名字、描述、参数 schema）；
- `tools/call`：执行工具、返回结果。

- 规范：https://modelcontextprotocol.io/specification
- 官方 SDK：TypeScript / Python（`pip install mcp`）。

起好服务后，把 `https://你的域名/mcp` 和访问密钥填进 App 即可。
**公网部署务必加鉴权（Bearer token）和 HTTPS**，否则任何人都能调用你的工具。
