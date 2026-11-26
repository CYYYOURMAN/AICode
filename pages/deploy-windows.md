# AIcode 部署指南（Windows / WSL2）

> 这是 [deploy-for-ai.md](deploy-for-ai.md) 的 Windows 版开头。Windows 不能直接跑 Docker 的 host 网络，
> 所以先用 WSL2 造一台 Ubuntu，之后**完全照 Linux 版做**即可——不需要改任何部署文件。

## 0. 先确认能不能用这条路

在 PowerShell 里看版本：

```powershell
[System.Environment]::OSVersion.Version
# Build 22621 及以上 = Windows 11 22H2+，可以用「镜像网络」，推荐
```

- **Windows 11 22H2+（Build ≥ 22621）**：走下面的完整步骤，手机能直连，最省事。
- **Windows 10 / 更老的 Windows 11**：WSL2 默认是 NAT 网络，手机连不进来，要额外配端口转发（每次重启还可能变），
  不推荐。建议改用一台 Linux 机器 / VPS / NAS，或者装一台虚拟机。

## 1. 装 WSL2 + Ubuntu（管理员 PowerShell）

```powershell
wsl --install -d Ubuntu-24.04
```

装完**重启 Windows**，然后从开始菜单打开「Ubuntu 24.04」，按提示设一个 Linux 用户名和密码（记下来）。

检查：

```powershell
wsl -l -v
# 应该看到 Ubuntu-24.04，VERSION 是 2
```

> 如果没有 `wsl` 命令：先 `wsl --install`（不带发行版），装好重启再试。

## 2. 打开 WSL 的「镜像网络」（Windows 11 22H2+ 必须做）

在 Windows 用户目录建 `C:\Users\<你的用户名>\.wslconfig`，内容：

```ini
[wsl2]
networkingMode=mirrored
dnsTunneling=true
autoProxy=true
```

保存后**关闭所有 WSL 窗口**，再执行一次：

```powershell
wsl --shutdown
```

这样 WSL 里的服务会直接用 Windows 机的局域网 IP 和端口，手机才能连进来。

## 3. 在 WSL 里装 Docker

打开「Ubuntu 24.04」，执行：

```bash
sudo apt update
sudo apt install -y docker.io docker-compose-v2
sudo usermod -aG docker $USER
```

**关掉这个 Ubuntu 窗口再重新打开**（让 docker 组生效），然后验证：

```bash
docker --version && docker compose version
docker info >/dev/null && echo "docker 权限 OK"
```

## 4. 剩下的照 Linux 版做

从 Linux 版第 0 节的「确认 3300/3080 端口没被占用」开始，**原样照做**：

👉 https://cyyyourman.github.io/AICode/deploy-for-ai.md

注意这几点：

- 部署目录用 `~/aicode`（在 WSL 里），**不要**放到 `/mnt/c/...`（跨文件系统很慢，而且权限会乱）；
- 命令都在 WSL 的 Ubuntu 窗口里敲，不是在 PowerShell 里；
- 服务器 IP 用 **Windows 的局域网 IP**（`ipconfig` 里看，例如 `192.168.1.20`），不是 WSL 内部的 `172.x.x.x`；
- 手机和 Windows 要在同一网络（同一 WiFi，或两边都连 Tailscale）。

## 5. 验证手机能连

在 Windows 的 PowerShell 里：

```powershell
# 网关应该在监听，并且能被本机访问
curl.exe http://localhost:3300/health
```

正常会看到 `{"ok": true, ...}`。

然后按 Linux 版第 6 节交付给主人（优先给「一段可粘贴的连接信息」）。

如果手机连不上，按顺序查：

1. **Windows 防火墙**：第一次 Docker 启动会弹「允许访问」——要允许**专用网络**和**公用网络**；
   也可以手动放行：`New-NetFirewallRule -DisplayName "AIcode" -Direction Inbound -LocalPort 3300,3080 -Protocol TCP -Action Allow`（管理员 PowerShell）；
2. **WSL 镜像网络没生效**：`wsl --shutdown` 再重开，确认 `.wslconfig` 写的是 `networkingMode=mirrored`；
3. **手机和电脑不在同一网络**：先确认手机浏览器能打开 `http://<Windows IP>:3300/health`。

## 6. 常见问题

- **`wsl --install` 很慢或失败**：多半是 Windows 更新没开/虚拟化没开；在 BIOS 里打开
  Intel VT-x / AMD-V，然后 `wsl --update`。
- **`docker: permission denied`**：`sudo usermod -aG docker $USER` 之后必须**重新打开** Ubuntu 窗口。
- **重启 Windows 后服务没了**：WSL 里的容器不会自动启动。重新打开 Ubuntu，`cd ~/aicode && docker compose up -d` 即可；
  想开机自启，在 Windows 里加一个「任务计划程序 → 启动时运行 `wsl -d Ubuntu-24.04 -u <用户> -e bash -lc 'cd ~/aicode && docker compose up -d'`」。
- **端口被占用**：`netstat -ano | findstr :3300`（PowerShell 里 `Get-NetTCPConnection -LocalPort 3300`），
  改 `.env` 的 `GATEWAY_PORT`（`DSH_PORT` 保持 3080）。
