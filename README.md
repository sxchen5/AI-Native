# 类豆包 AI 智能聊天系统（Spring AI 2.0 + Vue3）

本项目提供一套可运行的前后端完整示例：**微服务网关 + 聊天服务 + Nacos 注册/配置 + Vue3 类豆包布局**，并使用 **SSE（`text/event-stream`）** 实现流式“打字机”输出。

## 技术栈

- **后端**：Spring Boot `3.3.6`、Spring Cloud `2024.0.3`、Spring Cloud Alibaba `2023.0.3.4`（Nacos）、Spring AI `2.0.0-M4`（OpenAI Java SDK 集成路径）、**Spring Security（Session + 图片验证码登录）**、Java `17+`
- **前端**：Vue `3` + Vite、Element Plus、Pinia、Vue I18n（中/英）、主题深/浅切换、Axios + `fetch` SSE 流式、`marked` + `highlight.js` AI 消息渲染
- **AI 协议**：OpenAI 官方 Java SDK（`openai-java`）+ Spring AI `OpenAiSdkChatModel` + `ChatClient`（OpenAI 兼容 HTTP 协议，可切换对接 **OpenAI / 通义千问兼容模式 / 百度千帆 OpenAI 兼容**）

> 说明：`spring-ai-starter-model-openai` / `spring-ai-starter-model-openai-sdk` 在 `2.0.0-M4` 的 POM 中会固定依赖 **Spring Boot `4.1.x`**，与“Boot `3.3.x`”要求冲突。为保证可运行，本项目改为引入 **`spring-ai-openai-sdk` + `spring-ai-autoconfigure-model-openai-sdk`（仅用于 `@ConfigurationProperties`）** 并 **手动装配** `OpenAiSdkChatModel` / `ChatClient`（见 `OpenAiManualConfiguration`）。

> 说明：Spring Cloud `2024.0.x` 自带的兼容性校验默认要求 Boot `3.4+`。本项目在 `bootstrap.yml` 中关闭了 `spring.cloud.compatibility-verifier`，以允许 **Boot `3.3.x` + Cloud `2024.0.x`** 组合启动（这是为满足版本号约束而做的显式取舍）。

## 工程结构

- `chat-gateway`：Spring Cloud Gateway（路由、跨域、Nacos 服务发现）
- `chat-service`：聊天核心服务（会话/消息内存存储、Spring AI `ChatClient` 流式输出、SSE）
- `chat-ui`：Vue3 前端（左侧会话列表 + 右侧聊天窗口）
- `docker-compose.yml`：一键启动 **Nacos 2.3.x**（镜像 `nacos/nacos-server:v2.3.2`）
- `docs/nacos/*.yaml`：Nacos 配置示例（复制到控制台即可）

## 1）启动 Nacos（2.3.x）

在项目根目录执行：

```bash
docker compose up -d
```

默认地址：`http://127.0.0.1:8848/nacos`（单机模式）。

## 2）在 Nacos 创建配置（配置中心）

在 **配置管理 -> 配置列表** 新建两条配置（`Group` 默认 `DEFAULT_GROUP`，与 `bootstrap.yml` 一致）：

- **Data ID**：`chat-service.yaml`  
  内容可参考：`docs/nacos/chat-service.yaml`  
  用于下发模型密钥、Base URL、模型名等（会覆盖本地默认值）。

- **Data ID**：`chat-gateway.yaml`  
  内容可参考：`docs/nacos/chat-gateway.yaml`

> 两个服务的 `bootstrap.yml` 已通过 `spring.cloud.nacos.config.extension-configs` 自动拉取同名远程配置。

## 3）配置模型提供方（OpenAI / 通义 / 千帆）

聊天服务使用 **`spring.ai.openai-sdk.*` 配置项**（可用环境变量覆盖，也可放到 Nacos 的 `chat-service.yaml`）：

- **OpenAI**
  - `OPENAI_API_KEY`：OpenAI Key
  - `OPENAI_BASE_URL`：默认 `https://api.openai.com`
  - `OPENAI_MODEL`：例如 `gpt-4o-mini`

- **通义千问（DashScope OpenAI 兼容模式）**
  - `OPENAI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1`
  - `OPENAI_API_KEY`：DashScope API Key
  - `OPENAI_MODEL`：例如 `qwen-turbo`（以阿里云文档为准）

- **百度千帆（OpenAI 兼容）**
  - `OPENAI_BASE_URL=https://qianfan.baidubce.com/v2`
  - `OPENAI_API_KEY`：千帆 Bearer Token
  - `OPENAI_MODEL`：例如 `ernie-4.0-turbo-8k`（以千帆文档为准）

### 智谱 AI（官方 `zai-sdk`）

在 `chat-service` 中已引入 **`ai.z.openapi:zai-sdk:0.3.3`**。开启后，**主对话流式**与**标题/猜你想问等短文本**均走智谱 OpenAPI v4，不再使用 `spring.ai.openai-sdk` 路径。

