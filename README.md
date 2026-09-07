# TravelPal — 旅游规划 AI Agent

面向旅游规划场景的智能助手，基于大语言模型 + 工具调用 + 多 Agent 工作流技术构建。

## 项目介绍

TravelPal 是一个基于大语言模型的 AI 旅游规划系统，具备两种运行模式：

- **ReAct 模式**: 单 Agent 推理 + 工具调用，适用于快速查询（天气、路线、景点、12306 余票等）
- **Graph 模式**: Supervisor-Worker-Validator 多 Agent 工作流，适用于复杂旅游规划（约束提取→路线→行程→预算→校验→报告）
- **Multi-Agent 模式**: 主管调度子代理（SubAgent-as-Tool），适用于长尾/跨领域复杂任务（天气专家 + 知识检索 + 旅游规划外包 Graph）

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 后端框架 | Spring Boot | 3.5.14 |
| AI 框架 | Spring AI + Spring AI Alibaba | 1.1.2 / 1.1.2.0 |
| 语言模型 | DeepSeek（默认）/ 通义千问 | deepseek-v4-flash / qwen3.6-flash |
| 工具协议 | Model Context Protocol (MCP) | — |
| 持久化 | MySQL + Redis（MyBatis-Plus + Flyway） | 8.x / 7.x |
| 前端框架 | React | 19.x |
| 构建工具 | Vite | 8.x |
| 样式 | Tailwind CSS | 4.x |
| DAG 可视化 | @xyflow/react + dagre | — |
| 构建工具 | Maven | — |
| 运行环境 | Java 17 | — |

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
│           └── cloud/         # 云端工具组（网页搜索/页面抓取）
│
└── frontend/                  # 前端（React + Vite :5173）
    └── src/
        ├── components/        # ChatMessage / GraphFlow / NodeDetailPanel / Sidebar 等
        ├── hooks/             # useChat（SSE 分发）/ useConversations（对话列表）
        └── types/             # TypeScript 类型定义（SSE 协议 / VO 契约）
```

## 环境要求

- **Java 17+**
- **Maven 3.6+**
- **Node.js 18+**
- **npm 9+**
- **MySQL 8+ 与 Redis 7+**（仅 `deepseek` 生产 profile 需要，见下文）

## 快速开始

### 1. 获取 API Key

本项目需要以下 API Key：

| API | 用途 | 获取地址 | 配置位置 |
|-----|------|----------|----------|
| DeepSeek | 大语言模型（默认 profile） | https://platform.deepseek.com | agent-core `.env` |
| 通义千问 | 大语言模型（备选 profile） | https://dashscope.aliyun.com | agent-core `.env` |
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
# 编辑 .env，填入 AMAP_API_KEY、BAIDU_API_KEY，以及可选的 TAVILY_API_KEY
```

> **Profile 说明**：默认 profile 是 `deepseek`（见 application.yml `spring.profiles.default`）。
> `deepseek` 生产 profile 会启用持久化层，需要**本地 MySQL + Redis**；
> 改用 `dev` 或 `dashscope` profile 则使用内存记忆（InMemoryChatMemory），无需数据库。

### 3. 启动后端

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

### 4. 启动前端

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

### 反问交互

在 Graph 模式下，如果信息不足，系统会弹出反问问题卡：
- 可选择预设选项
- 也可输入自定义回答

### 对话历史与侧边栏

- 生产 profile 下对话（消息）会持久化到 MySQL，侧边栏展示会话列表，支持选中/行内重命名/删除
- conversationId 双向绑定到 URL hash：刷新页面后自动恢复当前会话与历史消息
- 会话 ID 由后端权威生成（UUID），续聊必须传合法 UUID，否则返回 400

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
