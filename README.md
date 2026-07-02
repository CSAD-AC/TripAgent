# TravelPal — 旅游规划 AI Agent

面向旅游规划场景的智能助手，基于大语言模型 + 工具调用 + 多 Agent 工作流技术构建。

## 项目介绍

TravelPal 是一个基于大语言模型的 AI 旅游规划系统，具备两种运行模式：

- **ReAct 模式**: 单 Agent 推理 + 工具调用，适用于快速查询（天气、路线、景点、12306余票等）
- **Graph 模式**: Supervisor-Worker-Validator 多 Agent 工作流，适用于复杂旅游规划（约束提取→路线→行程→预算→校验→报告）

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 后端框架 | Spring Boot | 3.5.14 |
| AI 框架 | Spring AI + Spring AI Alibaba | 1.1.2 |
| 语言模型 | DeepSeek / 通义千问 | deepseek-chat / qwen3.6-flash |
| 工具协议 | Model Context Protocol (MCP) | — |
| 前端框架 | React | 19.x |
| 构建工具 | Vite | 8.x |
| 样式 | Tailwind CSS | 4.x |
| DAG 可视化 | @xyflow/react + dagre | — |
| 构建工具 | Maven | — |
| 运行环境 | Java 17 | — |

## 项目结构

```
Agent/
├── agent-core/          # Agent 核心模块（Spring Boot :8080）
│   ├── src/main/java/
│   │   ├── agent/       # ReAct Agent 核心引擎
│   │   ├── llm/         # LLM 调用层（DeepSeek / DashScope）
│   │   ├── tool/        # 工具注册表 + 内置工具
│   │   ├── memory/      # 对话记忆管理
│   │   ├── prompt/      # System Prompt 定义
│   │   ├── config/      # 配置类
│   │   ├── controller/  # REST API + SSE 端点
│   │   ├── clarify/     # 反问澄清机制
│   │   └── workflow/    # SWV Graph 工作流
│   │       ├── agent/   # 各 Agent 节点
│   │       ├── builder/ # Graph 图构建
│   │       └── state/   # 工作流状态定义
│   └── src/main/resources/
│
├── mcp-server/          # MCP 工具服务器（Spring Boot :8081）
│   ├── amap/            # 高德地图工具组
│   │   ├── tool/        # 地理编码/路线/POI/天气
│   │   └── service/     # 高德 API 调用封装
│   ├── ticket12306/     # 12306 工具组
│   │   ├── tool/        # 车站/余票/联程/路线
│   │   └── service/     # 12306 API 调用封装
│   └── cloud/           # 云端工具组
│       ├── tool/        # 网页搜索/页面抓取
│       └── service/     # 搜索 API 调用封装
│
└── frontend/            # 前端（React + Vite :5173）
    └── src/
        ├── components/  # UI 组件
        ├── hooks/       # 自定义 Hooks
        └── types/       # TypeScript 类型定义
```

## 环境要求

- **Java 17+**
- **Maven 3.6+**
- **Node.js 18+**
- **npm 9+**

## 快速开始

### 1. 获取 API Key

本项目需要以下 API Key：

| API | 用途 | 获取地址 | 配置位置 |
|-----|------|----------|----------|
| DeepSeek | 大语言模型 | https://platform.deepseek.com | agent-core `.env` |
| 通义千问 | 大语言模型（备选） | https://dashscope.aliyun.com | agent-core `.env` |
| 高德地图 | 地理编码/路线/POI/天气 | https://console.amap.com | mcp-server `.env` |
| 百度千帆 | 网页搜索 | https://console.bce.baidu.com/qianfan/ | mcp-server `.env` |

### 2. 配置环境变量

```bash
# 配置 Agent Core
cp agent-core/src/main/resources/.env.example agent-core/src/main/resources/.env
# 编辑 .env，填入 DASHSCOPE_API_KEY 和 DEEPSEEK_API_KEY

# 配置 MCP Server
cp mcp-server/src/main/resources/.env.example mcp-server/src/main/resources/.env
# 编辑 .env，填入 AMAP_API_KEY 和 BAIDU_API_KEY
```

### 3. 启动后端

```bash
# 终端 1: 启动 MCP Server（工具服务）
cd mcp-server
mvn spring-boot:run -Dspring-boot.run.profiles=deepseek

# 终端 2: 启动 Agent Core（主服务）
cd agent-core
mvn spring-boot:run -Dspring-boot.run.profiles=deepseek
```

> 使用 `deepseek` profile 调用 DeepSeek API；使用 `dashscope` profile 调用通义千问 API（默认）。

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

### Graph 模式（完整旅游规划）

适用于多步骤旅游规划。在 UI 右上角切换到 **Graph** 模式。

**典型用法**：
```
我想去北京玩3天，预算5000，2个人，喜欢文化和美食
```

系统会自动执行以下流程：
1. **Manager** — 提取约束 → 复述确认 → 用户确认后进入流程
2. **Route** — 规划交通路线
3. **Itinerary** — 编排每日行程
4. **Budget** — 核算预算
5. **Validation** — 校验方案是否合理
6. **Report** — 生成最终旅行规划报告

### 反问交互

在 Graph 模式下，如果信息不足，系统会弹出反问问题卡：
- 可选择预设选项
- 也可输入自定义回答

## 接口说明

### REST API

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/chat` | POST | 同步聊天 |
| `/api/chat/stream` | POST | 流式聊天（SSE，ReAct 模式） |
| `/api/chat/graph` | POST | 流式工作流（SSE，Graph 模式） |
| `/api/chat/answer` | POST | 提交反问回答 |

### SSE 事件流

流式端点使用 Server-Sent Events，事件类型包括：

| 事件 | 说明 |
|------|------|
| `session_init` | 会话初始化（下发 conversationId） |
| `thinking_token` | LLM 思考文本（实时推送） |
| `tool_call` / `tool_result` / `tool_error` | 工具调用生命周期 |
| `iteration_separator` | 迭代轮次分隔 |
| `clarification_request` | 反问用户 |
| `node_status` / `node_data` / `graph_iteration` | Graph 工作流状态 |
| `final` | 最终答案 |
| `heartbeat` | 心跳（15s 间隔） |

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
  └─ Route: 深圳→成都交通方案
  └─ Itinerary: Day1-4 行程编排
  └─ Budget: 预算核算
  └─ Validation: 方案校验
  └─ Report: 完整旅行规划报告
```

## 常见问题

**Q: 启动时提示 API Key 无效？**
A: 检查 `.env` 文件中的 API Key 是否正确，注意区分 DeepSeek 和 DashScope 的 Key。

**Q: MCP Server 连接失败？**
A: 先启动 mcp-server（:8081），再启动 agent-core（:8080），agent-core 启动时会自动连接 MCP Server。

**Q: 前端启动后接口返回 500？**
A: 检查后端两个服务是否都已启动，前端会代理 `/api` 请求到 `localhost:8080`。
