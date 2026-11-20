# TravelPal — 旅游规划 AI Agent

面向旅游规划场景的智能助手，基于大语言模型 + 工具调用 + 多 Agent 工作流技术构建。

## 项目介绍

TravelPal 是一个基于大语言模型的 AI 旅游规划系统，具备三种运行模式：

- **ReAct 模式**: 单 Agent 推理 + 工具调用，适用于快速查询（天气、路线、景点、12306 余票等）
- **Graph 模式**: Supervisor-Worker-Validator 多 Agent 工作流，适用于复杂旅游规划（约束提取→路线→行程→预算→校验→报告）
- **Multi-Agent 模式**: 主管调度子代理（SubAgent-as-Tool），适用于长尾/跨领域复杂任务（天气专家 + 知识检索 + 旅游规划外包 Graph）

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 后端框架 | Spring Boot | 3.5.14 |
| AI 框架 | Spring AI + Spring AI Alibaba | 1.1.2 / 1.1.2.0 |
| 语言模型 | DeepSeek（默认）/ 通义千问 | global:deepseek-v4.1-flash / qwen3.6-flash |
| 工具协议 | Model Context Protocol (MCP) | — |
| 持久化 | MySQL + Redis（MyBatis-Plus + Flyway） | 8.x / 7.x |
| 认证与会话 | Spring Security + Spring Session Redis（BCrypt / CSRF） | 6.x / 3.x |
| 向量库 | Milvus（RAG 语义检索，Docker Compose 部署） | - |
| 前端框架 | React | 19.x |
| 前端构建 | Vite | 8.x |
| 样式 | Tailwind CSS | 4.x |
| DAG 可视化 | @xyflow/react + dagre | — |
| 后端构建 | Maven（多模块） | 3.6+ |
| 测试 | JUnit 5 + Testcontainers + JaCoCo | Testcontainers 2.0.5 / JaCoCo 0.8.15 |
| 运行时 | Java 17 | — |
| 容器化 | Docker 多阶段构建（BuildKit 缓存） | — |
| CI | GitHub Actions（actionlint + gitleaks） | — |

## 项目结构

```
TripAgent/
├── agent-core/                # Agent 核心模块（Spring Boot :8080，前端访问入口）
│   └── src/main/java/
│       ├── uno.zhuchen.agent.core/       # ReAct 引擎：agent / clarify / llm / memory / prompt / tool
│       ├── uno.zhuchen.agent.persistence # 持久化层：mapper / service / cache（MySQL + Redis）
│       ├── uno.zhuchen.agent.domain      # entity / dto / vo
│       ├── uno.zhuchen.agent.config      # LLM / 模型注册表 / MyBatis-Plus 配置
│       ├── uno.zhuchen.agent.controller  # AgentController + ConversationController
│       └── uno.zhuchen.workflow          # Graph 工作流：agent 节点 / builder / state / util
│
├── mcp-server/                # MCP 工具服务器（Spring Boot :8081，被 agent-core 消费）
│   └── src/main/java/uno/zhuchen/agent/
│       ├── mcpserver/         # ToolRegister 工具装配 + 开关配置
│       └── tools/
│           ├── amap/          # 高德地图工具组（地理编码/路线/POI/天气）
│           ├── ticket12306/   # 12306 工具组（车站/余票/联程/车次路线）
│           ├── cloud/         # 云端工具组（网页搜索/页面抓取）
│           └── rag/           # RAG 知识库工具组（混合检索/入库/统计/重建）
│
├── frontend/                  # 前端（React + Vite :5173，容器内由 nginx 托管 :80）
│   ├── Dockerfile             # node:22-alpine 构建 → nginx:1.27-alpine 托管
│   ├── nginx.conf             # SPA 回退 + /api 反代到 agent-core:8080 + SSE 关缓冲
│   └── src/
│       ├── components/        # ChatMessage / GraphFlow / NodeDetailPanel / Sidebar 等
│       ├── hooks/             # useChat（SSE 分发）/ useConversations（对话列表）
│       └── types/             # TypeScript 类型定义（SSE 协议 / VO 契约）
│
├── docker/
│   └── milvus/                # Milvus standalone 的 compose（etcd + minio + milvus）
│
└── .github/
    └── workflows/             # ci.yml（主 CI）+ integration-tests.yml（手动集成测试）
```

