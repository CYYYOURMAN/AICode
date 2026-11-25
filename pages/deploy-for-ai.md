# AIcode 部署指南（写给 AI）

你正在帮主人在一台 Linux 服务器上部署 AIcode：**手机 App + 服务器网关 + DSH 引擎**。
部署完成后，主人要在手机上装 App，用「服务器地址 + 配对码」连上来。

你只需要照做，不要改代码、不要改用例之外的组件。每一步都有验证方法，验证不过就停下问主人。

## 0. 前置检查
- 确认是 Linux（NAS 的 Linux 或云主机都行），`uname -a` 能跑；
- 确认有 Docker 和 docker compose v2：`docker --version`、`docker compose version`；
  没有就先装（Debian/Ubuntu）：`apt install docker.io docker-compose-v2`；
  当前用户要能用 docker：`docker info` 报权限错误时，`sudo usermod -aG docker $USER` 后重新登录；
- 确认 3300（网关）和 3080（引擎）端口没被占用：`ss -tlnp | grep -E ':3300|:3080'`；
  被占用就记下，后面在 `.env` 里改 `GATEWAY_PORT` / `DSH_PORT`（两个都要改，且不能相同）；
- 记下这台服务器的**局域网 IP**（`hostname -I` 的第一个地址，例如 `192.168.1.10`）——手机要连它；
- 向主人要一个模型 API key（DeepSeek 官方 key，`sk-` 开头）。

## 1. 下载部署包
```bash
SITE="https://cyyyourman.github.io/AICode"
DEST="${AICODE_DIR:-$HOME/aicode}"
mkdir -p "$DEST" && cd "$DEST"
curl -fsSL "$SITE/aicode-deploy.tar.gz" -o /tmp/aicode-deploy.tar.gz
tar xzf /tmp/aicode-deploy.tar.gz -C "$DEST"
ls   # 应看到 docker-compose.yml、.env.example、gateway/、preflight.sh
```

也可以跑一键脚本（只做下载、解压、体检，**不会**替你填 key，也**不会**自动启动）：
```bash
curl -fsSL "$SITE/install.sh" | AICODE_SITE="$SITE" bash
```

已有别的服务占用 3300/3080、或者想多开一份时，用独立目录和端口（一键脚本会写进 `.env`）：
```bash
curl -fsSL "$SITE/install.sh" | AICODE_SITE="$SITE" AICODE_DIR="$HOME/aicode-test" AICODE_GATEWAY_PORT=13300 AICODE_DSH_PORT=13080 bash
```
手动部署时，直接在 `$DEST/.env` 里改 `GATEWAY_PORT` / `DSH_PORT`（两个都改）。

先建好 `.env` 再跑体检（体检要读 `.env` 才知道最终端口；没有 `.env` 时端口检查会跳过）：
```bash
cd "$DEST" && ( [ -f .env ] || cp .env.example .env )
cd "$DEST" && bash preflight.sh
```

每项 `[OK]` 或 `[WARN]` 才继续；出现 `[FAIL]` 先按提示修（端口被占用就改 `.env` 的 `GATEWAY_PORT`/`DSH_PORT`；Docker 权限问题按提示把用户加入 docker 组）。

## 2. 配置
```bash
cd "$DEST"
cp .env.example .env
```
编辑 `.env`：
- `DEEPSEEK_API_KEY=` 填主人给的 key；
- 端口被占用时改 `GATEWAY_PORT`（默认 3300）和 `DSH_PORT`（默认 3080）；
- `DSH_HOST=` **留空**（启动时会自动探测服务器对外 IP）；不要填 `127.0.0.1`，否则手机连不上引擎；
  自动探测不对时，填服务器局域网 IP（例如 `192.168.1.10`）。手机走 Tailscale 时填服务器的
  Tailscale IP（`100.x.y.z`）——DSH 只能绑定一个具体地址，不能同时监听局域网和 Tailscale；
- 其余保持默认。

## 3. 启动
```bash
cd "$DEST"
docker compose up -d --build
```
第一次会构建镜像（下载 DSH 引擎，看网络约 5–15 分钟；几分钟没动静是正常的，别 Ctrl-C）。构建失败时看日志：`docker compose build 2>&1 | tail -30`，磁盘至少留 3GB：`df -h`。

## 4. 等待就绪 + 拿配对码
```bash
docker compose logs --tail=50 aicode
```
**不要加 `-f`**（会一直挂着）。先在 `$DEST` 里读端口，再对照日志：

```bash
GATEWAY_PORT=$(grep -E '^GATEWAY_PORT=' .env | cut -d= -f2); GATEWAY_PORT=${GATEWAY_PORT:-3300}
DSH_PORT=$(grep -E '^DSH_PORT=' .env | cut -d= -f2); DSH_PORT=${DSH_PORT:-3080}
```

看到「配对码：XXXXXX」和「监听 0.0.0.0:$GATEWAY_PORT」即就绪（端口按 `.env` 变）。

```bash
docker compose exec -T aicode cat /data/pair-code   # 配对码（6 位数字）
```
注意：**配对码是一次性的**，在 App 里配对成功后会自动轮换；再看时用上面的命令重新取。
网关的共享密钥在容器内 `/data/secret`（配对码就是用它生成的）。

