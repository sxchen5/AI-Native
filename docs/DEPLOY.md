# 部署说明

本文说明 **Docker Compose 部署** 与 **非 Docker（Shell + Jar）部署**，以及 **Logback 日志**（按天滚动 `main.yyyyMMdd.log`）。

## 日志（Logback）

两个 Spring Boot 模块均包含 `src/main/resources/logback-spring.xml`：

| 项 | 说明 |
|----|------|
| 当前写入文件 | `{日志目录}/main.log` |
| 按天归档 | `{日志目录}/main.yyyyMMdd.log`（午夜滚动，保留约 30 天） |
| 日志目录 | 环境变量 **`LOGBACK_LOG_DIR`**（优先），否则 **`LOG_DIR`**，否则默认相对工作目录的 **`logs/`** |

示例：

```bash
export LOGBACK_LOG_DIR=/var/log/doubao-ai/chat-service
java -jar chat-service/target/chat-service-1.0.0-SNAPSHOT.jar
# 生成 /var/log/doubao-ai/chat-service/main.log 与 main.20260417.log ...
```

Docker 镜像内默认 **`LOGBACK_LOG_DIR=/app/logs`**，并通过 Compose **命名卷**挂载，便于 `docker volume inspect` 备份。

应用日志级别可通过环境变量 **`LOG_LEVEL_APP`**（默认 `INFO`）调整 `com.example.doubaoai` 包。

---

## 一、Docker 部署（推荐）

### 1.1 前置条件

- Docker 20.10+、`docker compose` v2
- 机器内存建议 ≥ 4GB（含 Nacos + 两个 Java + Nginx）

### 1.2 配置密钥

```bash
cp deploy/env.example .env
# 编辑 .env：至少设置 OPENAI_API_KEY，或启用智谱 ZHIPU_ENABLED=true 与 ZHIPU_API_KEY
```

### 1.3 启动 / 停止

```bash
chmod +x deploy/*.sh
./deploy/docker-up.sh          # 或: docker compose -f docker-compose.prod.yml --env-file .env up -d --build
./deploy/docker-down.sh
```

访问：

- 前端（Nginx 反代 `/api` 到网关）：`http://<主机>:${CHAT_UI_PORT:-80}`
- 网关直连（调试）：`http://<主机>:8080`

### 1.4 仅 Nacos（开发）

```bash
docker compose up -d
```

---

## 二、非 Docker 部署（Shell 脚本）

### 2.1 前置条件

- JDK 17+、Maven 3.9+
- 已启动 Nacos：`export NACOS_SERVER=127.0.0.1:8848`（默认）
- 在 Nacos 配置中心创建 `chat-service.yaml` / `chat-gateway.yaml`（参见 `docs/nacos/*.yaml`）

### 2.2 一键编译 + 后台启动

```bash
export OPENAI_API_KEY=你的密钥
export LOGBACK_LOG_DIR=/opt/doubao-ai/logs   # 可选
chmod +x deploy/start-local.sh deploy/stop-local.sh
./deploy/start-local.sh
```

进程与控制台输出：

- PID：`logs/pids/*.pid`
- `nohup` 控制台追加：`$LOGBACK_LOG_DIR/chat-service-console.log` 等

停止：

```bash
./deploy/stop-local.sh
```

### 2.3 无 Nacos（可选）

```bash
export SPRING_PROFILES_ACTIVE=no-nacos
./deploy/start-local.sh
```

---

## 三、仅打包（CI / 手工）

```bash
chmod +x deploy/build.sh
./deploy/build.sh
# 产物：chat-service/target/*.jar  chat-gateway/target/*.jar
```

---

## 四、前端生产构建

```bash
cd chat-ui
npm ci
npm run build
# dist/ 可由任意静态服务器托管；需将 /api 反向代理到网关 8080（参见 deploy/nginx/chat-ui.conf）
```

---

## 五、端口一览

| 服务 | 端口 | 说明 |
|------|------|------|
| chat-ui (Nginx) | 80（可改 `CHAT_UI_PORT`） | 静态页 + `/api` 反代 |
| chat-gateway | 8080 | 对外 API 入口 |
| chat-service | 8081 | 业务与 SSE |
| Nacos | 8848 | 注册与配置中心 |

---

## 六、常见问题

1. **Nacos 未就绪导致 chat-service 启动失败**  
   `docker-compose.prod.yml` 已对 `nacos` 配置 `healthcheck`，`chat-service` 依赖 `service_healthy`。

2. **SSE 超时**  
   网关 `response-timeout` 已在 `application.yml` 配置；Tomcat 连接超时见 `chat-service` 的 `server.tomcat.connection-timeout`。

3. **日志目录无写权限**  
   确保 `LOGBACK_LOG_DIR` 对运行用户可写；Docker 下卷挂载目录权限与宿主 UID 一致时可调整 `user` 或使用命名卷（当前 compose 使用命名卷）。