- **`ZHIPU_ENABLED=true`**（或 Nacos 中 `app.zhipu.enabled: true`）：启用智谱
- **`app.zhipu.api-key`**：智谱开放平台 API Key（`application.yml` 中已用 `api-key: ${ZHIPU_API_KEY:}`，可直接在配置文件写明文，或用环境变量 / Nacos 注入；仍缺省时会尝试 **`OPENAI_API_KEY`**）
- **`ZHIPU_MODEL`**：默认 `glm-4-flash`（与 `app.zhipu.model` 一致，可用智谱文档中的其它模型名）
- **`ZHIPU_BASE_URL`**：默认 `https://open.bigmodel.cn/api/paas/v4/`

对应 YAML（见 `chat-service/src/main/resources/application.yml`）：

```yaml
app:
  zhipu:
    enabled: ${ZHIPU_ENABLED:false}
    api-key: ${ZHIPU_API_KEY:}
    model: ${ZHIPU_MODEL:glm-4-flash}
    base-url: ${ZHIPU_BASE_URL:https://open.bigmodel.cn/api/paas/v4/}
```

未启用智谱时，仍使用 Spring AI OpenAI 兼容路径，本地默认值如下：

```yaml
spring:
  ai:
    openai-sdk:
      api-key: ${OPENAI_API_KEY}
      base-url: ${OPENAI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: ${OPENAI_MODEL:gpt-4o-mini}
```

## 4）启动后端

在项目根目录执行：

```bash
mvn -DskipTests package
export NACOS_SERVER=127.0.0.1:8848
export OPENAI_API_KEY=你的密钥
# 可选：切换兼容端点/模型
# export OPENAI_BASE_URL=...
# export OPENAI_MODEL=...

java -jar chat-service/target/chat-service-1.0.0-SNAPSHOT.jar &
java -jar chat-gateway/target/chat-gateway-1.0.0-SNAPSHOT.jar &
```

### 4.1）无 Nacos 的本地直连模式（可选）

如果你暂时不想启动 Nacos，可使用 `no-nacos` profile（网关会直连 `127.0.0.1:8081`）：

```bash
java -jar chat-service/target/chat-service-1.0.0-SNAPSHOT.jar --spring.profiles.active=no-nacos &
java -jar chat-gateway/target/chat-gateway-1.0.0-SNAPSHOT.jar --spring.profiles.active=no-nacos &
```

端口：

- 网关：`8080`（对外统一入口）
- 聊天服务：`8081`（注册到 Nacos，网关通过 `lb://chat-service` 转发）

## 5）启动前端

```bash
cd chat-ui
npm install
npm run dev
```

开发环境默认 `http://127.0.0.1:5173`，并通过 `vite.config.ts` 将 `/api` 代理到网关 `8080`。

前端与 SSE 请求均携带 **Cookie（`withCredentials` / `credentials: include`）** 以维持登录 Session。

## 5.1）登录与演示账号

- 打开前端后先进入登录页：用户名、密码、**图片验证码**（`GET /api/auth/captcha`，响应头 `X-Captcha-Id`）。
- 登录成功后可使用聊天；顶栏可 **退出登录**。
- 演示账号在 `chat-service/src/main/resources/application.yml` 的 **`app.auth.users`** 中配置（可配置多条），例如默认：`demo / demo123`、`admin / admin123`。
- 未登录访问 `/api/**`（除认证接口外）将返回 **401 JSON**：`{"message":"未登录或会话已过期"}`。

## 接口说明（经网关访问：前缀 `/api`）

- `GET /api/auth/captcha`：PNG 验证码图片（响应头 **`X-Captcha-Id`**，前端需保存并在登录时回传）
- `POST /api/auth/login`：登录（Body：`username`、`password`、`captchaId`、`captchaCode`），建立 Session
- `POST /api/auth/logout`：退出
- `GET /api/auth/me`：是否已登录及用户名（无需登录也可调用）
- `GET /api/sessions`：会话列表（需登录）
- `POST /api/sessions`：新建会话（Body：`{ "title": "可选" }`）
- `PATCH /api/sessions/{sessionId}`：重命名（Body：`{ "title": "新标题" }`）
- `DELETE /api/sessions/{sessionId}`：删除会话
- `GET /api/sessions/{sessionId}/messages`：消息历史
- `POST /api/chat/stream`：**SSE**（`Accept: text/event-stream`）  
  Body：`{ "sessionId": "...", "content": "..." }`；可选 `restartFromUserMessageId`：从该条**用户消息**起截断后续并重新生成（`content` 可传空字符串表示沿用原用户文本；非空则作为编辑后的文本）  
  事件 JSON：
  - `{"type":"start","assistantMessageId":"..."}`
  - `{"type":"delta","text":"..."}`
  - `{"type":"done"}`
  - `{"type":"error","message":"..."}`

## Spring AI 2.0 关键点（本仓库落地）

- `ChatClient.Builder` / `ChatClient`：`OpenAiManualConfiguration`
- `Prompt` + `UserMessage` + `SystemMessage`：`ChatAiStreamService`
- 流式：`chatClient.prompt(prompt).stream().content()`（Reactor `Flux<String>`）
- SSE：`SseEmitter` 将增量事件推送给前端

## 许可

示例项目代码以仓库根目录许可为准（如未声明，默认仅用于学习与演示）。
