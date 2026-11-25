# HTTPS / 外网访问指南

AIcode 默认假设你在**局域网或 Tailscale 内**使用。如果你想让手机在外面直接连服务器，
**不要**把 3300/3080 裸奔到公网（网关里虽然有 Bearer 鉴权，但明文 HTTP 会被嗅探）。

下面三种方式按推荐度排序。

## 方案 A：Tailscale（推荐，最省事）

1. 服务器和手机都装 [Tailscale](https://tailscale.com/)，登录同一账号；
2. 服务器上 `tailscale ip -4` 拿到地址（如 `100.x.x.x`）；
3. 手机 App 连接页填 `http://100.x.x.x:3300`，配对码 `cat /data/pair-code`。

优点：不开公网端口、自带加密、App 不用配证书。
缺点：手机要装 Tailscale 并常驻 VPN。

## 方案 B：Caddy 反向代理 + Let's Encrypt

前提：有域名、服务器 80/443 可访问。

```caddyfile
# /etc/caddy/Caddyfile
aicode.example.com {
    # DSH 引擎（Web UI / API / WebSocket）
    reverse_proxy 127.0.0.1:3080
}

gateway.example.com {
    # 网关（token / 配对 / 手机控制 / 更新）
    reverse_proxy 127.0.0.1:3300
}
```

```bash
sudo caddy reload --config /etc/caddy/Caddyfile
```

然后在 `.env` 里：

```
DSH_BASE=https://aicode.example.com
```

重启容器：`docker compose up -d`。

App 连接页填 `https://gateway.example.com`（注意：网关端口 443，不带 :3300）。

> WebSocket/SSE 由 Caddy 自动处理，不需要额外配置。

## 方案 C：Nginx

```nginx
server {
    listen 443 ssl http2;
    server_name aicode.example.com;
    ssl_certificate     /etc/letsencrypt/live/aicode.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/aicode.example.com/privkey.pem;
    location / {
        proxy_pass http://127.0.0.1:3080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
    }
}

server {
    listen 443 ssl http2;
    server_name gateway.example.com;
    ssl_certificate     /etc/letsencrypt/live/gateway.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/gateway.example.com/privkey.pem;
    location / {
        proxy_pass http://127.0.0.1:3300;
        proxy_set_header Host $host;
        client_max_body_size 200m;   # 文件上传
    }
}
```

## 常见问题

**App 报 `CLEARTEXT communication not permitted`？**
旧版（0.12.25 之前）有明文白名单限制；0.12.26+ 已放开，可填任意 http/https 地址。

**配对码在哪？**
`docker compose exec aicode cat /data/pair-code`。用一次就换新的。

**换了地址/域名后 App 连不上？**
设置页 →「重新配对」，用新地址 + 新配对码重来一次。

**证书是自签的可以吗？**
Android 默认不信任自签证书。要么用 Let's Encrypt（推荐），要么用 Tailscale。

## 安全检查清单

- [ ] 3300/3080 只监听回环或内网（`docker-compose.yml` 用 host 网络时注意防火墙）
- [ ] 公网入口只有 443，且走 HTTPS
- [ ] 配对码/密钥没有出现在任何公开日志、截图、issue 里
- [ ] 定期轮换：删掉 `/data/secret` 重启，App 重新配对
