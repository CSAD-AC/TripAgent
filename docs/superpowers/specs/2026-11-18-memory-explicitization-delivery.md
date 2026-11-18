# 长期记忆显性化交付说明

## 交付范围

补齐此前"内部已具备但未显性化"的五项能力，对应提交 `966ed67`。

1. **veto 规则**：前端记忆面板可录入"不要再记住/不要再提"规则，落到 `memory_item`（`type=VETO`），召回与注入时按规则抑制匹配条目。
2. **纠错审计**：`PATCH /api/memory/{id}` 纠正值并写 `memory_revision`，前端可查看单条记忆的修订历史。
3. **混合召回排序**：语义分主（0.65）+ 重要度（0.15）+ 置信度（0.10）+ 新鲜度（0.10），无向量命中时回退词面打分。
4. **预算注入**：`app.memory.hybrid.max-chars` / `max-items` 控制注入条数与字符预算。
5. **异步记忆事件**：聊天完成后入队 `memory_ingest_event`，由 `MemoryExtractionWorker` 异步抽取，不阻塞回答。

## 本轮新增实现

### 前端

- `MemoryPanel.tsx`：veto 规则输入、单条纠正、修订历史、删除、清空全部。
- `useMemory.ts`：`correct` / `veto` / `revisions`。
- `types/index.ts`：`UserMemory` 扩展、新增 `MemoryRevision`。

### 后端（agent-core）

- `MemoryItemVO`：`GET /api/memory` 返回结构化记忆（含 `memoryType` / `status` / `extractorVersion`）。
- `MemoryController`：删除按 `memory_item.id` 走治理删除并留审计；旧 `user_memory` id 自动回落兼容；清空同时作用于新旧两张表。
- `LlmMemoryCandidateExtractor` + `MemoryExtractionConfig`：LLM 深度抽取，固定 JSON schema、类型白名单、敏感信息拦截、失败回退规则抽取，默认关闭。
- `MemoryVectorIndexer`：EPISODIC 入库后 PENDING -> INDEXED/FAILED；`/api/admin/memory/reindex` 返回真实重建条数。
- `RemoteMemoryVectorService`：经 mcp-server 访问记忆向量库，60 秒状态缓存，远程失败返回空并由检索层降级。
- `MemoryRetrievalService`：向量分与词面分混合，向量不可用时行为不变。

### 后端（mcp-server）

- `UserMemoryVectorStore`：独立 collection `user_memory_episodic_v1`，延迟建连，未启用时不连 Milvus。
- `UserMemoryVectorController`：`/api/memory-vectors/{status,upsert,search}`、`DELETE /api/memory-vectors/{id}`。
- 召回过滤强制 `tenant_id` + `user_id` + `status='active'`，缺用户维度直接拒绝；字面量只保留字母数字下划线连字符，防表达式注入。

## 开关与默认值

| 配置 | 默认 | 说明 |
|---|---|---|
| `app.memory.hybrid.enabled` | `false` | 混合记忆注入链路 |
| `app.memory.hybrid.vector-enabled` | `false` | agent-core 走远程语义召回 |
| `app.memory-vector.enabled`（mcp-server） | `false` | 记忆向量库启用 |
| `app.memory.extraction.llm-enabled` | `false` | LLM 深度抽取 |
| `app.memory.ingest-trigger.enabled` | `true` | 聊天完成后入队 |

语义召回需要两侧同时打开；只开一侧时按"向量不可用"处理，自动回退结构化排序。

## 验证结果

- `mvn -o -pl agent-core -DexcludedGroups=integration test`：75 通过，0 失败。
- `mvn -o -pl mcp-server -DexcludedGroups=integration test`：75 通过，0 失败。
- `npm run build`：通过（仅有既有的 chunk 体积警告）。
- `git diff --check`：无空白错误。

新增测试：`LlmMemoryCandidateExtractorTest`、`MemoryVectorIndexerTest`、`UserMemoryVectorStoreTest`，并在 `MemoryRetrievalServiceTest` 增加向量优先用例。

## 已知限制

1. 本地未起 Milvus 时 mcp-server 无法启动（知识库 RAG 的 `milvusClient` 在启动期建连），语义召回的端到端验证需要先 `docker compose up`。
2. `embedding_status=FAILED` 目前只能通过手动 reindex 恢复，尚无退避重试 worker。
3. LLM 深度抽取尚未做与规则抽取的 A/B 质量与成本对比，默认保持关闭。
4. `/api/chat`（同步接口）目前整段跑在 boundedElastic 上，长任务仍会占用线程；流式路径是逐 token 的，不受影响。

## 本地全链路联调中发现并修复的问题（2026-11-18）

按"MySQL + Redis + Milvus + mcp-server + agent-core + 前端"全量启动跑真实链路，暴露了 5 个此前只有单测覆盖、跑起来才现形的问题：

1. **配置路径错位**：`app.memory.hybrid` / `extraction` / `ingest-trigger` / `consolidation` 全部误缩进到 `app.memory.long-term` 下，代码读 `app.memory.*` 永远读不到，全靠默认值"恰巧一致"。已上移并加注释说明。
2. **同步聊天在 Netty event loop 上跑阻塞 ReAct**：`/api/chat` 缺 `subscribeOn(boundedElastic)`，导致 `SyncMcpToolCallback` 的 `block()` 直接抛 `block()/blockFirst() ... not supported in thread reactor-http-nio`，**所有 MCP 工具调用在同步接口下必然失败**。已补 `subscribeOn` 并加注释。
3. **经历记忆语义召回永不触发**：`ReactAgent.systemPromptFor` 调渲染器时 query 传 `null`，向量检索分支被提前 return；只有 Multi-Agent 和 Graph 路径传了输入。已改为传入本轮用户消息。
4. **远程向量服务在 event loop 上 block**：`RemoteMemoryVectorService` 用 `WebClient.block()`，在请求线程上抛异常并静默降级。已改为提交到独立守护线程池 + 超时（状态 1s / 检索 2s / 写删 2s），任何线程调用都安全。
5. **异步事件没有消费者**：触发服务只入队，唯一会消费的 `MemoryConsolidationScheduler` 默认关闭，等于异步链路默认不工作。新增 `MemoryIngestWorker`（默认 5 秒一轮，与 `ingest-trigger.enabled` 同开关）。

### 联调实测结果

- 服务：MySQL 3306 / Redis 6379 / Milvus 19530 / mcp-server 8081 / agent-core 18080 / 前端 5173（8080 被 Steam 占用，前端代理改为 `VITE_PROXY_TARGET` 可配）。
- 注册登录（服务端 Session + CSRF）→ 聊天（ReAct，工具正常调用天气/搜索）→ 15 秒内 `llm-v1` 抽取出 `PROFILE` / `PREFERENCE` 记忆。
- Milvus：`user_memory_episodic_v1` 自动建 collection，reindex 写入成功（`embedding_status=INDEXED`），语义检索命中 `score≈0.78` 并带正确 metadata。
- veto：无规则时 `pinned=1, episodic=1`；新增"京都"规则后 `episodic=0, suppressions=2`，纠错写审计、删除同时清理向量，均在日志中可见。

## 回滚方式

1. 关闭 `app.memory.hybrid.vector-enabled`：语义召回立即退回词面打分，无需重启 Milvus。
2. 关闭 `app.memory.extraction.llm-enabled`：抽取退回 `rule-v1`，不再产生额外模型调用。
3. 全量回滚：`git revert 966ed67`（不含数据库结构变更，`memory_item` 等表在更早提交中已存在）。
