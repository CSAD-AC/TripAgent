# 混合式长期记忆架构设计

## 状态

本设计已完成方案确认。阶段一与阶段二基础设施已实施；阶段三混合召回与注入仍待实施。

## 问题陈述

当前长期记忆实现只保存少量由用户明确表达的确定性偏好，并在每次请求中注入全部 active 记忆。这个方案安全、可预测，但无法跨会话召回过去的计划、决策或旅行经历，也没有区分长期画像事实与短期任务上下文。

旅游 Agent 的用户经常需要在不同会话中重复说明约束，导致体验断裂。对于企业级系统，记忆还需要具备来源追踪、纠错、删除传播、租户与用户隔离、提示词预算、可审计性，以及向量库不可用时的优雅降级能力。

## 目标

1. 同时支持结构化用户记忆与跨会话经历召回。
2. 结构化记忆作为权威源，向量索引作为可重建的派生数据。
3. 通过明确的 token 与条目预算限制记忆注入，避免长期记忆挤占提示词。
4. 提供用户可见的查看、纠错、删除和“不要再记住”规则能力。
5. 保持基于已认证用户的严格隔离，并为租户和项目级 scope 预留能力。
6. 在上线前给出可度量的质量、延迟、隐私和可靠性目标。

## 非目标

1. v1 不实现完整图记忆或组织级实体图谱。数据结构和元数据为其预留空间，但本阶段不交付图遍历。
2. 不保存原始凭据、健康数据、证件号或未脱敏敏感内容。这些类别由策略直接拦截。
3. 不把每条聊天消息都当作长期记忆。原始对话仍属于会话历史，而不是记忆。
4. 不替代会话摘要或短期聊天记忆。记忆层是对它们的补充。
5. 不支持跨用户共享、全局记忆，或在没有合规流程时由管理员直接查看用户记忆内容。

## 用户故事

### 终端用户

- 作为旅行者，我希望 Agent 记住我稳定的偏好和约束，这样我不必在每次对话中重复说明。
- 作为旅行者，我希望 Agent 能召回相关的过往行程和规划决策，这样后续问题具有连续性。
- 作为旅行者，我希望查看 Agent 记住了哪些关于我的信息，以便纠正或删除。
- 作为旅行者，我希望设置“不要再提及此事”规则，让敏感或过期信息不再影响后续回答。
- 作为旅行者，我希望当前明确要求始终优先于历史偏好。

### 产品与运维

- 作为产品负责人，我希望记忆相关交互可度量，从而判断召回是否提升了任务完成率。
- 作为运维人员，我希望向量索引可以从关系型权威源重建，以便模型升级和故障恢复。
- 作为合规负责人，我希望记录来源、scope、模型版本和删除历史，使记忆行为可审计。

## 目标架构

### 记忆分层

| 层 | 内容 | 权威源 | 检索方式 |
|---|---|---|---|
| 短期会话 | 最新消息、工具输出、临时任务状态 | 现有会话存储或 Redis | 按 `userId + conversationId` 直接读取 |
| 结构化画像 | `profile`、`preference`、`constraint`、`veto` | MySQL | 固定 schema 直接读取 |
| 经历记忆 | 过往行程、计划、决策、重要结果 | MySQL | SQL 过滤 + Milvus 召回 |
| 治理与审计 | 来源、版本、模型、置信度、删除与修订历史 | MySQL | 管理 API 与审计查询 |

