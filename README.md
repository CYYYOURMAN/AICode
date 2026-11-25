# AIcode

**把一台自己的 Linux 服务器变成「AI 手机助手」。**
手机 App 连上你自己托管的 [DSH](https://github.com/deepseek-ai/deepseek-harness) 引擎：
能对话、读写文件、跑命令、操作手机——数据和服务全在你自己手里。

> 状态：**M0 骨架**（网关 + Docker 部署已能跑通；App 端「连接向导 / 去硬编码」在 M2）。

## 架构

```
┌─────────────┐   Bearer / 局域网或 HTTPS    ┌──────────────────────────────┐
│  手机 App    │ ───────────────────────────> │  AIcode Gateway (:3300)       │
│ (AIcode)    │ <─────────────────────────── │  ├─ token 下发 / 配对         │
└─────────────┘                              │  ├─ 手机控制命令队列           │
                                             │  ├─ App 更新通道 / 崩溃上报    │
                                             │  └─ /exec（默认关闭）          │
                                             └──────────────┬───────────────┘
                                                            │ 同容器 / host 网络
                                             ┌──────────────▼───────────────┐
                                             │  DSH 引擎 (:3080, MIT)        │
                                             │  对话 / 工具 / 工作区          │
                                             └──────────────────────────────┘
```

## 前置条件

- 一台 Linux 服务器（NAS / VPS / 小主机都行），装好 Docker 和 Docker Compose；
- 一个 DSH 能用的模型 API key（当前是 DeepSeek 官方 key）；
- 手机：Android 8.0+。

## 快速开始（5 分钟）

```bash
git clone <本仓库地址> aicode && cd aicode
cp .env.example .env
# 编辑 .env：填 DEEPSEEK_API_KEY（其余可默认）
docker compose up -d --build
```

第一次启动会构建镜像（下载 DSH 引擎），大约几分钟。起来后：

```bash
# 1) 看网关日志：会打印配对码（6 位数字）
docker compose logs -f aicode

# 2) 也可以直接读配对码 / 密钥（容器内）
docker compose exec aicode cat /data/pair-code
docker compose exec aicode cat /data/secret
```

- 网关：`http://你的服务器IP:3300`
- 引擎：`http://你的服务器IP:3080`
- 健康检查：`curl http://你的服务器IP:3300/health`

## 手机连接

1. 装 App（Releases 里的 APK，或自己构建 `app/`）；
2. 首次打开 → 填服务器地址（`http://服务器IP:3300`）→ 填配对码；
3. 连上后 App 会自动取 token，进入对话。

> 当前 M0 阶段 App 端的连接向导还在开发（M2），可以先用旧版 APK 手动配置。
> 配对接口已经就绪：`POST /pair/claim {"code":"123456"}` → `{"secret":"..."}`。

## 推荐的安全部署

**不要直接把 3300/3080 裸奔到公网。** 二选一：

1. **Tailscale（最省事）**：服务器和手机都装 Tailscale，App 地址填 `http://<tailscale-ip>:3300`，
   不暴露公网端口。
2. **反向代理 + HTTPS（Caddy 示例）**：

```
aicode.example.com {
    reverse_proxy 127.0.0.1:3080
}
gateway.example.com {
    reverse_proxy 127.0.0.1:3300
}
```

然后在 `.env` 里把 `DSH_BASE` 设为 `https://aicode.example.com`。

## 手机控制（可选）

`PHONE_ENABLED=1` 时，网关提供手机控制命令通道（`/phone/*`）。App 里需要手动开启
「无障碍服务」和「悬浮窗」权限；root 不是必需项。

## 远程命令执行（默认关闭）

文件页的删除/重命名/新建依赖 `/exec`（服务器上执行 shell）。开源版**默认关闭**：
`EXEC_ENABLED=0`。需要时在 `.env` 里打开，并理解这意味着「任何拿到网关密钥的人都能在服务器上执行命令」。

## 目录结构

```
open/
├── docker-compose.yml
├── .env.example
├── gateway/
│   ├── Dockerfile
│   ├── entrypoint.sh     # 起 DSH + 网关
│   └── gateway.py        # 网关（token/配对/手机控制/更新/崩溃）
├── SECURITY.md
└── README.md
```

## 路线图

- [x] M0：网关通用化骨架 + Docker 部署
- [ ] M1：网关配置化细节、容器验证
- [ ] M2：App 连接向导（去硬编码 IP/密钥）、连接诊断
- [ ] M3：文档 + 发版自动化 + GitHub Releases
- [ ] M4：社区化（issue 模板、示例 MCP、HTTPS 指南）

## License

MIT（DSH 引擎本身也是 MIT）。
