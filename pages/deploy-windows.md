# AIcode 部署指南（Windows / Docker Desktop）

> Windows 版和 Linux 版的差别只有两处：**用 Docker Desktop**、**用 `docker-compose.windows.yml`**（bridge 网络 + 端口映射）。
> 其余步骤（配 key、拿配对码、验证、交付）和 [Linux 版](deploy-for-ai.md) 完全一样。
>
> **已在 Windows 10 22H2 + Docker Desktop 4.99 上实机验证过**（2026-11-26）：
> 装 Docker → 配镜像加速 → 构建 → 容器起来 → 手机配对 → App 打开网页 → 发消息得到 AI 回复，全流程跑通。
> 本文下面标「必做」的地方都是那次踩出来的，跳过会卡住。

## 0. 前提

- **Windows 10 或 11**（64 位）；
- 管理员权限（装 Docker Desktop 要用）；
- 能开 WSL2 或 Hyper-V（Docker Desktop 的后端）。Win10 家庭版用 WSL2 后端即可。

## 1. 装 Docker Desktop

```powershell
winget install -e --id Docker.DockerDesktop
```

装完**重启 Windows**，然后打开 Docker Desktop，等左下角显示 **Engine running**。

验证（PowerShell）：

```powershell
docker --version
docker compose version
docker info >$null; if ($LASTEXITCODE -eq 0) { "docker OK" }
```

> 如果 `docker` 命令找不到：重开一个 PowerShell 窗口（PATH 要刷新）。
> 如果 Docker Desktop 报 WSL2 没装：先在管理员 PowerShell 里 `wsl --install`，重启，再开 Docker Desktop。

**确认用的是 Linux 容器**：Docker Desktop 右下角/设置里，容器类型要是 `Linux`（Windows 容器的镜像跑不了这个部署包）。

> Docker Desktop 装完**不会自动启动**。要手动打开（桌面图标或开始菜单），等左下角绿了再往下做。

## 1.1 配镜像加速（国内必做，不配必失败）

Docker Hub 在国内基本拉不动。不配这个，`up --build` 会卡在拉 `node:24-slim`：

```
failed to resolve source metadata for docker.io/library/node:24-slim ... dial tcp ... i/o timeout
```

配法：**右键托盘里的 Docker 图标 → Settings → Docker Engine**，把 JSON 改成：

```json
{
  "registry-mirrors": ["https://docker.m.daocloud.io", "https://dockerproxy.com"]
}
```

点 **Apply & restart**，等引擎重新变绿。验证：

```powershell
docker pull node:24-slim
# 能下载完、最后出现 "Status: Downloaded newer image for node:24-slim" 就行
```

## 1.2 Docker Desktop 卡在启动 / 一直转圈

国内网络下它启动时会去连 `hub.docker.com` 拉配置，超时就不动了（日志里能看到 `fetching features from remote https://hub.docker.com/...`）。
临时办法（管理员 PowerShell）：

```powershell
Add-Content C:\Windows\System32\drivers\etc\hosts "127.0.0.1 hub.docker.com"
```

然后右键托盘图标 **Restart**。启动成功后把这个 hosts 行删掉也行（留着不影响部署，只是 Docker 的在线功能用不了）。

> 2026-11-26 在 Windows 10 22H2 + Docker Desktop 4.99 上实测：**这两条都是必须的**，否则引擎起不来 / 镜像拉不下来。

## 2. 下载部署包

```powershell
$SITE = "https://cyyyourman.github.io/AICode"
$DEST = "$HOME\aicode"
New-Item -ItemType Directory -Force -Path $DEST | Out-Null
curl.exe -fsSL "$SITE/aicode-deploy.tar.gz" -o "$env:TEMP\aicode-deploy.tar.gz"
tar -xzf "$env:TEMP\aicode-deploy.tar.gz" -C $DEST
cd $DEST
dir
```

