# 混合式长期记忆阶段一与阶段二基础设施实施计划

> **用于代理执行：** 本计划按任务顺序执行，使用复选框跟踪。当前在同一会话内内联执行，不另行分派子代理。

**目标：** 建立通用结构化记忆表、治理字段、抽取事件表和可重建的向量基础设施，同时保持现有 `/api/memory` 与三种 Agent 的长期记忆行为不回退。

**架构：** 阶段一把现有 `user_memory` 演进为通用 `memory_item`，先用确定性抽取器写入低风险偏好，并保留兼容服务接口；阶段二增加 `memory_ingest_event`、后台 worker 骨架和 `MemoryVectorService` 抽象。MCP Server 已有 DashScope + Milvus，用户记忆向量基础设施通过新的独立 collection 配置实现，知识库 RAG 不受影响。

**技术栈：** Java 17、Spring Boot 3.5、WebFlux、MyBatis-Plus、Flyway、H2、Milvus、JUnit 5、Mockito、React 19。

---

### 范围边界

- 本次实现“阶段一完整能力 + 阶段二基础设施”，不开启阶段三的混合召回与提示词注入。
- 不做 LLM 自动抽取的实际接入；`MemoryExtractionWorker` 先以事件处理框架、固定结果结构和可测试接口落地。
- 不在 `agent-core` 直接引入 Milvus/Embedding 重型依赖；先定义 `MemoryVectorService` 接口与内存/禁用实现，真实向量写入通过 MCP Server 的独立用户记忆 collection 作为后续接线点。
- 不修改前端，只保持现有“偏好记忆”接口继续可用。

### 文件结构

- 新增/修改 Flyway 迁移：`V11__create_memory_item.sql`、`V12__create_memory_ingest_event.sql`、`V13__create_memory_revision.sql`。
- 修改 H2 dev schema：加入上述三张表。
- 新增实体：`MemoryItemEntity`、`MemoryIngestEventEntity`、`MemoryRevisionEntity`。
- 新增 Mapper：`MemoryItemMapper`、`MemoryIngestEventMapper`、`MemoryRevisionMapper`。
- 新增领域对象：`MemoryItemType`、`MemoryItemStatus`、`MemoryVectorStatus`、`MemoryItemRecord`。
- 新增服务：`MemoryItemService`、`MemoryIngestService`、`MemoryExtractionWorker`、`MemoryVectorService`、`NoopMemoryVectorService`。
- 修改 `UserMemoryService`：保留现有 API，内部委托 `MemoryItemService` 或兼容层。
- 新增测试：`MemoryItemServiceTest`、`MemoryIngestServiceTest`、`MemoryVectorServiceTest`。
- 修改 Flyway 迁移测试：版本从 V1~V10 更新到 V1~V13。

---

### 任务 1：通用记忆表与治理字段

**文件：**
- 新增 `agent-core/src/main/resources/db/migration/V11__create_memory_item.sql`
- 新增 `agent-core/src/main/resources/db/migration/V12__create_memory_ingest_event.sql`
- 新增 `agent-core/src/main/resources/db/migration/V13__create_memory_revision.sql`
- 修改 `agent-core/src/main/resources/db/dev/schema.sql`
- 修改 `agent-core/src/test/java/uno/zhuchen/agent/persistence/FlywayMigrationTest.java`

- [ ] 步骤 1：创建 `memory_item` 表，字段按设计文档覆盖 `tenant_id`、`user_id`、`project_id`、`memory_type`、`memory_key`、`memory_value`、`content`、`attributes_json`、`source_type`、`source_conversation_id`、`source_message_id`、`confidence`、`importance`、`memory_status`、`superseded_by`、`expires_at`、`embedding_status`、`embedding_model`、`embedding_version`、`extractor_version`、时间戳。
- [ ] 步骤 2：避免字段名 `status` 触发 MyBatis-Plus 全局逻辑删除；数据库列叫 `memory_status`，Java 字段叫 `memoryStatus`。
- [ ] 步骤 3：为非 episodic 类型建立 `(tenant_id, user_id, project_id, memory_type, memory_key, memory_status)` 条件唯一约束；MySQL 用生成列或唯一索引结合 `memory_status` 实现，H2 使用普通唯一索引。
- [ ] 步骤 4：创建 `memory_ingest_event` 与 `memory_revision` 表。
- [ ] 步骤 5：在 H2 dev schema 添加等价表。
- [ ] 步骤 6：更新 `FlywayMigrationTest`：迁移版本为 V1~V13，执行数量为 13，并断言新表和关键索引存在。
- [ ] 步骤 7：运行 `mvn -pl agent-core "-Dtest=FlywayMigrationTest" test`，Docker 不可用时该测试应跳过，其他测试不报错。

### 任务 2：通用记忆实体、Mapper 与领域类型