该架构采用分层模式：短期会话上下文与长期记忆分离；长期记忆使用关系型存储承载治理能力，使用向量存储承载语义召回。这与当前企业级记忆实践一致，即把 buffer、summary、episodic、entity/rule memory 分开，并在结构化数据与向量数据上做混合检索 [AlloyDB 长期记忆方案](https://cloud.google.com/blog/products/databases/implementing-long-term-ai-agent-memory-in-alloydb-and-memorystore)。

设计参考：

- ChatGPT 区分 saved memories 与 chat-history references，并提供明确的查看、纠错、删除和项目级记忆控制 [Memory in ChatGPT](https://help.openai.com/en/articles/8590148-memory-in-chatgpt)。
- Mem0 使用 SQL 作为事实与元数据的权威源，向量库负责语义检索，并按 user、agent、app、session 做 scope 隔离 [Mem0 工作原理](https://github.com/mem0ai/mem0/blob/main/docs/core-concepts/how-it-works.mdx)。
- Google Memory Bank 使用固定 schema 的结构化画像，实现低延迟、一致的用户画像访问 [Memory Profiles](https://docs.cloud.google.com/gemini-enterprise-agent-platform/scale/memory-bank/profiles)。

### 记忆类型

| 类型 | 用途 | 示例 | 更新策略 |
|---|---|---|---|
| `profile` | 对个性化有用的稳定用户事实 | 家乡城市、语言、常用出发城市 | 需要明确表述；旧值标记为 superseded |
| `preference` | 软偏好 | 喜欢自然、海滩、当地美食 | 按归一化 key 合并或覆盖 |
| `constraint` | 规划硬规则 | 不吃辣、不要红眼航班、无障碍要求 | 高优先级；需要明确纠正才能改变 |
| `veto` | 抑制规则 | 不要再提这个话题、不要记住这类信息 | 最高优先级；不把原始内容注入提示词 |
| `episodic` | 有时效的经历与决策 | 去过京都、规划过四川行程、拒绝过某酒店区域 | 默认追加；用 superseded 标记而非直接覆盖 |
| `summary` | 压缩后的会话或项目历史 | 长任务摘要 | 存在会话/项目记忆中，不进入长期事实库 |

### 权威源规则

MySQL 是所有记忆记录的权威源。Milvus 只包含以 MySQL 记忆项 ID 为主键的派生向量。任何 Milvus collection 都可以从 MySQL 重建。删除时先把 MySQL 行标记为非 active，再删除或过滤对应向量。Milvus 故障不能阻止关系型记忆落库。

## 数据模型

本设计用通用 `memory_item` 替代当前单一用途的 `user_memory` 表，或将现有表迁移到该结构。表结构由 Flyway 管理。

### `memory_item`

- `id`：UUID，主键。
- `tenant_id`：租户标识，默认 `default`。
- `user_id`：已认证用户标识，必填。
- `project_id`：可空的项目或工作空间 scope。
- `memory_type`：`profile`、`preference`、`constraint`、`veto` 或 `episodic`。
- `memory_key`：归一化键，例如 `travel.preference.food` 或 `trip.visited.city`。
- `memory_value`：归一化短值。
- `content`：由 key 与 value 渲染出的展示文本。
- `attributes_json`：结构化元数据，例如城市、日期范围、预算金额、实体标签。
- `source_type`：`user_message`、`assistant_message`、`tool_result`、`import` 或 `system`。
- `source_conversation_id`：来源会话 ID。
- `source_message_id`：可用时的来源消息 ID。
- `confidence`：0 到 1 的置信度。
- `importance`：0 到 1 的重要度，用于排序。
- `status`：`active`、`deleted`、`superseded` 或 `rejected`。
- `superseded_by`：替代当前记忆项的 ID（适用时）。
- `expires_at`：可选过期时间。
- `embedding_status`：`not_required`、`pending`、`indexed`、`failed` 或 `deleted`。
- `embedding_model`：生成向量所用模型。
- `embedding_version`：Embedding 模型版本或指纹。
- `extractor_version`：记忆抽取 prompt 或规则版本。
- `created_at`、`updated_at`、`deleted_at`。

索引：

- 非 episodic 类型在 (`tenant_id`, `user_id`, `project_id`, `memory_type`, `memory_key`, `status`) 上建立唯一或条件唯一 scope。
- (`tenant_id`, `user_id`, `status`, `memory_type`, `updated_at`) 索引。
- (`tenant_id`, `user_id`, `status`, `expires_at`) 索引。

### `memory_ingest_event`

- `id`：UUID。
- `tenant_id`、`user_id`、`conversation_id`。
- `event_type`：`message`、`tool_result`、`correction` 或 `delete`。
- `payload_json`：有大小上限的序列化输入。
- `status`：`pending`、`processing`、`done`、`failed` 或 `skipped`。
- `attempt_count`、`last_error`、`created_at`、`processed_at`。

事件表支持异步抽取与重放。

### `memory_revision`

- `id`、`memory_item_id`、`operation`、`previous_value`、`new_value`、`actor`、`reason`、`created_at`。

修订记录支持用户纠错与合规审计。

### Milvus Collection

Collection 名称：`user_episodic_memory_v1`

字段：

- `id`：记忆项 ID，作为主键。
- `vector`：`content` 及相关结构化字段的向量。
- `tenant_id`、`user_id`、`project_id`。
- `memory_type`、`status`、`importance`、`created_at`、`updated_at`。
- `source_conversation_id`、`embedding_model`、`embedding_version`。

所有检索必须在服务端加入过滤表达式：`tenant_id`、`user_id`、`status = active`，以及可选的项目 scope。调用方不能传入任意过滤条件。

## 写入链路

1. 聊天请求完成后，框架发出 completed-turn 事件，包含原始用户消息、最终回复、会话 ID、已认证用户 ID 和选定的工具输出。
2. 同步快速抽取器只保存高置信、明确表达的结构化值。这保留当前行为，并让低风险偏好立即可用。
3. 框架入队一条 `memory_ingest_event`，用于深度抽取。
4. 后台 worker 从 MySQL 加载近期相关记忆，用于去重和冲突检测。
5. LLM 以固定 JSON 抽取候选记忆。prompt 包含记忆类型定义、schema 约束、敏感类别排除项和当前查询上下文。
6. 落库前执行策略检查：敏感信息脱敏、租户与用户绑定、veto 检查、重复检测、置信度阈值、过期时间设置。
7. 结构化候选按类型策略 upsert 或标记 superseded。
8. 经历候选追加到 `memory_item`，并设置 `embedding_status = pending`。
9. Embedding worker 对 pending 项向量化并 upsert 到 Milvus。
10. 如果 Milvus 失败，关系型记忆仍保持 active，`embedding_status = failed`，并安排重试。

### 抽取规则

- 使用一个小型、带版本号的 JSON schema 约束抽取输出。
- 只保存可复用事实：偏好、约束、目标、决策、计划和经历。
- 不保存模型推理过程、密钥、原始凭据、健康数据、证件号或情绪化辱骂内容。
- 类似“我从上海搬到杭州”的更新会创建新的当前值，并把旧值标记为 superseded。
- 类似“不要记住我的薪资”的 veto 会创建或更新 `veto` 规则，并阻断后续匹配候选。
- 每条记忆项都要记录抽取 prompt 与规则版本。

## 读取链路

1. 已认证 principal 提供 `tenantId` 和 `userId`。项目 scope（如有）来自可信服务端上下文。
2. 加载置顶的结构化记忆：active 的 `profile`、`constraint` 和 `veto` 记录。veto 规则转换成注入抑制条件，不作为原始个人数据注入。
3. 用当前消息和最近一到两轮会话构造检索 query。
4. 在 Milvus 中检索经历候选，`topK = 20`，并强制带上租户/用户/status 过滤。
5. 在 MySQL 中查询结构化命中项、近期经历、实体 key 命中和 supersede 关系。
6. 使用加权排序融合候选：
   - 语义相似度
   - 关键词或实体重合
   - 时间新鲜度
   - 置信度
   - 重要度
   - 来源可靠性
   - 用户反馈
7. 应用 veto 与抑制规则。
8. 用 `finalTopK = 6` 将候选项压缩为有边界的提示词片段。
9. 注入 ReAct 与 Multi-Agent 的系统提示词；Graph 模式通过初始 state 的 `INPUT_USER_MEMORY` 注入。
10. 如果检索失败，只使用置顶结构化记忆继续回答。

### 排序公式

初始排序公式可配置、可调优：

```text
score = 0.35 * 语义相似度
      + 0.20 * 词法命中
      + 0.15 * 实体命中
      + 0.15 * 时间新鲜度
      + 0.10 * 置信度
      + 0.05 * 重要度
```

权重是配置项而不是代码常量。评测结果可以在不做 schema 迁移的情况下调整权重。

## 提示词预算

配置项：

```yaml
app.memory.hybrid:
  enabled: true
  profile-max-tokens: 300
  episodic-max-tokens: 700
  total-max-tokens: 1000
  recall-top-k: 20
  final-top-k: 6
  min-score: 0.62
  max-item-chars: 160
  search-timeout-ms: 250
  fail-open: true
```

规则：

- 注入记忆总长度不得超过 `total-max-tokens`。
- 优先注入结构化画像与约束。
- 经历记忆只在排序后填充剩余预算。
- 当前明确用户请求始终优先于已保存偏好。
- 提示词在有用时包含来源标签与日期，例如“2026-11-17：规划过京都行程”。
- 没有记忆时不注入任何记忆段落。

## 模块边界

| 模块 | 职责 | 依赖 |
|---|---|---|
| `MemoryIngestService` | 接收 completed-turn 事件并入队抽取任务 | Agent 框架、事件表 |
| `MemoryExtractionWorker` | 异步候选抽取、校验、supersede 逻辑 | LLM 网关、策略服务、MySQL |
| `MemoryPolicyService` | 敏感过滤、veto 检查、置信度阈值 | 策略配置 |
| `MemoryItemService` | CRUD、归属校验、修订、过期、软删除 | MySQL mapper |
| `MemoryVectorService` | Embedding、upsert、删除、重建、collection 健康 | Embedding 客户端、Milvus |
| `MemoryRetrievalService` | 混合召回、融合、重排、抑制 | MySQL、Milvus |
| `MemoryPromptRenderer` | 为所有 Agent 模式渲染有边界的提示词 | 检索结果 |
| `MemoryController` | 面向用户的列表、更新、删除、veto、设置 | 记忆项服务 |
| `MemoryAdminController` | 重建索引、健康、队列统计、审计查询 | 运维服务 |

这些模块应保持足够独立，以便在没有真实向量库的情况下做单元测试。MCP Server 中已有的 RAG 检索代码可复用其 Embedding 客户端配置与 Milvus 访问模式，但用户记忆必须使用独立 collection 和过滤 namespace，不能与知识库 RAG 混用。

## API 设计

现有端点继续可用，并由结构化记忆支撑。

- `GET /api/memory`：列出当前用户的 active 记忆，包含类型与来源元数据。
- `DELETE /api/memory/{id}`：软删除一条归属当前用户的记忆，并入队向量删除。
- `DELETE /api/memory`：软删除当前用户全部 active 记忆。

新增端点：

- `PATCH /api/memory/{id}`：纠正值或状态。
- `POST /api/memory/veto`：创建抑制规则。
- `GET /api/memory/revisions/{id}`：列出归属当前用户某条记忆的修订历史。
- `POST /api/memory/reindex`：入队重建当前用户经历记忆向量。
- `GET /api/admin/memory/health`：索引新鲜度、pending 项、失败事件。

外部或不存在 ID 返回 `404`，避免泄露存在性。

## 隔离与治理

- `tenant_id` 与 `user_id` 由服务端根据认证与会话上下文注入，业务 API 绝不从请求体接收。
- 所有 MySQL 查询必须包含租户与用户过滤条件。
- 所有 Milvus 检索使用服务端构造的过滤表达式。
- 知识库 RAG、用户记忆和未来的图记忆使用不同 collection 或 namespace。
- MySQL 中删除采用软删除，并异步传播到 Milvus。SLA 为删除后 5 分钟内不可被检索。
- 记忆设置允许分别关闭个性化和关闭经历召回。
- 临时或非个性化会话不创建、不更新记忆。
- 敏感数据策略在 Embedding 前执行。被拒绝的候选只以 `skipped` 或 `rejected` 状态保存在 ingest 事件中。
- 每条记忆记录来源消息和抽取器版本，便于审计。

## 失败处理

- 结构化记忆失败：记录 warning，继续聊天。
- Milvus 超时：只使用 MySQL 结构化记忆。
- Embedding 失败：保持记忆 active，但索引状态 failed，并按退避策略重试。
- 抽取 LLM 失败：事件标记 failed 并重试，绝不阻塞用户响应。
- 排序失败：回退到置顶结构化记忆。
- 重建索引必须幂等，并以记忆项 ID 作为向量主键。

## 分阶段交付与时间考虑

### 阶段一：结构化画像增强

- 将 `user_memory` 迁移或适配到 `memory_item` 字段。
- 增加来源、置信度、重要度、状态、过期、修订和 veto 支持。
- 初期继续保留确定性抽取。
- 增加严格提示词预算与冲突优先级。

退出标准：画像与约束具备治理能力、可审计且有长度边界。

### 阶段二：经历记忆与向量基础设施

- 增加 `memory_ingest_event` 与后台 worker。
- 增加基于 LLM 的固定 JSON schema 候选抽取。
- 在 Milvus 创建 `user_episodic_memory_v1`。
- 实现 Embedding、索引、删除传播和重建索引。

退出标准：一条经历记忆可以在一个会话中创建，并能从 MySQL 重建到 Milvus。

### 阶段三：混合召回与注入

- 实现结构化 + 向量召回、融合、重排和抑制。
- 接入 ReAct、Graph 与 Multi-Agent。
- 增加配置、指标和 fail-open 行为。

退出标准：相关经历记忆能在配置预算内注入，且故障时安全降级。

### 阶段四：管理与记忆巩固

- 增加用户管理界面、管理审计、保留策略、TTL 和周期性 consolidation。
- 只有在召回质量数据证明存在缺口后，才考虑图记忆。

退出标准：运维人员无需直接访问数据库即可观察和维护记忆健康。

## 成功指标

### 领先指标

- 记忆召回 precision@6：在记忆评测集上至少 0.80。
- 记忆召回 recall@6：对已标注相关记忆至少 0.70。
- 提示词记忆长度：p95 不超过 1000 token。
- 检索新增延迟：p95 不超过 250 ms。
- 跨用户泄露：自动化测试与手工冒烟测试均为 0。
- 删除传播：95% 的向量在 5 分钟内被过滤或删除。

### 滞后指标

- 上线四周后，跨会话重复陈述偏好的比例下降至少 30%。
- 多会话旅行规划中的后续任务完成率提升至少 10%。
- 有记忆注入的会话中，用户纠错或删除记忆的比例低于 5%。
- 不因记忆服务导致聊天失败率上升。

测量方式：离线评测集，加上请求 trace 与记忆服务事件采集的生产指标。

## 测试策略

- 单元测试：抽取 schema 校验、策略检查、supersede 逻辑、排序、预算和提示词渲染。
- Repository 测试：归属校验、软删除、删除后重新添加、过期和修订历史。
- 向量测试：单元测试使用 fake vector store；端到端索引使用真实 Milvus testcontainer 或 integration tag。
- 隔离测试：证明用户 A 不能读取、修改、删除或召回用户 B 的记忆。
- 故障注入测试：Milvus 超时、Embedding 失败、抽取失败和排序失败。
- 提示词快照测试：证明记忆顺序、标签、边界和当前请求优先级。
- 离线评测集：覆盖画像召回、经历召回、矛盾事实、veto 规则、过期事实和敏感内容拒绝。

## 风险与缓解

| 风险 | 缓解措施 |
|---|---|
| 错误或过期记忆影响回答 | 来源标签、时间权重、supersede 关系、用户纠错、当前请求优先 |
| 向量库跨用户泄露 | 服务端过滤、独立 collection、强制租户/用户条件、隔离测试 |
| 提示词膨胀 | 硬性 token 与条目预算，先排序再渲染 |
| 敏感数据被索引 | MySQL 落库前和 Embedding 前做策略过滤，veto 规则，审计 |
| 异步抽取延迟 | 同步高置信路径、事件状态可见、重试 |
| 重建成本 | 按记忆 ID 幂等 upsert、批量 Embedding、按用户重建 |
| 图记忆扩展复杂 | 推迟到阶段四，在召回质量数据证明缺口后再做 |

## 待确认问题

1. 工程：首次实现是把 `user_memory` 迁移到 `memory_item`，还是新建 `memory_item` 并保留 `user_memory` 兼容视图？建议：通过 Flyway 迁移，并提供兼容服务适配层。
2. 产品：当前产品模型中有哪些项目或工作空间 scope？建议：在项目级聊天上线前先使用 `project_id = null`。
3. 数据：用户记忆使用哪个 Embedding 模型？建议：初期复用已配置的 RAG Embedding 模型，并在每条记忆上记录 `embedding_model` 与 `embedding_version`。
4. 合规：已删除记忆修订记录的保留窗口是多久？建议：出于安全与调试目的保留 30 天，且可配置。
5. 工程：抽取 LLM 调用是否与聊天使用同一 provider？建议：初期使用同一 provider，但配置独立模型标识，以便度量成本与质量。

## 验收标准

- 用户在一个会话中表达的稳定偏好，可以在另一个会话中使用。
- 用户过去规划过的行程，可以通过语义相关问题召回，而不要求精确关键词匹配。
- 结构化画像、约束和 veto 项始终在不做向量检索的情况下加载。
- 注入记忆遵守所有配置的 token 与条目边界。
- 当前明确用户请求在生成回答时优先于已保存偏好。
- 被删除的记忆在删除 SLA 后不再被列出、注入或检索。
- 用户不能通过任何 API、提示词注入路径或向量检索访问他人记忆。
- 向量库与抽取失败不会导致聊天请求失败。
- 所有记忆项都可以从 MySQL 重建到 Milvus。
- 发布前，单元、Repository、隔离、提示词、故障注入和前端构建检查全部通过。
