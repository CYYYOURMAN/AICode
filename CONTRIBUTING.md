# 贡献指南

## 环境

- JDK 17 + Android SDK（`ANDROID_HOME` 指向 SDK 根目录）
- Gradle 8.9+（或直接用 Android Studio）
- Python 3.10+（网关，仅标准库）
- Docker（跑网关容器）

## 目录

```
gateway/   # 服务端网关（Python，标准库，无第三方依赖）
android/   # Android App（Kotlin + Compose）
docs/      # 部署 / MCP 等文档
```

## 构建

```bash
# App（输出 unsigned APK；自己签名见 README）
cd android && gradle assembleRelease

# 网关自检
python3 -m py_compile gateway/gateway.py
```

## 网关本地跑

```bash
GATEWAY_DATA_DIR=/tmp/aicode-data GATEWAY_SECRET=devsecret \
DSH_TOKEN_LOG=/tmp/dsh.log GATEWAY_PORT=3300 \
python3 gateway/gateway.py
# 另开一个终端验证
curl -s -H "Authorization: Bearer devsecret" http://127.0.0.1:3300/token
```

## 提交前

- [ ] App 能编译（`cd android && gradle assembleRelease`）
- [ ] 网关 `py_compile` 通过，改过的接口用 curl 验证
- [ ] **不提交**：密钥、token、配对码、个人 IP、个人路径、`local.properties`、`keystore.properties`
- [ ] 改了行为就同步 README / docs

## 提交信息

用简短的 imperative 风格，例如：

```
feat(gateway): add /pair/claim one-time code
fix(android): keep connection page after failed pair
docs: add https guide
```

## 发版

- 版本号在 `android/app/build.gradle.kts`（`versionCode` / `versionName`）。
- 打 tag（`v0.12.x`）会触发 GitHub Actions 构建 APK 并创建 Release。
- 自用的签名 APK 不要提交到仓库。