应看到：`docker-compose.yml`、`docker-compose.windows.yml`、`.env.example`、`gateway\`、`preflight.sh`、`README-DEPLOY.md`。

## 3. 配置

```powershell
cd $DEST
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
notepad .env
```

改这几项：

- `DEEPSEEK_API_KEY=` 填你的 key（`sk-` 开头）；
- `GATEWAY_PORT` 默认 3300、`DSH_PORT` 默认 3080 —— **端口被占用才改**，且优先只改 `GATEWAY_PORT`（`DSH_PORT` 保持 3080）；
- `DSH_HOST=` **留空**（Windows 版是端口映射，容器内自动用容器 IP）。

## 4. 启动（注意是 windows 那份）

```powershell
cd $DEST
docker compose -f docker-compose.windows.yml up -d --build
```

第一次会构建镜像（拉 DSH 引擎），看网络约 5–15 分钟。

## 5. 验证

> 第一次 `up --build` 后 DSB 引擎要 20–40 秒才打印 token，`/health` 之前可能是 `ok: false`，等一下再试。


```powershell
# 端口应该是 0.0.0.0:3300 / 0.0.0.0:3080（Docker 的端口映射）
Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in 3300,3080 } | Select-Object LocalAddress,LocalPort

# 网关健康检查
curl.exe http://localhost:3300/health
# 应返回 {"ok": true, "port": true, ...}；刚启动 15 秒内可能 ok:false，等一会儿重试

# 看日志（不要加 -f）
docker compose -f docker-compose.windows.yml logs --tail=50 aicode
# 应看到「配对码：XXXXXX」「监听 0.0.0.0:3300」「模型 key 验证通过」
```

取配对码：

```powershell
docker compose -f docker-compose.windows.yml exec -T aicode cat /data/pair-code
```

**Windows 上跳过 `preflight.sh`**（它是 bash 脚本，要靠 Git Bash 才能跑）；它的检查项上面已经覆盖了。

## 6. 防火墙（第一次启动会弹窗）

Docker Desktop 第一次发布端口时，Windows 会弹「允许访问」。**要把「专用网络」和「公用网络」都勾上**。

没弹或者点错了，用管理员 PowerShell 补一条：

```powershell
New-NetFirewallRule -DisplayName "AIcode" -Direction Inbound -LocalPort 3300,3080 -Protocol TCP -Action Allow
```

## 7. 交付给主人

和 Linux 版第 6 节一样：

1. **先装 App**（Android，签名版）：
   - 首选 raw 直链：`https://raw.githubusercontent.com/CYYYOURMAN/AICode/main/download/aicode-latest.apk`
   - 备用：`https://github.com/CYYYOURMAN/AICode/releases/latest/download/aicode-latest.apk`
2. **给一段可粘贴的连接信息**（最省事）：

```powershell
$port = (Select-String -Path .env -Pattern '^GATEWAY_PORT=(\d+)').Matches.Groups[1].Value
if (-not $port) { $port = "3300" }
$secret = (docker compose -f docker-compose.windows.yml exec -T aicode cat /data/secret) -join "" -replace '\s',''
$ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '172.*' -and $_.IPAddress -notlike '169.254.*' } | Select-Object -First 1).IPAddress
"http://$ip`:$port|$secret"
```

把输出那一行发给主人（提醒他这段含密钥，别转发），他在 App 连接页点「从剪贴板导入连接」即可。

3. 手机和电脑要在同一网络（同一 WiFi，或两边都连 Tailscale）。

## 8. 常见问题

- **构建卡住 / 拉不到依赖**：Docker Desktop 设置里给 Docker 配代理，或者重试一次；磁盘至少留 3GB。
- **`docker compose up` 报端口被占用**：`Get-NetTCPConnection -LocalPort 3300` 看是谁；改 `.env` 的 `GATEWAY_PORT`（优先只改这个）。
- **手机连不上**：先确认手机浏览器能打开 `http://<电脑IP>:3300/health`；打不开就查防火墙（第 6 节）和「手机与电脑是否同一网络」。
- **重启 Windows 后服务没起来**：Docker Desktop 默认不会自启容器。打开 Docker Desktop，再
  `cd $DEST; docker compose -f docker-compose.windows.yml up -d`；
  想让 Docker Desktop 开机自启：设置里勾「Start Docker Desktop when you sign in」。
- **`/health` 的 ok 是 false**：等 30 秒重试；还不行看日志里 DSH 有没有打印 token。
