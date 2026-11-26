# 常见问题（部署 + App）

## 部署

### docker compose up 失败 / 卡在构建
- 现象：构建几分钟没动静，或报错退出。
- 处理：`docker compose build 2>&1 | tail -30` 看最后几行；网络慢通常卡在 npm 下载，重试一次；
  磁盘要 ≥3GB：`df -h`。

### 端口被占用
- 现象：启动报 `address already in use`，或 `ss -tlnp | grep 3300` 看到别的服务。
- 处理：`.env` 里改 `GATEWAY_PORT` / `DSH_PORT`（两个都要改，且不能相同），再 `docker compose up -d`。

### /health 返回 ok:false
- 原因：DSH 还没打印 token（启动要 20–40 秒）、端口冲突、或 DSH 崩了。
- 处理：`docker compose logs --tail=100 aicode`；再等 30 秒重试；深入看 `/data/dsh.log`。

### 配对码无效
- 原因：配对码是**一次性**的，配对成功后会轮换。
- 处理：`docker compose exec -T aicode cat /data/pair-code` 重新取；长期密钥在 `/data/secret`。

### 配对成功，但 App 里一直「网络没连上 / 网页无法打开」
- 原因：`DSH_PORT` 改成了 3080 以外的端口，而 App **0.12.40 之前**把本地回环转发端口写死 3080
  （网页打开的是 `127.0.0.1:<引擎端口>`，端口对不上就加载失败）。配对和拿 token 都正常，所以很容易误判成网络问题。
- 处理：`.env` 里把 `DSH_PORT` 改回 `3080`，`docker compose up -d`；确实必须用别的端口时，
  先装 App **0.12.40 或更新**（beta 通道）再改。
- 判断方法：设置页「引擎版本 / Node.js」都显示「—」，加上网页打不开，基本就是这个。

### 手机能配对，但 App 打不开页面
- 原因：引擎端口只监听 `127.0.0.1`，手机连不上。
- 处理：`.env` 的 `DSH_HOST` 留空（自动探测）或填服务器局域网 IP；
  `ss -tlnp | grep 3080` 应显示局域网 IP 而不是 `127.0.0.1`。
- 用 Tailscale：`DSH_HOST` 填 Tailscale IP（`100.x.y.z`），手机也连 Tailscale。

### 启动日志提示「模型 key 无效」
- 现象：`[entrypoint] 警告：模型 key 无效（HTTP 401）`。
- 处理：检查 `.env` 的 `DEEPSEEK_API_KEY` 是否复制完整（`sk-` 开头、无空格）。
  连不上 api.deepseek.com 时只会提示跳过，不影响启动。

### 更新/重装后配对信息丢了
- `docker compose down -v` 会**删数据卷**（密钥、DSH 数据），不要随便加 `-v`。
- 重装后重新配对：`docker compose exec -T aicode cat /data/pair-code`。

## App

### 连接页连不上服务器
- 手机和服务器要在同一网络（同一局域网，或都连 Tailscale）；地址用 `http://服务器IP:3300`，不要用 `localhost`。
- 先用手机浏览器打开 `http://服务器IP:3300/health`，能看到 JSON 说明网络通。

### 鱼世界页空白 / 提示「未配置 MCP」
- 设置 → MCP 区块检查地址、密钥、启用开关；见 [MCP 示例](mcp-example.md)。

### 文件页空白 / 提示 401
- token 过期（引擎重启会换 token）：设置页「重新获取 token」；或切到网页版重新登录。

### 终端只能执行单条命令
- App 终端页是单命令快捷输入；**交互式终端在网页版**：新建会话 → 右侧栏「终端」（支持 bash / vim）。

### 手机控制不生效
- 控制走**稳定版** AIcode 的无障碍服务（测试版不轮询）：设置页「无障碍服务」→ 前往开启。
- 系统会隔一段时间自动关闭无障碍服务，重新打开即可。

### 通知不弹
- 系统通知权限没给：设置页「回合完成通知」→ 打开通知开关。
