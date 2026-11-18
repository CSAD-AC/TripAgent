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

## 回滚方式

1. 关闭 `app.memory.hybrid.vector-enabled`：语义召回立即退回词面打分，无需重启 Milvus。
2. 关闭 `app.memory.extraction.llm-enabled`：抽取退回 `rule-v1`，不再产生额外模型调用。
3. 全量回滚：`git revert 966ed67`（不含数据库结构变更，`memory_item` 等表在更早提交中已存在）。