**文件：**
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryItemEntity.java`
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryIngestEventEntity.java`
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryRevisionEntity.java`
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryItemMapper.java`
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryIngestEventMapper.java`
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryRevisionMapper.java`
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/core/memory/MemoryItemType.java`
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/core/memory/MemoryItemStatus.java`
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/core/memory/MemoryVectorStatus.java`
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/core/memory/MemoryItemRecord.java`

- [ ] 步骤 1：定义枚举 `MemoryItemType`：`PROFILE`、`PREFERENCE`、`CONSTRAINT`、`VETO`、`EPISODIC`。
- [ ] 步骤 2：定义枚举 `MemoryItemStatus`：`ACTIVE`、`DELETED`、`SUPERSEDED`、`REJECTED`。
- [ ] 步骤 3：定义枚举 `MemoryVectorStatus`：`NOT_REQUIRED`、`PENDING`、`INDEXED`、`FAILED`、`DELETED`。
- [ ] 步骤 4：定义 `MemoryItemRecord`，字段与实体一致但使用枚举，供服务层使用。
- [ ] 步骤 5：定义实体，数据库字段映射显式写 `@TableField`，`memoryStatus` 映射到 `memory_status`。
- [ ] 步骤 6：定义三个 BaseMapper 接口，不写自定义 SQL，先用 QueryWrapper/UpdateWrapper。
- [ ] 步骤 7：运行 `mvn -pl agent-core -DskipTests test-compile`，确认编译成功。

### 任务 3：`MemoryItemService` 结构化记忆服务

**文件：**
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/core/memory/MemoryItemService.java`
- 新增 `agent-core/src/test/java/uno/zhuchen/agent/core/memory/MemoryItemServiceTest.java`

- [ ] 步骤 1：`upsert(tenantId, userId, projectId, type, key, value, sourceType, conversationId, messageId, confidence, importance, extractorVersion)` 写入或更新 active 记忆。
- [ ] 步骤 2：对非 episodic 类型按 `(tenantId, userId, projectId, type, key, active)` 查找旧值并覆盖；对 episodic 类型总是追加。
- [ ] 步骤 3：`supersede(tenantId, userId, id, replacementId)` 将旧项标记为 `SUPERSEDED` 并写入 `superseded_by`。
- [ ] 步骤 4：`list(tenantId, userId, projectId)` 只返回 active 项，按 `updated_at desc` 排序。
- [ ] 步骤 5：`delete(tenantId, userId, id)` 与 `deleteAll(tenantId, userId)` 软删除，且必须校验归属。
- [ ] 步骤 6：`findByIdForUser` 找不到或跨用户抛 `NotFoundException`。
- [ ] 步骤 7：所有数据库异常在服务边界捕获：读操作返回空列表/空值，写操作记录 warning，不向调用方抛出。
- [ ] 步骤 8：单元测试覆盖 insert、update、episodic append、supersede、delete、delete-all、跨用户拒绝、删除后重加。
- [ ] 步骤 9：运行 `mvn -pl agent-core "-Dtest=MemoryItemServiceTest" test`，确认全部通过。

### 任务 4：兼容现有 `UserMemoryService`

**文件：**
- 修改 `agent-core/src/main/java/uno/zhuchen/agent/core/memory/UserMemoryService.java`
- 修改 `agent-core/src/test/java/uno/zhuchen/agent/core/memory/UserMemoryServiceTest.java`

- [ ] 步骤 1：保留现有构造器签名、公共方法和返回类型，避免影响三个 Agent 与前端。
- [ ] 步骤 2：内部写入同时调用 `MemoryItemService.upsert`，将旧 key 映射为 `PREFERENCE`、`DEFAULT/PROFILE` 对应类型；旧表写入保留一个阶段。
- [ ] 步骤 3：`loadSummary` 继续从旧 active 记忆读取，避免阶段一引入召回变化。
- [ ] 步骤 4：现有测试保持通过，并新增断言：抽取“我喜欢美食”后 `memory_item` 收到一条 `PREFERENCE`。
- [ ] 步骤 5：运行 `mvn -pl agent-core "-Dtest=MemoryExtractorTest,MemoryPromptRendererTest,UserMemoryServiceTest,MemoryItemServiceTest" test`，确认全部通过。

### 任务 5：抽取事件表服务

**文件：**
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/core/memory/MemoryIngestService.java`
- 新增 `agent-core/src/test/java/uno/zhuchen/agent/core/memory/MemoryIngestServiceTest.java`

- [ ] 步骤 1：`enqueue(tenantId, userId, conversationId, eventType, payloadJson)` 创建 `PENDING` 事件。
- [ ] 步骤 2：`claimBatch(limit)` 原子领取 `limit` 个 pending 事件并标记为 `PROCESSING`；单测中用 Mockito 模拟。
- [ ] 步骤 3：`markDone(id)`、`markFailed(id, error)`、`markSkipped(id)` 更新状态。
- [ ] 步骤 4：事件 payload 最大 8KB，超长截断并记录 warning。
- [ ] 步骤 5：测试覆盖 enqueue、claim、done、failed、skipped、payload 截断。
- [ ] 步骤 6：运行 `mvn -pl agent-core "-Dtest=MemoryIngestServiceTest" test`，确认全部通过。

