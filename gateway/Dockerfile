FROM node:22-slim

ARG DSH_VERSION=0.2.2-rc.5

RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 curl tini \
 && rm -rf /var/lib/apt/lists/*

RUN npm install -g @deepseek-ai/dsh@${DSH_VERSION}

COPY gateway.py /app/gateway.py
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 3300 3080

ENTRYPOINT ["/usr/bin/tini", "--", "/app/entrypoint.sh"]
