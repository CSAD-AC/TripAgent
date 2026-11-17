# 混合式长期记忆阶段三与阶段四实施计划

> **用于代理执行：** 本计划按任务顺序执行，使用复选框跟踪。当前在同一会话内内联执行。

**目标：** 接通混合召回与提示词注入，补齐用户纠错/veto/审计、保留策略和基础记忆巩固，使长期记忆从“基础设施”变为“用户可感知能力”。

**架构：** `agent-core` 负责结构化记忆、混合召回编排和提示词注入；真实 Embedding/Milvus 通过 MCP Server 暴露 HTTP 接口，复用其已有 DashScope/Milvus 配置，但使用独立 collection。阶段三默认开启结构化召回，向量召回失败时降级；阶段四增加用户治理接口和定时整理。

**技术栈：** Java 17、Spring Boot 3.5、WebFlux、MyBatis-Plus、Milvus、DashScope Embedding、JUnit 5、Mockito、React 19。

---

### 实际实施与计划差异

- 已实施：结构化混合召回、veto 抑制、预算渲染、三条 Agent 路径切换、用户纠错/veto/审计 API、管理 health/process/reindex API、定时整理与保留策略。
- 未实施：MCP Server 的用户记忆向量 HTTP 服务、`RemoteMemoryVectorService`、真实 Milvus 语义召回与删除传播。当前 `MemoryVectorService` 仍是 `NoopMemoryVectorService`。
- `app.memory.hybrid.enabled=true` 的含义是启用混合记忆提示词链路和结构化经历排序，不代表真实向量检索已启用。
- 经历记忆当前由规则和兼容写入产生，未接入 LLM 深度抽取。

### 原计划范围边界

- 不实现图记忆。
- 不在 `agent-core` 直接引入 Spring AI Milvus 依赖，避免双套配置漂移。
- 用户记忆向量集合与知识库 `knowledge_chunk` 完全隔离。
- 真实向量链路原计划通过配置 `app.memory.hybrid.enabled=true` 开启；实际当前只启用结构化混合链路，真实向量实现仍待接线。
- 不修改前端布局，只扩展后端 API 与既有偏好记忆面板数据结构兼容。

### 文件结构

- `mcp-server`：新增 `UserMemoryVectorController`、`UserMemoryVectorService`，使用现有 `EmbeddingModel` 与独立 `VectorStore` 配置。
- `agent-core`：新增 `RemoteMemoryVectorService` 实现 `MemoryVectorService`；新增 `MemoryRetrievalService`、`MemoryPromptContext`、`HybridMemoryPromptRenderer`。
- `agent-core`：修改 `ReactAgent`、`MultiAgentManager`、`GraphStreamRunner`，把旧 `UserMemoryService` 注入改成混合上下文注入；保留旧注入作为禁用混合模式下的回退。
- `agent-core`：新增 `MemoryGovernanceService`、`MemoryAdminService`、`MemoryConsolidationScheduler`。
- `agent-core`：扩展 `MemoryController` 与新增 `MemoryAdminController`。
- `agent-core`：扩展 `UserMemoryVO` 或新增 `MemoryItemVO` 以承载治理字段。

---

### 任务 1：MCP Server 用户记忆向量服务

**文件：**
- 新增 `mcp-server/src/main/java/uno/zhuchen/agent/tools/memory/UserMemoryVectorService.java`
- 新增 `mcp-server/src/main/java/uno/zhuchen/agent/tools/memory/UserMemoryVectorController.java`
- 新增相关 DTO 与测试

- [ ] 使用 `EmbeddingModel` 对文本向量化。
- [ ] 使用独立 `VectorStore` bean 指向 `user_memory_episodic_v1`，与知识库 `VectorStore` 隔离。
- [ ] 提供 `upsert`、`delete`、`search`、`rebuild` HTTP 接口。
- [ ] search 必须服务端强制 `tenant_id`、`user_id`、`status=active` 过滤。
- [ ] 测试覆盖 DTO 校验、filter 构造、失败不抛到聊天链路。

### 任务 2：agent-core 远程向量实现与配置

**文件：**
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/core/memory/RemoteMemoryVectorService.java`
- 修改 `application.yml`、`NoopMemoryVectorService` 条件

- [ ] 实现 `MemoryVectorService`，通过 HTTP 调 MCP Server。
- [ ] `enabled=true` 时启用远程实现，`false` 时启用 Noop。
- [ ] 所有调用失败只记录日志，不改变主聊天流程。
- [ ] 单元测试覆盖失败降级。

### 任务 3：混合召回与预算渲染

**文件：**
- 新增 `MemoryRetrievalService.java`
- 新增 `MemoryPromptContext.java`
- 新增 `HybridMemoryPromptRenderer.java`
- 新增测试

- [ ] 加载结构化 profile/constraint/veto。
- [ ] 调用向量检索获取 episodic 候选。
- [ ] 应用 veto 抑制、排序、TopK、字符/ token 预算。
- [ ] 输出统一 prompt section，明确“当前请求优先”。
- [ ] 测试覆盖排序、veto、预算、空结果、向量失败降级。

### 任务 4：ReAct/Graph/Multi-Agent 注入切换

**文件：**
- 修改三条 Agent 入口

- [ ] 混合模式开启时注入 `HybridMemoryPromptRenderer` 结果。
- [ ] 混合模式关闭时保持旧 `UserMemoryService` 注入。
- [ ] 写入兼容性不变：旧路径仍写，异步事件在聊天完成后入队。
- [ ] 三种模式行为一致。

### 任务 5：用户治理 API

**文件：**
- 新增/修改 `MemoryController`
- 新增 `MemoryGovernanceService`

- [ ] 支持纠正值、软删除、veto 创建、修订历史查询、按用户重建索引。
- [ ] 所有接口按认证用户隔离。
- [ ] 删除触发向量删除。

### 任务 6：阶段四定时整理与保留策略

**文件：**
- 新增 `MemoryConsolidationScheduler.java`
- 新增 `MemoryAdminService.java`

- [ ] 定期处理 failed/pending embedding 重试。
- [ ] 定期软删除过期记忆。
- [ ] 提供 health 统计：pending/failed/active/superseded。
- [ ] 定时-default disabled，生产可配置开启。

### 任务 7：文档与全量验证

- 更新 README 与设计文档状态。
- 后端全量测试、前端构建、diff check。
- 提交：`feat: add hybrid memory recall and governance`。