### 任务 6：抽取 Worker 骨架与固定结果结构

**文件：**
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/core/memory/MemoryExtractionResult.java`
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/core/memory/MemoryExtractionWorker.java`
- 新增 `agent-core/src/test/java/uno/zhuchen/agent/core/memory/MemoryExtractionWorkerTest.java`

- [ ] 步骤 1：定义 `MemoryExtractionResult`，包含 `type`、`key`、`value`、`content`、`confidence`、`importance`、`attributesJson`、`expiresAt`。
- [ ] 步骤 2：定义 `MemoryCandidateExtractor` 接口，方法为 `extract(String payloadJson)`。
- [ ] 步骤 3：提供 `RuleMemoryCandidateExtractor` 实现，复用现有 `MemoryExtractor`，不调用 LLM。
- [ ] 步骤 4：`MemoryExtractionWorker.processPendingBatch(limit)` 领取事件、解析 payload、调用 extractor、写入 `MemoryItemService`、标记 done；失败标记 failed。
- [ ] 步骤 5：敏感类别占位策略：当前只拦截空值、超长值和明显凭据关键字，返回 skipped；详细策略留后续阶段。
- [ ] 步骤 6：测试覆盖成功写入、无候选跳过、事件失败、重复 key 更新。
- [ ] 步骤 7：运行 `mvn -pl agent-core "-Dtest=MemoryExtractionWorkerTest" test`，确认全部通过。

### 任务 7：向量服务抽象与禁用实现

**文件：**
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/core/memory/MemoryVectorService.java`
- 新增 `agent-core/src/main/java/uno/zhuchen/agent/core/memory/NoopMemoryVectorService.java`
- 新增 `agent-core/src/test/java/uno/zhuchen/agent/core/memory/NoopMemoryVectorServiceTest.java`

- [ ] 步骤 1：定义接口方法：`upsert(MemoryItemRecord item)`、`delete(String memoryItemId)`、`rebuild(String tenantId, String userId)`。
- [ ] 步骤 2：定义 `NoopMemoryVectorService` 作为默认 bean，所有方法记录 debug 日志并返回成功。
- [ ] 步骤 3：在 `application.yml` 增加 `app.memory.hybrid.enabled=false`，默认禁用真实向量写入。
- [ ] 步骤 4：测试证明 Noop 实现不会抛异常，也不会修改记忆项。
- [ ] 步骤 5：运行 `mvn -pl agent-core "-Dtest=NoopMemoryVectorServiceTest" test`，确认通过。

### 任务 8：MCP Server 用户记忆 collection 配置骨架

**文件：**
- 修改 `mcp-server/src/main/resources/application.yml`
- 新增 `mcp-server/src/main/java/uno/zhuchen/agent/tools/memory/UserMemoryVectorProperties.java`
- 新增 `mcp-server/src/test/java/uno/zhuchen/agent/tools/memory/UserMemoryVectorPropertiesTest.java`

- [ ] 步骤 1：增加独立配置项 `app.memory-vector.collection-name=user_memory_episodic_v1`、`embedding-dimension=1024`。
- [ ] 步骤 2：不复用知识库 `knowledge_chunk` collection 名称，不修改 RAG 配置。
- [ ] 步骤 3：创建 `UserMemoryVectorProperties` 读取配置并提供默认值。
- [ ] 步骤 4：单元测试验证默认值和自定义值绑定。
- [ ] 步骤 5：运行 `mvn -pl mcp-server "-Dtest=UserMemoryVectorPropertiesTest" test`，确认通过。

### 任务 9：文档与全量验证

**文件：**
- 修改 `README.md`
- 修改 `docs/superpowers/specs/2026-11-17-hybrid-memory-architecture-design.md`（仅更新状态说明）

- [ ] 步骤 1：README 增加“阶段一/二基础设施状态”的说明：通用表、事件表、向量配置已落地，但线上默认不启用自动抽取和混合召回。
- [ ] 步骤 2：设计文档状态从“已完成方案确认”改为“阶段一与阶段二基础设施已实施，阶段三待实施”。
- [ ] 步骤 3：运行 `mvn test -DexcludedGroups=integration`，确认后端全量测试通过。
- [ ] 步骤 4：运行 `npm run build`，确认前端构建通过。
- [ ] 步骤 5：运行 `git diff --check`，确认无空白错误。
- [ ] 步骤 6：提交：`feat: add hybrid memory phase1 phase2 infrastructure`。

---

### 自审记录

- 阶段一目标已覆盖：通用表、治理字段、来源/版本/状态、修订表、兼容旧接口。
- 阶段二“基础设施”目标已覆盖：事件表、worker 骨架、固定抽取结果、向量服务抽象、独立 collection 配置。
- 阶段三“混合召回与注入”明确不在本次范围。
- 未在 `agent-core` 引入真实 Milvus 依赖，避免本阶段启动链变重；真实向量写入留到后续接线任务。