## 5. 验证（必须做）
```bash
# 端口从 .env 读（换过端口也不会验错实例）
GATEWAY_PORT=$(grep -E '^GATEWAY_PORT=' .env | cut -d= -f2); GATEWAY_PORT=${GATEWAY_PORT:-3300}
DSH_PORT=$(grep -E '^DSH_PORT=' .env | cut -d= -f2); DSH_PORT=${DSH_PORT:-3080}

# 健康检查，应返回 {"ok":true,...}（优先用服务器局域网 IP，别用 127.0.0.1 以外的旧实例）
curl -s http://127.0.0.1:$GATEWAY_PORT/health

# 配对码能换密钥（应返回 {"ok":true,"secret":"..."}）
CODE=$(docker compose exec -T aicode cat /data/pair-code | tr -d '[:space:]')
SECRET=$(curl -s -X POST http://127.0.0.1:$GATEWAY_PORT/pair/claim -d "{\"code\":\"$CODE\"}" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("secret",""))')

# 带密钥调一个需要鉴权的接口，应返回 200；不带密钥应 401
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $SECRET" http://127.0.0.1:$GATEWAY_PORT/app/version
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:$GATEWAY_PORT/app/version

# 引擎端口应该监听在服务器局域网 IP 上（不是 127.0.0.1），手机才连得上
ss -tlnp | grep ":$DSH_PORT"
```

- `/health` 的 `ok` 是 false：看 `docker compose logs --tail=100 aicode`，常见是 DSH 还没打印 token（再等 30 秒）或端口冲突（改 `.env` 端口后 `docker compose up -d`）。
- `/app/version` 返回 200 但 `versionCode` 是 0：正常，这个部署没配置 App 发布通道（主人想要 App 更新功能时再配）。

**注意：上面的验证每 claim 一次，配对码就轮换一次。交付给主人前必须重新取：**

```bash
cd "$DEST" && docker compose exec -T aicode cat /data/pair-code
```

### 5.1 验证 AI 真的能用（有真实 key 时）

上面验的是网关和端口，**不能证明模型可用**。用真实 key 时补这一步：

```bash
docker compose exec -T aicode node -e "fetch('https://api.deepseek.com/models',{headers:{Authorization:'Bearer '+process.env.DEEPSEEK_API_KEY}}).then(r=>console.log('model api:',r.status)).catch(e=>console.log('model api: ERR',e.message))"
```

- 返回 `model api: 200` 才算真的能用（再在 App 里发一句确认）；
- `401` / `403`：key 错了，改 `.env` 后 `docker compose up -d`（会重建容器）；
- `ERR`：容器出不了网；
- 启动日志出现「连不上 api.deepseek.com（HTTP 000）」：也是出网问题（被拦 / DNS / 容器缺 CA）；
- **用假 key / 没填 key 时**：`/health` 绿、配对成功、`/app/version` 200 都正常，但一问模型就失败——**不算部署成功**。

### 5.2 从手机那一端验（客户端侧）

手机和服务器连同一网络后，**用手机浏览器**打开：

```
http://<服务器局域网 IP>:$GATEWAY_PORT/health
```

看到 `{"ok":true,...}` 才说明手机能到网关；看不到是网络/防火墙问题，不是 App 的问题。
再确认引擎端口监听在手机能访问的地址上：`ss -tlnp | grep ":$DSH_PORT"` 应显示局域网 IP（不是 `127.0.0.1`）。

## 6. 交付给主人

> 配对码是**一次性**的：第 5 节验证时已经用掉一个了。交付前务必按 5 节末尾的命令**重新取一次**，
> 且取完之后不要再拿它去 claim 或配对（否则这个码又在主人手里失效）。

告诉主人这三样：
1. 服务器地址：`http://<服务器局域网 IP>:$GATEWAY_PORT`（手机走 Tailscale 时换成 Tailscale IP）
2. 配对码：`<6 位数字>`
3. App 下载（已签名，可直接安装）：
   `https://github.com/CYYYOURMAN/AICode/releases/latest/download/aicode-latest.apk`
   下载慢时用 raw 直链（国内时通时断）：`https://raw.githubusercontent.com/CYYYOURMAN/AICode/main/download/aicode-latest.apk`
   （Release 里另有 unsigned 的 CI 构建，只给开发者自己签名用；普通用户用上面链接）

手机和服务器要在同一网络：同一局域网，或者**两边都连** Tailscale。
App 连的是**网关端口**（`$GATEWAY_PORT`）；引擎端口（`$DSH_PORT`）必须监听手机能访问到的地址
（上面的 `ss` 检查；默认是局域网 IP。走 Tailscale 时在 `.env` 里把 `DSH_HOST` 设成 Tailscale IP 再
`docker compose up -d`），否则 App 能配对但打不开页面。

## 7. 失败回滚
```bash
cd "$DEST" && docker compose down -v
```
**注意 `-v` 会删数据卷**（配对密钥、DSH 的数据）；只是重装/重启时**不要**加 `-v`，否则要重新配对、重建数据。
如果装错了目录，删掉 `$DEST` 重来。不要动服务器上其他服务。

## 遇到问题
先看 [常见问题](troubleshooting.md)：端口冲突、`/health` false、手机连不上引擎、模型 key 401、配对码一次性等。

## 注意
- 不要改 `gateway/` 里的代码；更新时重新下载部署包并 `docker compose up -d --build`；
- 不要把 `.env`、配对码、密钥写到公开的地方；
- 端口只对内网开放即可；要公网访问，先让主人确认，再走 HTTPS 反向代理（见仓库 `docs/HTTPS.md`）。
