FROM node:24-slim

# 国内网络默认走镜像源；海外构建可覆盖：--build-arg APT_MIRROR=deb.debian.org --build-arg NPM_REGISTRY=https://registry.npmjs.org
ARG DSH_VERSION=0.2.2-rc.5
ARG APT_MIRROR=mirrors.ustc.edu.cn
ARG NPM_REGISTRY=https://registry.npmmirror.com

RUN sed -i "s|deb.debian.org|${APT_MIRROR}|g" /etc/apt/sources.list.d/debian.sources 2>/dev/null \
 || sed -i "s|deb.debian.org|${APT_MIRROR}|g" /etc/apt/sources.list || true

# ca-certificates 必须装：node:24-slim 不自带 CA，缺了它 curl 访问 https 会报 exit 77
# （000），entrypoint 的模型 key 验证会永远显示「连不上 api.deepseek.com」——真 key 也验不了。
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 curl tini ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN npm install -g --registry=${NPM_REGISTRY} @deepseek-ai/dsh@${DSH_VERSION}

COPY gateway.py /app/gateway.py
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 3300 3080

ENTRYPOINT ["/usr/bin/tini", "--", "/app/entrypoint.sh"]
