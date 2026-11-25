# AIcode 部署指南（写给 AI）

你正在帮主人在一台 Linux 服务器上部署 AIcode：**手机 App + 服务器网关 + DSH 引擎**。
部署完成后，主人要在手机上装 App，用「服务器地址 + 配对码」连上来。

你只需要照做，不要改代码、不要改用例之外的组件。每一步都有验证方法，验证不过就停下问主人。

## 0. 前置检查
- 确认是 Linux（NAS 的 Linux 或云主机都行），`uname -a` 能跑；
- 确认有 Docker 和 docker compose v2：
  - `docker --version`、`docker compose version`；
  - 没有就先装：Debian/Ubuntu `apt install docker.io docker-compose-v2`；
- 确认 3300（网关）和 3080（引擎）端口没被占用：`ss -tlnp | grep -E ':3300|:3080'`；
  被占用就记下，后面在 `.env` 里改 `GATEWAY_PORT` / `DSH_PORT`；
- 向主人要一个模型 API key（DeepSeek 官方 key，`sk-` 开头）。

## 1. 下载部署包
```bash
SITE="https://CYYYOURMAN.github.io/AICode"   # 下载失败时，改用主人提供的镜像地址
mkdir -p ~/aicode && cd ~/aicode
curl -fsSL "$SITE/aicode-deploy.tar.gz" -o /tmp/aicode-deploy.tar.gz
tar xzf /tmp/aicode-deploy.tar.gz -C ~/aicode
ls   # 应看到 docker-compose.yml、.env.example、gateway/
解压后先跑一次体检（只读，不改任何东西）：

```bash
cd ~/aicode && bash preflight.sh
```

每项 `[OK]` 或 `[WARN]` 才继续；出现 `[FAIL]` 先按提示修（端口被占用就改 `.env` 的 `GATEWAY_PORT`/`DSH_PORT`；Docker 权限问题按提示把用户加入 docker 组）。

```
或者直接跑一键脚本（会做同样的事）：
```bash
curl -fsSL "$SITE/install.sh" | AICODE_SITE="$SITE" bash
```

## 2. 配置
```bash
cd ~/aicode
cp .env.example .env
```
编辑 `.env`：
- `DEEPSEEK_API_KEY=` 填主人给的 key；
- 端口被占用时改 `GATEWAY_PORT`（默认 3300）和 `DSH_PORT`（默认 3080）；
- 其余保持默认。

## 3. 启动
```bash
cd ~/aicode
docker compose up -d --build
```
第一次会构建镜像（下载 DSH 引擎，几分钟）。构建失败时看日志：`docker compose build 2>&1 | tail -30`。

## 4. 等待就绪 + 拿配对码
```bash
docker compose logs -f aicode   # 看到「配对码：XXXXXX」和「AIcode Gateway 监听 0.0.0.0:3300」即就绪
docker compose exec aicode cat /data/pair-code   # 配对码（6 位数字）
```

## 5. 验证（必须做）
```bash
# 健康检查，应返回 {"ok":true,...}
curl -s http://127.0.0.1:3300/health

# 配对码能换密钥（应返回 {"ok":true,"secret":"..."}）
curl -s -X POST http://127.0.0.1:3300/pair/claim -d '{"code":"<上一步的配对码>"}'
```
如果 `/health` 的 `ok` 是 false：看 `docker compose logs --tail=100 aicode`，常见是 DSH 还没打印 token（再等 30 秒）或端口冲突（改 `.env` 端口后 `docker compose up -d`）。

## 6. 交付给主人
告诉主人这两样（地址用服务器**局域网 IP**，例如 `http://192.168.1.10:3300`；如果主人在外网用，用 Tailscale IP 或公网安全部署）：
1. 服务器地址：`http://<服务器IP>:<GATEWAY_PORT>`
2. 配对码：`<6 位数字>`

主人在手机 App「连接服务器」页填这两样即可。

## 7. 失败回滚
```bash
cd ~/aicode && docker compose down -v
```
如果装错了目录，删掉 `~/aicode` 重来。不要动服务器上其他服务。

## 注意
- 不要改 `gateway/` 里的代码；更新时重新下载部署包并 `docker compose up -d --build`；
- 不要把 `.env`、配对码、密钥写到公开的地方；
- 端口只对内网开放即可；要公网访问，先让主人确认，再走 HTTPS 反向代理（见部署包里的 README）。