> `agent-core/` 与 `mcp-server/` 根下也各有一份多阶段 `Dockerfile`，三个镜像的构建上下文
> 统一是仓库根，由根级 `.dockerignore` 裁剪。构建与运行方式见[容器化](#容器化-docker)。

## 环境要求

- **Java 17+**
- **Maven 3.6+**
- **Node.js 20.19+ 或 22.12+**（Vite 8 的要求；CI 与前端镜像统一用 Node 22），自带 npm
- **Docker**（三个用途：跑 RAG 需要的 Milvus、构建镜像、`mvn verify` 里的 Testcontainers 迁移测试。
  缺 Docker 时后两项对应的部分是跳过而非失败）
- **MySQL 8+ 与 Redis 7+**（`deepseek` 生产 profile 与 mcp-server 需要，见下文）
- **Milvus**（RAG 知识库语义检索需要，Docker Compose 一键启动）

## 快速开始

### 1. 获取 API Key

本项目需要以下 API Key：

| API | 用途 | 获取地址 | 配置位置 |
|-----|------|----------|----------|
| DeepSeek | 大语言模型（默认 profile） | https://platform.deepseek.com | agent-core `.env` |
| 通义千问 | 大语言模型（备选 profile） | https://dashscope.aliyun.com | agent-core `.env` |
| DashScope | RAG 向量化/重排（text-embedding-v3 / qwen3.7-text-rerank） | https://dashscope.aliyun.com | mcp-server `.env` |
| 高德地图 | 地理编码/路线/POI/天气 | https://console.amap.com | mcp-server `.env` |
| 百度千帆 | 网页搜索 | https://console.bce.baidu.com/qianfan/ | mcp-server `.env` |
| Tavily | 网页搜索（百度降级后备） | https://app.tavily.com | mcp-server `.env` |

### 2. 配置环境变量

```bash
# 配置 Agent Core
cp agent-core/src/main/resources/.env.example agent-core/src/main/resources/.env
# 编辑 .env，填入 DEEPSEEK_API_KEY（必填）和 DASHSCOPE_API_KEY（可选）

# 配置 MCP Server
cp mcp-server/src/main/resources/.env.example mcp-server/src/main/resources/.env
# 编辑 .env，填入 AMAP_API_KEY、BAIDU_API_KEY、DEEPSEEK_API_KEY（搜索双通道），TAVILY_API_KEY 可选
```

> **Profile 说明**：默认 profile 是 `deepseek`（见 application.yml `spring.profiles.default`）。
> `deepseek` 生产 profile 会启用持久化层，需要**本地 MySQL + Redis**；
> 改用 `dev` 或 `dashscope` profile 时，对话记忆和账号仓库使用内存实现，无需数据库。

### 认证与会话

- 首次打开前端进入登录/注册页，账号密码使用 BCrypt 哈希存储。
- 生产环境登录态由 Redis 服务端 Session 管理，浏览器只持有 `HttpOnly` Cookie。
- 默认空闲超时 30 分钟、最长登录有效期 12 小时；注销后服务端 Session 立即失效。
- 所有业务 API 均要求认证，对话、消息和后续长期记忆统一按认证后的 `userId` 隔离。
- 状态变更请求使用 `XSRF-TOKEN` Cookie + `X-XSRF-TOKEN` Header 双重提交保护。
- 生产部署必须通过 HTTPS 访问，并设置 `SESSION_COOKIE_SECURE=true`。

账号接口：

- `GET /api/auth/csrf`
- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `GET /api/auth/me`

### 3. 启动 Milvus（RAG 需要）

知识库语义检索依赖 Milvus 向量库，一键启动：

```bash
cd docker/milvus && docker compose up -d
```

### 4. 启动后端

```bash
# 终端 1: 启动 MCP Server（工具服务，必须先启动）
cd mcp-server
mvn spring-boot:run -Dspring-boot.run.profiles=deepseek

# 终端 2: 启动 Agent Core（主服务，deepseek 生产 profile 需本地 MySQL + Redis）
cd agent-core
mvn spring-boot:run -Dspring-boot.run.profiles=deepseek
```

> `deepseek` profile 调用 DeepSeek API（默认）；`dashscope` profile 调用通义千问 API。
> 两个服务都使用 `deepseek` profile 时即可启用完整的持久化与链路追踪能力。

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

打开浏览器访问 http://localhost:5173

## 使用说明

### ReAct 模式（快速问答）

适用于单轮工具调用场景。在 UI 右上角切换到 **ReAct** 模式。

**可查询内容**：
- 天气查询："深圳明天天气怎么样？"
- 12306 余票："查询6月30日深圳到广州的火车票"
- POI 搜索："深圳有哪些好玩的景点？"
- 路线规划："从深圳大学到欢乐谷怎么走？"

**模型选择器**：顶部下拉框可切换 LLM 模型（列表来自 `GET /api/models`，默认选中第一项 deepseek）。

**超能模式开关**：开启后 ReAct 迭代轮次无上限（默认上限 `agent.react.max-iterations` = 20 轮），
由前端「停止」按钮（SSE abort）中断；仅 ReAct 模式生效。

### Graph 模式（完整旅游规划）

适用于多步骤旅游规划。在 UI 右上角切换到 **Graph** 模式。

**典型用法**：
```
我想去北京玩3天，预算5000，2个人，喜欢文化和美食
```

系统会自动执行以下流程：
1. **Manager** — 提取约束 → 复述确认 → 用户确认后进入流程
2. **Route / Itinerary** — 并行规划交通路线与每日行程（parallel_group 虚拟中转 fan-out）
3. **Budget** — 等待路线与行程完成后核算预算（join）
4. **Validation** — 校验方案是否合理，失败则回退 Manager 重排（最多重试 2 次）
5. **Report** — 生成最终旅行规划报告

### Multi-Agent 模式（主管调度子代理）

适用于长尾/跨领域复杂任务。在 UI 右上角切换到 **多Agent** 模式。

**典型用法**：
```
帮我对比深圳和杭州哪个更适合定居，考虑房价、气候、教育
```

系统会自动执行：
1. **Manager** — 拆解任务（房价 / 气候 / 教育对比）
2. **KnowledgeExpert** — 联网检索各维度资料
3. **Manager** — 汇聚结果，输出带来源的对比结论

> 复杂旅游规划会被主管自动"外包"给 Graph 流水线（tripPlanner 子代理），形成"自主调度 + 固定流水线"混合编排。

### RAG 知识库检索与自沉淀

系统内置旅游知识库（景点/美食/行程/历史规划报告），LLM 在回答旅游知识类问题时**优先检索知识库**，不足时才转外部搜索，回答更有据、可溯源。

- **检索接入**：ReAct / Multi-Agent 模式会自动调用知识检索工具（`knowledgeSearch`），无需手动操作。
- **知识来源**：内置种子、手动上传文档、消息点赞沉淀。
- **消息点赞沉淀**：对满意的 assistant 回答点「赞」，其全文会自动入库（同消息去重），之后可被检索复用，越用越准。
- **知识库管理**：前端「知识库」页支持上传文档、查看/删除、全量重建索引。
- **降级容错**：Milvus / 向量化 / 重排等任一组件故障只会损失部分检索质量，自动降级（单路召回/跳过重排），不中断 Agent 主流程。

### 反问交互

在 Graph 模式下，如果信息不足，系统会弹出反问问题卡：
- 可选择预设选项
- 也可输入自定义回答

### 对话历史与侧边栏

- 生产 profile 下对话（消息）会持久化到 MySQL，侧边栏展示会话列表，支持选中/行内重命名/删除
- conversationId 双向绑定到 URL hash：刷新页面后自动恢复当前会话与历史消息
- 会话 ID 由后端权威生成（UUID），续聊必须传合法 UUID，否则返回 400

### 长期记忆

长期记忆采用三层文本模型，并在 ReAct、Graph、Multi-Agent 三种模式中合并进同一条 system 提示词：

1. **用户固定提示词**：由用户独占维护，worker 永远不修改。适合放硬性规则、称呼和长期稳定偏好。
2. **自动用户信息块**：最多 600 字，记录稳定的全局事实。用户可以直接编辑，后续整理会在容量允许时尽量保留，但不承诺永久不重建。
3. **自动事件记忆块**：最多 1000 字，记录已发生或明确计划的长期事件。用户同样可以编辑和回滚。

写入采用两阶段模型调用：先由 `MemoryCandidateExtractor` 从原始消息中抽取全局稳定事实，丢弃本次行程、临时状态和敏感信息；再由 `MemoryBlockUpdater` 基于完整旧记忆和候选事实重写自动块，冲突时以最新候选为准。普通对话不会触发长期记忆写入，只有显式关键词（如“记住”“记一下”“请记住”）和每日增量任务会入队。

幂等由 `memory_raw_event.event_key` 保证：显式事件使用用户、会话、来源和规范化文本计算 SHA-256，每日事件使用 `message:<id>`；唯一键为 `(user_id, event_key)`，重复入队由 `INSERT IGNORE` 跳过。事件状态为 `PENDING / PROCESSING / DONE / FAILED`，失败最多重试 3 次。

V15 为每个事件记录抽取候选、最终结果和真实状态：`UPDATED / NO_CHANGE / FAILED`。前端会先提示“正在整理”，处理完成后再根据真实结果提示成功、无变化或失败，不再把 async worker 的 `DONE` 误当成一定发生了更新。

开发环境不兼容旧记忆数据。V14 迁移会删除旧的 `user_memory`、`memory_item`、`memory_ingest_event`、`memory_revision`，并建立 `memory_user_prompt`、`memory_block`、`memory_raw_event`、`memory_block_revision`、`memory_consolidation_watermark`；V15 增加事件结果审计列。

长期记忆接口：

- `GET /api/memory/prompt`
- `PUT /api/memory/prompt`
- `GET /api/memory/prompt/revisions`
- `POST /api/memory/prompt/rollback/{revisionId}`
- `GET /api/memory/blocks`
- `GET /api/memory/events/latest?conversationId=...`
- `PUT /api/memory/blocks/{type}`
- `GET /api/memory/blocks/{type}/revisions`
- `POST /api/memory/blocks/{type}/rollback/{revisionId}`

主要配置：

- `app.memory.blocks.enabled`：是否启用新长期记忆 worker，默认 `true`。
- `app.memory.blocks.consolidate-interval-ms` / `batch-size`：待处理事件轮询间隔与批量。
- `app.memory.blocks.daily-cron` / `daily-timezone` / `daily-min-new-messages`：每日增量整理计划与最小新增消息数。
- `app.memory.blocks.user-prompt-max-chars` / `profile-max-chars` / `event-max-chars`：三层文本上限。
- `app.memory.blocks.max-attempts`：事件最大重试次数。
- `app.memory.ingest-trigger.enabled` / `app.memory.hybrid.enabled`：旧链路已默认关闭。

## 接口说明

### REST API

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/models` | GET | 模型列表（前端模型选择器数据源，第一项为默认模型） |
| `/api/chat` | POST | 同步聊天 |
| `/api/chat/stream` | POST | 流式聊天（SSE，ReAct 模式） |
| `/api/chat/graph` | POST | 流式工作流（SSE，Graph 模式） |
| `/api/chat/answer` | POST | 提交反问回答 |
| `/api/chat/multi` | POST | 流式 Multi-Agent（SSE，主管调度子代理） |
| `/api/conversations?userId&page&size` | GET | 对话分页列表 |
| `/api/conversations/{id}` | GET | 对话详情（含消息列表） |
| `/api/conversations/{id}` | PUT | 修改对话标题 |
| `/api/conversations/{id}` | DELETE | 软删除对话 |
| `/api/knowledge/documents` | POST | 上传文档入知识库（异步入库，返回 docId） |
| `/api/knowledge/documents?page&size` | GET | 知识文档分页列表 |
| `/api/knowledge/documents/{id}` | GET | 文档详情 + 索引状态（上传后轮询） |
| `/api/knowledge/documents/{id}` | DELETE | 删除文档（软删 + 物理删分块与向量） |
| `/api/knowledge/reindex` | POST | 全量重建向量索引 |
| `/api/knowledge/documents/stats` | GET | 知识库统计（文档数/分块数/状态分布） |
| `/api/messages/{id}/like` | POST | 点赞消息并沉淀进知识库 |

聊天请求体 `ChatRequest`：`message`（必填）、`conversationId`（可选 UUID）、`modelId`（可选，仅 ReAct 生效）、`superMode`（可选，仅 ReAct 生效）。

### SSE 事件流

流式端点使用 Server-Sent Events。**第一个事件永远是 `session_init`**（携带后端生成的 `conversationId` 与 `traceId`）；Graph 模式第二个事件是 `graph_topology`（完整 DAG 定义）。

| 事件 | 说明 |
|------|------|
| `session_init` | 会话初始化（下发 conversationId + traceId） |
| `heartbeat` | 心跳（15s 间隔，防反向代理 idle timeout） |
| `clarification_request` | 反问用户（携带 questionId / 预设选项） |
| `error` | 不可恢复异常（含 traceId） |
| `thinking` / `thinking_token` | LLM 思考文本（token 级实时推送） |
| `tool_call_start` / `tool_call` / `tool_result` / `tool_error` | 工具调用生命周期（toolCallId 精确匹配，支持并行） |
| `iteration_separator` | ReAct 迭代轮次分隔 |
| `graph_topology` | Graph DAG 拓扑定义（节点/边/起点/终点） |
| `node_status` / `node_progress` / `node_data` / `node_error` | Graph 节点状态/进度/输出/异常 |
| `graph_iteration` | Graph 回退迭代跟踪（Manager 回退决策时发射） |
| `branch_taken` | Graph 条件边路由（passed / failed 等） |
| `final` | 最终答案 |

## 示例

### 示例 1: 天气查询（ReAct 模式）

```
用户: 北京明天天气怎么样？
助手: [调用天气工具] → 北京明天晴转多云，25-32°C，南风3-4级
```

### 示例 2: 12306 余票查询（ReAct 模式）

```
用户: 查询6月30日深圳到广州的火车票
助手: [调用12306工具] → 共15趟列车...
```

### 示例 3: 旅游规划（Graph 模式）

```
用户: 我想去成都玩4天，预算6000，3个人，喜欢美食和自然风光
助手: [工作流自动执行] →
  └─ Manager: 提取约束→复述确认
  └─ Route / Itinerary: 并行编排交通方案与 Day1-4 行程
  └─ Budget: 预算核算
  └─ Validation: 方案校验（失败自动回退重排）
  └─ Report: 完整旅行规划报告
```

## 容器化 (Docker)

三个服务各有一份多阶段 `Dockerfile`。**构建上下文统一是仓库根 `TripAgent/`**
（Maven 父 pom 在根目录，`-pl <module> -am` 必须在根执行）：

| 镜像 | 构建阶段 → 运行阶段 | 端口 | 说明 |
|------|--------------------|------|------|
| `tripagent/mcp-server` | `maven:3.9-eclipse-temurin-17` → `eclipse-temurin:17-jre-jammy` | 8081 | 工具服务 |
| `tripagent/agent-core` | 同上 | 8080 | 主服务（三模式） |
| `tripagent/frontend` | `node:22-alpine` → `nginx:1.27-alpine` | 80 | 静态站点 + `/api` 反代 |

### 构建

```bash
cd TripAgent

docker build -f mcp-server/Dockerfile  -t tripagent/mcp-server:latest  .
docker build -f agent-core/Dockerfile  -t tripagent/agent-core:latest  .
docker build -f frontend/Dockerfile    -t tripagent/frontend:latest    .
```

首次构建较慢（容器内要从零下载 Maven / npm 依赖，冷缓存时 agent-core 约十分钟），
之后靠 BuildKit 的 `RUN --mount=type=cache` 复用依赖层。

> **密钥不会被烘进镜像**，由三道防线共同保证：
>
> 1. 根级 `.dockerignore` 把 `**/.env` 挡在构建上下文之外；
> 2. Dockerfile 只在容器内从源码重新构建，**从不 COPY 本地 `target/*.jar`**
>    （本地 jar 的 `BOOT-INF/classes/.env` 里就是真实密钥）；
> 3. 构建末尾用 `jar tf` 自检，一旦发现 `BOOT-INF/classes/.env` 直接让构建失败。

### 运行

镜像里只有应用本身，**MySQL / Redis / Milvus 仍需外部提供**（仓库只附带 Milvus 的 compose）。
密钥一律在运行时用 `-e` 注入，不要写进镜像：

```bash
docker network create tripagent-net

# 工具服务
docker run -d --name mcp-server --network tripagent-net -p 8081:8081 \
  -e AMAP_API_KEY=... -e BAIDU_API_KEY=... -e DASHSCOPE_API_KEY=... \
  -e MYSQL_HOST=host.docker.internal -e MYSQL_PASSWORD=... \
  -e MILVUS_HOST=host.docker.internal \
  tripagent/mcp-server:latest

# 主服务。注意 MCP_SERVER_URL 必须指向容器名: 容器内的 localhost 指向容器自己,
# 用默认的 localhost:8081 会连不上工具服务。
docker run -d --name agent-core --network tripagent-net -p 8080:8080 \
  -e DEEPSEEK_API_KEY=... \
  -e MCP_SERVER_URL=http://mcp-server:8081 \
  -e MYSQL_HOST=host.docker.internal -e MYSQL_PASSWORD=... \
  -e REDIS_HOST=host.docker.internal \
  tripagent/agent-core:latest

# 前端
docker run -d --name frontend --network tripagent-net -p 80:80 \
  tripagent/frontend:latest
```

访问 http://localhost 即可。前端 nginx 通过 `agent-core:8080` 反代 `/api` 并对 SSE 接口关闭了代理缓冲，
所以 `agent-core` 必须在同一网络里且容器名为 `agent-core`。

> `host.docker.internal` 是 Docker Desktop（Windows / macOS）提供的宿主机别名。
> Linux 上需要额外加 `--add-host=host.docker.internal:host-gateway`，
> 或直接把 `MYSQL_HOST` / `REDIS_HOST` / `MILVUS_HOST` 指向容器网络内的服务名。

## 持续集成 (CI)

仓库已接入 GitHub Actions，配置在 `.github/workflows/`：

| 工作流 | 触发 | 内容 |
|--------|------|------|
| `ci.yml` | push 到 `main` / `master`、任意 PR、手动触发 | 五个 job 并行跑，见下表 |
| `integration-tests.yml` | 仅手动触发 | 依赖 MySQL / Redis / Milvus 与真实 API Key 的集成测试 |

### `ci.yml` 的五个 job

| Job | 名称 | 做什么 | 产出 artifact |
|-----|------|--------|---------------|
| `backend` | 后端编译与单元测试 | `mvn -B -ntp verify -DexcludedGroups=integration`：编译 + 单元测试 + 打包（JDK 17） | surefire 报告、JaCoCo 覆盖率、可执行 jar |
| `frontend` | 前端类型检查与构建 | `npm ci` + `npm run build`（`tsc && vite build`，Node 22） | `frontend/dist` |
| `workflow-lint` | Workflow 静态检查 | actionlint 校验 `.github/workflows/*.yml` 的语法、表达式与脚本问题 | — |
| `secret-scan` | 密钥泄漏扫描 | gitleaks v8.30.1 扫**全量提交历史**（`fetch-depth: 0`），命中即失败 | — |
| `images` | 镜像构建验证 | 构建三个镜像但**不推送**任何 registry，仅 push 主分支与手动触发时跑 | — |

### 设计上的几个取舍

- **主 CI 不依赖任何密钥或外部服务**：用 `-DexcludedGroups=integration` 排除 `@Tag("integration")` 的测试，
  fork 出来的 PR 也能跑绿，不会因为没配 Secrets 而红。
- **测试分层**：单元测试与 Testcontainers 测试进主 CI（后者自己起临时容器，不需要本地库）；
  需要真实 MySQL / Redis / Milvus / API Key 的进 `integration-tests.yml`，手动触发，不拖红主 CI。
  Flyway 迁移脚本会在主 CI 中于临时 MySQL 8.4 容器上全量跑一遍，本机没有 Docker 时这组测试自动跳过。
- **覆盖率只报告、不设闸**：JaCoCo 报告作为 artifact 上传，覆盖率低不会让构建失败。
- **密钥扫描不留基线**：2026-06 那次 `AMAP_API_KEY` 硬编码已于 2026-10 用 `git filter-repo`
  从全部提交中清除，因此 CI 里没有 baseline 抑制文件——任何新命中都应让 CI 失败。
  扫描带 `--redact`，即使命中也不会把密钥明文写进 CI 日志。
- **不加 `paths` 过滤**：作为分支保护的必需检查（required status check）时，
  `paths` 过滤会让某些 PR 永远等不到检查结果而卡住合并。

### 本地复现

```bash
# 后端（在 TripAgent/ 下）
mvn -B -ntp clean verify -DexcludedGroups=integration

# 前端
cd frontend && npm ci && npm run build
```

### 手动跑集成测试

先在 `Settings → Secrets and variables → Actions` 配置
`DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY`、`AMAP_API_KEY`、`BAIDU_API_KEY`
（`TAVILY_API_KEY` 可选），再到 Actions 页手动触发「集成测试」。

workflow 会先做一轮 Secrets 完整性预检：缺任何一个必需项就把整个集成测试跳过并打 warning，
不会失败。触发时可以勾选 `mcp_full_suite`，决定跑 mcp-server 的全部集成测试，还是只跑 `.env` 冒烟用例。

## 常见问题

**Q: 启动时提示 API Key 无效？**
A: 检查 `.env` 文件中的 API Key 是否正确，注意区分 DeepSeek 和 DashScope 的 Key。

**Q: deepseek profile 启动报数据库/Redis 连接失败？**
A: `deepseek` 生产 profile 需要本地 MySQL（默认 localhost:3306，库名 `agent`）与 Redis（默认 localhost:6379），
连接参数通过 `.env` 的 `MYSQL_*` / `REDIS_*` 配置。没有数据库时可改用 `dev` / `dashscope` profile（内存记忆）。

**Q: MCP Server 连接失败？**
A: 先启动 mcp-server（:8081），再启动 agent-core（:8080），agent-core 启动时会自动连接 MCP Server。

**Q: 前端启动后接口返回 500？**
A: 检查后端两个服务是否都已启动，前端会代理 `/api` 请求到 `localhost:8080`。

**Q: 模型选择器里没有模型？**
A: 调用 `GET /api/models` 检查后端是否正常返回 `app.models` 注册表（默认 deepseek / qwen 两项）。

**Q: RAG 知识库检索不可用 / 返回 `degraded=rerank`？**
A: 语义检索依赖 Milvus（需先 `cd docker/milvus && docker compose up -d`）与 DashScope（向量化 + 重排，`DASHSCOPE_API_KEY` 配在 mcp-server `.env`）。任一组件的故障会自动降级（单路召回 / 跳过重排），不会中断主流程；`degraded=rerank` 表示重排未生效（如账号未开通对应模型），检索仍会返回 RRF 粗排结果。

**Q: Docker 构建报 `FATAL: src/main/resources/.env 被打进了 jar`？**
A: 这是镜像里的密钥自检主动拦截，不是构建环境问题。它表示 jar 中出现了 `BOOT-INF/classes/.env`，
镜像一旦产出就会带真实密钥。检查根级 `.dockerignore` 是否仍排除 `**/.env`，以及 Dockerfile 是否被改成
COPY 本地 `target/` 产物；正常走仓库里的 Dockerfile 不会触发。

**Q: `docker run` 后 agent-core 启动时报 MCP 连接失败？**
A: 容器里的 `localhost` 指向容器自己。启动 agent-core 时显式传
`-e MCP_SERVER_URL=http://mcp-server:8081`，并确保 `mcp-server` 与 `agent-core` 接在同一个 Docker 网络。

**Q: 本地 `mvn verify` 慢，或者卡在 Flyway 相关测试？**
A: 那组迁移测试会用 Testcontainers 起一个临时 `mysql:8.4` 容器，首次运行需下载镜像。
本机没装或没启动 Docker 时它们会自动跳过，不会失败。

**Q: 为什么 PR 上不跑 `images` job？**
A: 镜像构建要在容器里从零下载依赖，冷缓存时最久一次约十分钟。只在 push 主分支和手动触发时跑，
避免每个 PR 都多等；想让 PR 也验证，删掉该 job 的 `if` 条件即可。
