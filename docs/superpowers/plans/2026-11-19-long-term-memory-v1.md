# 长期记忆 v1 实施计划（两阶段抽取 + 幂等入队）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把“每轮自动抽取 + pinned/episodic 双路召回”收敛为“用户固定提示词 + 用户信息块 ≤600 字 + 事件记忆块 ≤1000 字”三层文本：固定提示词由用户独占维护，两个自动块只由显式关键词和每日增量任务写入，并保证写入幂等。

**Architecture:** 会话记忆继续使用现有 `conversation` / `message` / `ChatMemory`，不参与长期记忆写入。长期记忆新增 `memory_user_prompt`、`memory_block`、`memory_raw_event`、`memory_block_revision`、`memory_consolidation_watermark` 五张表。`memory_user_prompt` 是用户独占的固定提示词，worker 永远不修改；`memory_block` 存自动生成的 PROFILE / EVENT 两块文本，允许用户临时编辑，但后续整理可能重建。写入链路固定为两阶段：先由 `MemoryCandidateExtractor` 从原始事件中抽取“应当长期记住的全局事实”，再由 `MemoryBlockUpdater` 把候选事实加入自动两块文本。每次原始事件带 `event_key` 唯一键，避免重复入队。system 消息丢失问题已修复，因此三层文本合并进同一条 system 角色提示词；不新增第二个用户消息。

**Tech Stack:** Spring Boot 3 / WebFlux / MyBatis-Plus / MySQL / Flyway / Spring AI / React + TypeScript + Vite / JUnit 5 + Mockito

---

## 实施补充（2026-11-19）

实际联调发现“事件状态 DONE 但自动块没有变化”会误导用户。后续实现补了三点：

1. `MemoryBlockUpdater` 由模型基于完整旧记忆和候选事实重写最终文本；同一属性冲突时最新候选优先。程序不维护属性级矛盾规则，只在模型遗漏候选时做补入兜底并限制长度。
2. 新增 V15 事件审计字段：`result_status`、`candidate_json`、`result_json`、`error_message`，区分 `UPDATED / NO_CHANGE / FAILED`。
3. 注入文本明确所有权：用户固定提示词为最高优先级且模型只读；自动记忆允许模型更新、用户也可编辑。纯记忆指令使用独立的“长期记忆任务”样式，不渲染普通 assistant 回复；前端展示整理中、已更新、无变化或失败状态。

---

## 设计决策

### 1. 两阶段，不做“压缩与合并”

长期记忆写入分两次模型调用：

1. **抽取阶段**：只从原始消息里提取“全局、稳定、值得长期记住”的内容，输出 `profileCandidates` / `eventCandidates` / `discarded`。临时行程、本次偏好、敏感信息、重复信息在这一步丢弃。
2. **重写阶段**：把完整旧 `PROFILE` / `EVENT` 内容和候选事实一起交给模型，由模型重写最终两块文本。同一属性值冲突时，最新候选优先，旧值必须删除；用户对自动块的编辑只做 best-effort 保留；固定提示词是另一层，由用户独占维护，worker 不碰。

这样做的目的：

- 第一阶段先过滤“这趟 / 这次 / 本来只对本轮有用”的内容；
- 第二阶段只处理已筛选的事实和既有记忆，不重新发明事实；冲突消解由模型基于全文完成；
- 两阶段都能单独测试，出错时可以明确归因到“抽取错了”还是“加入错了”。

### 2. 幂等性

`memory_raw_event` 增加：

- `event_key VARCHAR(64) NOT NULL`
- `event_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'`
- `attempt_count INT NOT NULL DEFAULT 0`
- `processed_at DATETIME NULL`

唯一键为 `(user_id, event_key)`：

- 显式关键词事件：`event_key = sha256(userId + conversationId + normalizedInput)`
- 每日增量事件：`event_key = "message:" + messageId`

入队使用 `INSERT IGNORE` + 唯一键，重复事件直接跳过；消费时先原子认领为 `PROCESSING`，成功后置 `DONE`，失败回滚为 `PENDING` 并递增 `attempt_count`。同一事件最多重试 3 次，超过后置 `FAILED`。

### 3. 旧数据处理

这是开发环境，不做旧数据兼容：

- V14 迁移中直接 `DROP TABLE IF EXISTS` 旧 `user_memory`、`memory_item`、`memory_ingest_event`、`memory_revision`;
- `db/dev/schema.sql` 同步移除旧表并加入新表；
- `FlywayMigrationTest` 从 13 个迁移改为 15 个迁移；
- 旧 REST 控制器随旧表一起移除；未调用的旧内部服务代码暂时保留，后续单独清理。

### 4. 分层所有权与注入位置

既然 system 通道已经可用，长期记忆应该回到 system 语义：

- 长期记忆是“模型应该知道的背景”，不是用户本轮说的话；
- system 语义能明确表达“这是背景约束”，优先级高于本轮用户输入；
- 继续用 user 角色会让记忆和真实用户输入竞争，尤其用户本轮要求和记忆冲突时更容易出错。

实现上建议**合并进同一条 system 消息**，而不是连续发送两条 system 消息：

```text
{基础 Agent 提示词}

[长期记忆]
用户信息：
...

事件记忆：
...
```

这样对模型网关和不同 provider 都更稳，也避免多 system 消息被合并或重排的兼容性问题。

所有权拆成三层：

1. **用户固定提示词**：
   - 存 `memory_user_prompt`;
   - 只由用户通过 API / 前端维护；
   - worker 永远不修改；
   - 重要规则、硬性偏好、身份信息建议放这里；
   - 这是唯一有“不可被自动整理覆盖”承诺的层。

2. **自动 PROFILE / EVENT 块**：
   - 存 `memory_block`;
   - 正常由 worker 更新；
   - 用户也可以直接编辑；
   - `generated_edited_at` 非空时，下一次更新会尽量保留用户编辑，但不做永久锁定承诺；
   - 后续整理、冲突合并或字数超限时可能重建，revision 提供回滚。

3. **原始事件**：
   - 存 `memory_raw_event`;
   - append-only + 幂等；
   - 用于重放、审计和回滚。

自动块的用户编辑保留策略是 **best-effort**：

- 提示词明确要求保留与最新候选不冲突的 `generated_edited_at` 用户编辑；
- 如果旧编辑与最新候选冲突，模型必须用最新候选替换旧值；
- 如果整体超限，按长度上限裁剪，并写 revision；
- 前端在自动块编辑器上明确提示：自动块可编辑，但重要内容请放入固定提示词。

### 5. 每日任务的 toast 语义

只比较 `version` 无法知道“这次变化是用户这轮触发的，还是凌晨每日任务触发的”。因此：

- `memory_block` 增加 `last_change_source`;
- `memory_block_revision` 增加 `change_source`;
- `MemoryBlockVO` 返回 `changeSource`;
- 前端只对 `KEYWORD` 和 `AUTO_CONSOLIDATION` 且发生在本轮之后的变化弹 toast;
- `DAILY` 变化不弹 toast，只在记忆页显示“最近自动整理时间”。

---

## 文件结构

### 新增

| 文件 | 职责 |
|---|---|
| `agent-core/src/main/resources/db/migration/V14__create_long_term_memory_blocks.sql` | 五张新表 + 清理旧表 |
| `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryUserPromptEntity.java` | 用户固定提示词实体 |
| `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryBlockEntity.java` | 两个自动块实体 |
| `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryRawEventEntity.java` | 原始事件实体 |
| `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryBlockRevisionEntity.java` | 块修订实体 |
| `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryWatermarkEntity.java` | 每日水位实体 |
| `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryBlockMapper.java` | 块 Mapper |
| `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryUserPromptMapper.java` | 固定提示词 Mapper |
| `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryRawEventMapper.java` | 原始事件 Mapper |
| `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryBlockRevisionMapper.java` | 修订 Mapper |
| `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryWatermarkMapper.java` | 水位 Mapper |
| `agent-core/src/main/java/uno/zhuchen/agent/persistence/query/MessageQuery.java` | 每日增量消息查询 |
| `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockType.java` | `PROFILE` / `EVENT` 枚举 |
| `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryKeywordTrigger.java` | 显式关键词正则 |
| `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryRawEventService.java` | 幂等入队 |
| `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryCandidateExtractor.java` | 第一阶段：抽取全局候选事实 |
| `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockUpdater.java` | 第二阶段：把候选加入两个自动块 |
| `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockWorker.java` | 10 秒轮询未处理事件 |
| `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryDailyConsolidator.java` | 每日 02:00 增量扫描 |
| `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/LongTermMemoryContextBuilder.java` | 固定提示词 + 两个自动块 → system context |
| `agent-core/src/main/java/uno/zhuchen/agent/controller/MemoryBlockController.java` | 块读取/编辑/历史/回滚 |
| `agent-core/src/main/java/uno/zhuchen/agent/domain/vo/MemoryBlockVO.java` | 前端块视图 |
| `agent-core/src/main/java/uno/zhuchen/agent/domain/vo/MemoryPromptVO.java` | 固定提示词视图 |
| `frontend/src/hooks/useMemoryBlocks.ts` | 前端块 API 封装 |
| `frontend/src/components/MemoryBlockEditor.tsx` | 单块编辑器 |
| `frontend/src/components/MemoryPromptEditor.tsx` | 用户固定提示词编辑器 |

### 修改

| 文件 | 改动 |
|---|---|
| `agent-core/src/main/java/uno/zhuchen/agent/core/agent/ReactAgent.java` | `remember()` 只调关键词触发；注入改用三层文本 |
| `agent-core/src/main/java/uno/zhuchen/agent/core/multi/MultiAgentManager.java` | 同上 |
| `agent-core/src/main/java/uno/zhuchen/workflow/builder/GraphStreamRunner.java` | 同上 |
| `agent-core/src/main/java/uno/zhuchen/agent/core/agent/AgentState.java` | 背景块改为 system context |
| `agent-core/src/main/java/uno/zhuchen/workflow/agent/ManagerAgent.java` | Graph 首轮改为把记忆并入 system 提示词 |
| `agent-core/src/main/resources/application.yml` | 新增 `app.memory.blocks.*`，关闭旧链路 |
| `agent-core/src/main/resources/db/dev/schema.sql` | 删除旧表，加入新表 |
| `agent-core/src/test/java/uno/zhuchen/agent/persistence/FlywayMigrationTest.java` | 断言 V14 与新表 |
| `frontend/src/components/MemoryPanel.tsx` | 改为固定提示词 + 两个自动块编辑器 |
| `frontend/src/App.tsx` | 记忆反馈改为 `changeSource + version` |
| `frontend/src/types/index.ts` | 新增 `MemoryBlock` 类型 |

---

## Task 1: 建表与实体

**Files:**

- Create: `agent-core/src/main/resources/db/migration/V14__create_long_term_memory_blocks.sql`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryUserPromptEntity.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryBlockEntity.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryRawEventEntity.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryBlockRevisionEntity.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryWatermarkEntity.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryBlockMapper.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryUserPromptMapper.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryRawEventMapper.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryBlockRevisionMapper.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryWatermarkMapper.java`
- Modify: `agent-core/src/main/resources/db/dev/schema.sql`
- Modify: `agent-core/src/test/java/uno/zhuchen/agent/persistence/FlywayMigrationTest.java`

### Step 1: 写 V14 迁移 SQL

```sql
DROP TABLE IF EXISTS memory_revision;
DROP TABLE IF EXISTS memory_ingest_event;
DROP TABLE IF EXISTS memory_item;
DROP TABLE IF EXISTS user_memory;

CREATE TABLE memory_block (
    id                  VARCHAR(64)  NOT NULL PRIMARY KEY,
    user_id             VARCHAR(64)  NOT NULL,
    block_type          VARCHAR(16)  NOT NULL,
    content             TEXT         NOT NULL,
    char_count          INT          NOT NULL DEFAULT 0,
    version             INT          NOT NULL DEFAULT 0,
    generated_edited_at DATETIME     NULL,
    last_change_source  VARCHAR(24)  NOT NULL DEFAULT 'INIT',
    last_changed_at     DATETIME     NULL,
    created_at          DATETIME     NOT NULL,
    updated_at          DATETIME     NOT NULL,
    UNIQUE KEY uk_memory_block (user_id, block_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE memory_user_prompt (
    user_id     VARCHAR(64) NOT NULL PRIMARY KEY,
    content     TEXT        NOT NULL,
    char_count  INT         NOT NULL DEFAULT 0,
    version     INT         NOT NULL DEFAULT 0,
    updated_at  DATETIME    NOT NULL,
    created_at  DATETIME    NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE memory_raw_event (
    id              VARCHAR(64)   NOT NULL PRIMARY KEY,
    user_id         VARCHAR(64)   NOT NULL,
    conversation_id VARCHAR(64)   NULL,
    message_id      BIGINT        NULL,
    event_key       VARCHAR(64)   NOT NULL,
    content         VARCHAR(2000) NOT NULL,
    source          VARCHAR(16)   NOT NULL,
    event_status    VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    attempt_count   INT           NOT NULL DEFAULT 0,
    processed_at    DATETIME      NULL,
    created_at      DATETIME      NOT NULL,
    UNIQUE KEY uk_raw_event_key (user_id, event_key),
    KEY idx_raw_event_pending (event_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE memory_block_revision (
    id            VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id       VARCHAR(64) NOT NULL,
    block_type    VARCHAR(16) NOT NULL,
    old_content   TEXT        NULL,
    new_content   TEXT        NOT NULL,
    change_source VARCHAR(16) NOT NULL,
    created_at    DATETIME    NOT NULL,
    KEY idx_block_revision (user_id, block_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE memory_consolidation_watermark (
    user_id         VARCHAR(64) NOT NULL PRIMARY KEY,
    last_message_id BIGINT      NULL,
    last_run_at     DATETIME    NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### Step 2: 更新 dev schema

在 `agent-core/src/main/resources/db/dev/schema.sql` 中删除：

```sql
user_memory
memory_item
memory_ingest_event
memory_revision
```

并加入与 V14 相同的五张新表。dev profile 不走 Flyway，必须靠这份 schema 初始化。

### Step 3: 写五个实体

`MemoryUserPromptEntity`：

```java
@Data
@TableName("memory_user_prompt")
public class MemoryUserPromptEntity {
    @TableId(type = IdType.INPUT)
    private String userId;
    private String content;
    private Integer charCount;
    private Integer version;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
}
```

`MemoryBlockEntity`：

```java
@Data
@TableName("memory_block")
public class MemoryBlockEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private String userId;
    private String blockType;
    private String content;
    private Integer charCount;
    private Integer version;
    private LocalDateTime generatedEditedAt;
    private String lastChangeSource;
    private LocalDateTime lastChangedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

`MemoryRawEventEntity` 增加：

```java
private String eventKey;
private String eventStatus;
private Integer attemptCount;
```

其余字段与 SQL 一一对应。

### Step 4: 写五个 Mapper

```java
@Mapper
public interface MemoryBlockMapper extends BaseMapper<MemoryBlockEntity> {
}
```

其余四个同构。

### Step 5: 修改 Flyway 测试

把 `FlywayMigrationTest` 中所有 `13` 改为 `14`，版本列表改为 `V1..V14`，并补充断言：

```java
assertTrue(tableExists("memory_block"));
assertTrue(tableExists("memory_user_prompt"));
assertTrue(tableExists("memory_raw_event"));
assertTrue(tableExists("memory_block_revision"));
assertTrue(tableExists("memory_consolidation_watermark"));

assertFalse(tableExists("user_memory"));
assertFalse(tableExists("memory_item"));
assertFalse(tableExists("memory_ingest_event"));
assertFalse(tableExists("memory_revision"));
```

### Step 6: 跑迁移测试

Run:

```powershell
cd agent-core
mvn -o -Dtest='FlywayMigrationTest' -DfailIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`，日志出现 `Successfully applied 1 migration ... version "14"`。

### Step 7: 提交

```bash
git add agent-core/src/main/resources/db/migration/V14__create_long_term_memory_blocks.sql \
        agent-core/src/main/resources/db/dev/schema.sql \
        agent-core/src/test/java/uno/zhuchen/agent/persistence/FlywayMigrationTest.java \
        agent-core/src/main/java/uno/zhuchen/agent/domain/entity/ \
        agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/
git commit -m "feat(memory): add two-stage long-term memory schema"
```

---

## Task 2: 显式关键词触发与幂等入队

**Files:**

- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryKeywordTrigger.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryRawEventService.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/agent/ReactAgent.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/multi/MultiAgentManager.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/workflow/builder/GraphStreamRunner.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/core/memory/block/MemoryRawEventServiceTest.java`

### Step 1: 写关键词与幂等测试

```java
class MemoryRawEventServiceTest {

    @Test
    void explicitKeywordUsesStableEventKey() {
        MemoryRawEventMapper mapper = mock(MemoryRawEventMapper.class);
        MemoryRawEventService service = new MemoryRawEventService(mapper);

        String key1 = service.eventKey("u1", "conv-1", "记住：我不吃辣", "KEYWORD");
        String key2 = service.eventKey("u1", "conv-1", "记住：我不吃辣", "KEYWORD");
        String key3 = service.eventKey("u1", "conv-1", "记住：我不吃辣 ", "KEYWORD");

        assertEquals(key1, key2);
        assertEquals(key1, key3);
    }

    @Test
    void duplicateEventKeyIsIgnored() {
        MemoryRawEventMapper mapper = mock(MemoryRawEventMapper.class);
        when(mapper.insertIgnore(any())).thenReturn(0);

        MemoryRawEventService service = new MemoryRawEventService(mapper);
        String id = service.appendKeyword("u1", "conv-1", "记住：我不吃辣");

        assertNull(id);
        verify(mapper, never()).insert(any());
    }
}
```

### Step 2: 实现显式关键词触发器

```java
public final class MemoryKeywordTrigger {
    private static final Pattern KEYWORD =
            Pattern.compile("(记住|记一下|帮我记住|记下来|请记住)");

    private MemoryKeywordTrigger() {
    }

    public static boolean matches(String message) {
        return message != null && KEYWORD.matcher(message).find();
    }
}
```

### Step 3: 实现幂等事件服务

```java
@Service
public class MemoryRawEventService {

    private final MemoryRawEventMapper mapper;

    public MemoryRawEventService(MemoryRawEventMapper mapper) {
        this.mapper = mapper;
    }

    public String appendKeyword(String userId, String conversationId, String content) {
        String normalized = content == null ? "" : content.trim();
        if (userId == null || normalized.isBlank()) {
            return null;
        }
        return insert(userId, conversationId, null,
                eventKey(userId, conversationId, normalized, "KEYWORD"),
                normalized, "KEYWORD");
    }

    public String appendDaily(String userId, String conversationId, Long messageId, String content) {
        String normalized = content == null ? "" : content.trim();
        if (userId == null || messageId == null || normalized.isBlank()) {
            return null;
        }
        return insert(userId, conversationId, messageId,
                "message:" + messageId,
                truncate(normalized, 2000), "DAILY");
    }

    String eventKey(String userId, String conversationId, String content, String source) {
        String normalized = content == null ? "" : content.trim();
        String raw = userId + "|" + conversationId + "|" + source + "|" + normalized;
        return DigestUtils.sha256Hex(raw);
    }

    private String insert(String userId, String conversationId, Long messageId,
                          String eventKey, String content, String source) {
        MemoryRawEventEntity entity = new MemoryRawEventEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(userId);
        entity.setConversationId(conversationId);
        entity.setMessageId(messageId);
        entity.setEventKey(eventKey);
        entity.setContent(content);
        entity.setSource(source);
        entity.setEventStatus("PENDING");
        entity.setAttemptCount(0);
        entity.setCreatedAt(LocalDateTime.now());

        int inserted = mapper.insertIgnore(entity);
        return inserted > 0 ? entity.getId() : null;
    }
}
```

`MemoryRawEventMapper` 增加 `INSERT IGNORE`：

```java
@Insert("INSERT IGNORE INTO memory_raw_event "
        + "(id, user_id, conversation_id, message_id, event_key, content, source, event_status, attempt_count, created_at) "
        + "VALUES (#{id}, #{userId}, #{conversationId}, #{messageId}, #{eventKey}, #{content}, #{source}, #{eventStatus}, #{attemptCount}, #{createdAt})")
int insertIgnore(MemoryRawEventEntity entity);
```

### Step 4: 改三条聊天链路的 remember

```java
private void remember(String userId, String conversationId, String userInput) {
    try {
        if (!MemoryKeywordTrigger.matches(userInput)) {
            return;
        }
        memoryRawEventService.appendKeyword(userId, conversationId, userInput);
    } catch (Exception e) {
        log.warn("长期记忆入队失败: userId={}, conversationId={}, error={}",
                userId, conversationId, e.getMessage());
    }
}
```

`ReactAgent`、`MultiAgentManager`、`GraphStreamRunner` 均替换为同一逻辑。

### Step 5: 跑测试

Run:

```powershell
cd agent-core
mvn -o -Dtest=MemoryRawEventServiceTest test
```

Expected: `Tests run: 2, Failures: 0`。

### Step 6: 提交

```bash
git add agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/ \
        agent-core/src/main/java/uno/zhuchen/agent/core/agent/ReactAgent.java \
        agent-core/src/main/java/uno/zhuchen/agent/core/multi/MultiAgentManager.java \
        agent-core/src/main/java/uno/zhuchen/workflow/builder/GraphStreamRunner.java
git commit -m "feat(memory): enqueue explicit memories idempotently"
```

---

## Task 3: 两阶段抽取与加入

**Files:**

- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockType.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryCandidateExtractor.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockUpdater.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockService.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/core/memory/block/MemoryCandidateExtractorTest.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/core/memory/block/MemoryBlockUpdaterTest.java`

### Step 1: 定义类型与上限

```java
public enum MemoryBlockType {
    PROFILE(600),
    EVENT(1000);

    private final int maxChars;

    MemoryBlockType(int maxChars) {
        this.maxChars = maxChars;
    }

    public int maxChars() {
        return maxChars;
    }
}
```

### Step 2: 写第一阶段测试

```java
class MemoryCandidateExtractorTest {

    @Test
    void extractsGlobalFactAndDiscardsTripScopedFact() {
        ModelRouter router = mock(ModelRouter.class);
        when(router.route(any())).thenReturn(fixedModel("""
                {
                  "profileCandidates": ["不吃辣"],
                  "eventCandidates": [],
                  "discarded": ["这次带老人出行"]
                }
                """));

        MemoryCandidateExtractor extractor =
                new MemoryCandidateExtractor(router, new ObjectMapper());

        MemoryCandidateResult result = extractor.extract(
                List.of("记住：我不吃辣，这次带老人出行"));

        assertEquals(List.of("不吃辣"), result.profileCandidates());
        assertTrue(result.eventCandidates().isEmpty());
        assertTrue(result.discarded().contains("这次带老人出行"));
    }
}
```

### Step 3: 实现第一阶段抽取器

提示词：

```text
你是长期记忆抽取器。输入是一组用户消息。
只抽取“全局、稳定、长期有效”的事实，输出严格 JSON：
{"profileCandidates":[],"eventCandidates":[],"discarded":[]}

规则：
1. profileCandidates 只放长期稳定信息：常住城市、长期饮食偏好、长期同行人、长期预算习惯
2. eventCandidates 只放已经发生或明确计划的具体经历，每条一行
3. discarded 放本次行程、临时状态、工具查询、寒暄、重复内容、敏感信息
4. 禁止新增原文没有的事实
5. 不要输出解释，不要用代码块包裹
```

返回值：

```java
public record MemoryCandidateResult(
        List<String> profileCandidates,
        List<String> eventCandidates,
        List<String> discarded
) {
}
```

### Step 4: 写第二阶段测试

```java
class MemoryBlockUpdaterTest {

    @Test
    void bestEffortPreservesEditedGeneratedContentWhenItFits() {
        ModelRouter router = mock(ModelRouter.class);
        when(router.route(any())).thenReturn(fixedModel(
                "{\"profile\":\"不吃香菜\",\"events\":\"\"}"));

        MemoryBlockUpdater updater =
                new MemoryBlockUpdater(router, new ObjectMapper());

        MemoryBlockUpdateResult result = updater.update(
                "用户编辑：不吃辣",
                "",
                new MemoryCandidateResult(List.of("不吃香菜"), List.of(), List.of()),
                true,
                "KEYWORD");

        assertTrue(result.profile().contains("用户编辑：不吃辣"));
        assertTrue(result.profile().contains("不吃香菜"));
        assertTrue(result.generatedEditPreserved());
    }

    @Test
    void mayRebuildEditedGeneratedContentWhenLimitConflicts() {
        ModelRouter router = mock(ModelRouter.class);
        when(router.route(any())).thenReturn(fixedModel(
                "{\"profile\":\"" + "新".repeat(600) + "\",\"events\":\"\"}"));

        MemoryBlockUpdater updater =
                new MemoryBlockUpdater(router, new ObjectMapper());

        MemoryBlockUpdateResult result = updater.update(
                "用户编辑：" + "旧".repeat(100),
                "",
                new MemoryCandidateResult(List.of(), List.of(), List.of()),
                true,
                "KEYWORD");

        assertFalse(result.generatedEditPreserved());
        assertTrue(result.profile().contains("新"));
    }
}
```

### Step 5: 实现第二阶段更新器

提示词：

```text
你是长期记忆写入器。输入是当前两块记忆和已筛选的候选事实。
基于完整旧记忆和候选事实重写最终记忆，输出严格 JSON：
{"profile":"...","events":"..."}

规则：
1. 候选事实优先级最高；同一属性冲突时删除旧值，只保留最新候选
2. 输出完整最终记忆，不要追加或拼接旧全文
3. 若 `generatedEdited=true`，保留与最新候选不冲突的用户编辑
4. 只能使用旧记忆或候选事实中已有内容，禁止新增事实
5. profile 不超过 600 字，events 不超过 1000 字
6. events 每条一行，行尾保留日期
7. 不要输出解释，不要用代码块包裹
```

自动块用户编辑的 best-effort 保留由重写提示词承担：不冲突的旧编辑保留，冲突旧值由最新候选替换。程序只做候选遗漏兜底和长度限制，不做属性级正则矛盾判断。

返回值：

```java
public record MemoryBlockUpdateResult(
        String profile,
        String events,
        boolean generatedEditPreserved
) {
}
```

超限时按普通自动块处理：

```java
static String fit(String content, MemoryBlockType type) {
    String text = content == null ? "" : content.trim();
    return text.length() <= type.maxChars()
            ? text
            : text.substring(0, type.maxChars());
}
```

### Step 6: 跑测试

Run:

```powershell
cd agent-core
mvn -o -Dtest='MemoryCandidateExtractorTest,MemoryBlockUpdaterTest' test
```

Expected: `Tests run: 3, Failures: 0`。

### Step 7: 提交

```bash
git add agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/ \
        agent-core/src/test/java/uno/zhuchen/agent/core/memory/block/
git commit -m "feat(memory): add two-stage candidate extraction and block update"
```

---

## Task 4: 后台消费与每日增量

**Files:**

- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockWorker.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryDailyConsolidator.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/persistence/query/MessageQuery.java`
- Modify: `agent-core/src/main/resources/application.yml`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/core/memory/block/MemoryBlockWorkerTest.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/core/memory/block/MemoryDailyConsolidatorTest.java`

### Step 1: 配置

```yaml
app:
  memory:
    blocks:
      enabled: ${MEMORY_BLOCKS_ENABLED:true}
      consolidate-interval-ms: 10000
      daily-cron: "0 0 2 * * *"
      daily-timezone: "Asia/Shanghai"
      daily-min-new-messages: 5
      user-prompt-max-chars: 600
      profile-max-chars: 600
      event-max-chars: 1000
      max-attempts: 3
```

### Step 2: 写 worker 测试

```java
class MemoryBlockWorkerTest {

    @Test
    void processesPendingEventsInUserBatches() {
        MemoryBlockService service = mock(MemoryBlockService.class);
        MemoryBlockWorker worker = new MemoryBlockWorker(service, 50);

        worker.run();

        verify(service).processPending(50);
    }
}
```

### Step 3: 写 worker

```java
@Component
@ConditionalOnProperty(name = "app.memory.blocks.enabled",
        havingValue = "true", matchIfMissing = true)
public class MemoryBlockWorker {

    private final MemoryBlockService service;
    private final int batchSize;

    public MemoryBlockWorker(MemoryBlockService service,
                             @Value("${app.memory.blocks.batch-size:50}") int batchSize) {
        this.service = service;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${app.memory.blocks.consolidate-interval-ms:10000}")
    public void run() {
        try {
            service.processPending(batchSize);
        } catch (Exception e) {
            log.warn("[memory-block] 消费失败: {}", e.getMessage());
        }
    }
}
```

`processPending` 流程：

1. 取 `event_status='PENDING'` 且 `attempt_count < 3` 的事件，按 user 分组；
2. 原子更新为 `PROCESSING`;
3. 第一阶段抽取候选；
4. 第二阶段更新两个自动块；
5. 成功后写 revision、置 `DONE`;
6. 失败后回滚为 `PENDING`，`attempt_count + 1`;
7. 超过 3 次置 `FAILED`。

### Step 4: 写每日任务测试

```java
class MemoryDailyConsolidatorTest {

    @Test
    void skipsUsersWithLessThanFiveNewMessages() {
        when(watermarkService.activeUserIds()).thenReturn(List.of("u1"));
        when(messageQuery.newUserMessagesAfter("u1", 10L, 200))
                .thenReturn(List.of(msg(11L), msg(12L), msg(13L), msg(14L)));

        daily.dailyRun();

        verify(rawEventService, never()).appendDaily(any(), any(), any(), any());
        verify(watermarkService).touch(eq("u1"), eq(10L), any());
    }

    @Test
    void appendsFiveNewMessagesOnce() {
        when(watermarkService.activeUserIds()).thenReturn(List.of("u1"));
        when(messageQuery.newUserMessagesAfter("u1", 10L, 200))
                .thenReturn(List.of(msg(11L), msg(12L), msg(13L), msg(14L), msg(15L)));

        daily.dailyRun();

        verify(rawEventService, times(5)).appendDaily(eq("u1"), any(), anyLong(), any());
        verify(watermarkService).touch(eq("u1"), eq(15L), any());
    }
}
```

### Step 5: 写每日任务

```java
@Scheduled(cron = "${app.memory.blocks.daily-cron:0 0 2 * * *}",
        zone = "${app.memory.blocks.daily-timezone:Asia/Shanghai}")
public void dailyRun() {
    for (String userId : watermarkService.activeUserIds()) {
        Long lastId = watermarkService.lastMessageId(userId);
        List<MessageEntity> fresh = messageQuery.newUserMessagesAfter(userId, lastId, 200);

        if (fresh.size() < minNewMessages) {
            watermarkService.touch(userId, lastId, LocalDateTime.now());
            continue;
        }

        for (MessageEntity message : fresh) {
            rawEventService.appendDaily(
                    userId,
                    message.getConversationId(),
                    message.getId(),
                    message.getContent());
        }
        watermarkService.touch(
                userId,
                fresh.get(fresh.size() - 1).getId(),
                LocalDateTime.now());
    }
}
```

### Step 6: 跑测试

Run:

```powershell
cd agent-core
mvn -o -Dtest='MemoryBlockWorkerTest,MemoryDailyConsolidatorTest' test
```

Expected: `Tests run: 3, Failures: 0`。

### Step 7: 提交

```bash
git add agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/ \
        agent-core/src/main/java/uno/zhuchen/agent/persistence/query/MessageQuery.java \
        agent-core/src/main/resources/application.yml \
        agent-core/src/test/java/uno/zhuchen/agent/core/memory/block/
git commit -m "feat(memory): add idempotent worker and daily incremental scan"
```

---

## Task 5: 注入固定提示词与两个自动块

**Files:**

- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/LongTermMemoryContextBuilder.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/agent/ReactAgent.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/agent/AgentState.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/multi/MultiAgentManager.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/workflow/builder/GraphStreamRunner.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/workflow/agent/ManagerAgent.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/core/memory/block/LongTermMemoryContextBuilderTest.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/core/agent/AgentStateTest.java`

### Step 1: 写渲染器

```java
@Component
public class LongTermMemoryContextBuilder {

    private final MemoryBlockService blockService;

    public LongTermMemoryContextBuilder(MemoryBlockService blockService) {
        this.blockService = blockService;
    }

    public String render(String userId) {
        String userPrompt = blockService.userPrompt(userId);
        String profile = blockService.content(userId, MemoryBlockType.PROFILE);
        String events = blockService.content(userId, MemoryBlockType.EVENT);

        if (isBlank(userPrompt) && isBlank(profile) && isBlank(events)) {
            return null;
        }

        StringBuilder b = new StringBuilder("[长期记忆]\n");
        if (!isBlank(userPrompt)) {
            b.append("[用户固定提示词]\n")
                    .append(userPrompt.trim())
                    .append("\n\n");
        }
        if (!isBlank(profile)) {
            b.append("[自动记忆] 用户信息：\n")
                    .append(profile.trim())
                    .append("\n\n");
        }
        if (!isBlank(events)) {
            b.append("[自动记忆] 事件记忆：\n")
                    .append(events.trim())
                    .append("\n");
        }
        return b.toString().trim();
    }
}
```

### Step 2: 替换三条链路的注入

先把 `AgentState` 的背景字段改成 system context，并在组装消息时合并进基础 system 提示词：

```java
private String systemContext;

public void setSystemContext(String systemContext) {
    this.systemContext = systemContext;
}

public List<Message> getFullMessages() {
    List<Message> full = new ArrayList<>();

    String prompt = systemPrompt.getText();
    if (systemContext != null && !systemContext.isBlank()) {
        prompt = prompt + "\n\n" + systemContext;
    }
    full.add(new SystemMessage(prompt));
    full.addAll(messages);
    return full;
}
```

`ReactAgent` 与 `MultiAgentManager`：

```java
private void injectLongTermMemory(AgentState state, String userId) {
    String block = longTermMemoryContextBuilder.render(userId);
    if (block != null && !block.isBlank()) {
        state.setSystemContext(block);
    }
    log.debug("[memory-inject] userId={}, systemContextChars={}",
            userId, block == null ? 0 : block.length());
}
```

Graph 首轮当前是把记忆追加到 `rawRequest`，这里要改为并入 `SYSTEM_PROMPT_EXTRACT`：

```java
String managerSystemPrompt = SYSTEM_PROMPT_EXTRACT;
if (!memorySection.isBlank()) {
    managerSystemPrompt = managerSystemPrompt + "\n\n" + memorySection;
}

String extractJson = callLLMWithTools(
        traceId,
        managerSystemPrompt,
        rawRequest + "\n(conversationId=" + conversationId + ")",
        new ToolCallback[]{askUserToolCallback},
        LLM_MAX_MANAGER_FIRST_ROUNDS);
```

同时删除这行：

```java
rawRequest = rawRequest + "\n\n" + memorySection;
```

三条链路都不再调用 `HybridMemoryPromptRenderer` 或 `MemoryPromptRenderer`。

### Step 3: 写渲染测试

```java
class LongTermMemoryContextBuilderTest {

    @Test
    void rendersUserPromptAndAutoBlocks() {
        when(blockService.userPrompt("u1")).thenReturn("回复保持简洁，不要 emoji");
        when(blockService.content("u1", MemoryBlockType.PROFILE))
                .thenReturn("用户叫张三，性别男");
        when(blockService.content("u1", MemoryBlockType.EVENT))
                .thenReturn("2025-11-07 去北京玩了三天");

        String text = builder.render("u1");

        assertTrue(text.contains("[长期记忆]"));
        assertTrue(text.contains("[用户固定提示词]"));
        assertTrue(text.contains("回复保持简洁"));
        assertTrue(text.contains("[自动记忆] 用户信息"));
        assertTrue(text.contains("[自动记忆] 事件记忆"));
    }
}
```

`AgentStateTest` 补一条断言，确保长期记忆确实进入 system 而不是 user：

```java
@Test
void longTermMemoryIsMergedIntoSystemPrompt() {
    AgentState state = new AgentState("u1", "conv-1", "你是旅游助手", "帮我规划行程");
    state.setSystemContext("[长期记忆]\n用户信息：不吃辣");

    List<Message> messages = state.getFullMessages();

    assertEquals(MessageType.SYSTEM, messages.get(0).getMessageType());
    assertTrue(messages.get(0).getText().contains("你是旅游助手"));
    assertTrue(messages.get(0).getText().contains("[长期记忆]"));
    assertTrue(messages.get(1).getText().contains("帮我规划行程"));
}
```

### Step 4: 跑测试

Run:

```powershell
cd agent-core
mvn -o -Dtest='LongTermMemoryContextBuilderTest,AgentStateTest' test
```

Expected: `Tests run: 2, Failures: 0`。

### Step 5: 提交

```bash
git add agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/LongTermMemoryContextBuilder.java \
        agent-core/src/main/java/uno/zhuchen/agent/core/agent/AgentState.java \
        agent-core/src/main/java/uno/zhuchen/agent/core/agent/ReactAgent.java \
        agent-core/src/main/java/uno/zhuchen/agent/core/multi/MultiAgentManager.java \
        agent-core/src/main/java/uno/zhuchen/workflow/builder/GraphStreamRunner.java \
        agent-core/src/main/java/uno/zhuchen/workflow/agent/ManagerAgent.java
git commit -m "feat(memory): inject memory blocks via system context"
```

---

## Task 6: 块读写 API

**Files:**

- Create: `agent-core/src/main/java/uno/zhuchen/agent/controller/MemoryBlockController.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/vo/MemoryBlockVO.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/vo/MemoryPromptVO.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockService.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/controller/MemoryBlockControllerTest.java`

### Step 1: 定义接口

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/memory/prompt` | 用户固定提示词 |
| GET | `/api/memory/blocks` | 两块自动文本 + 字数 + 上限 + version + changeSource + updatedAt |
| PUT | `/api/memory/prompt` | 保存用户固定提示词，写 revision；worker 永不修改 |
| PUT | `/api/memory/blocks/{type}` | 保存自动块，写 revision，置 `generated_edited_at` |
| GET | `/api/memory/prompt/revisions` | 固定提示词最近 20 条历史 |
| GET | `/api/memory/blocks/{type}/revisions` | 自动块最近 20 条历史 |
| POST | `/api/memory/prompt/rollback/{revisionId}` | 回滚固定提示词 |
| POST | `/api/memory/blocks/{type}/rollback/{revisionId}` | 回滚到指定版本 |

### Step 2: VO

```java
public record MemoryBlockVO(
        String type,
        String content,
        int charCount,
        int maxChars,
        int version,
        String changeSource,
        LocalDateTime changedAt,
        LocalDateTime updatedAt,
        boolean generatedEdited
) {
}
```

`MemoryPromptVO`：

```java
public record MemoryPromptVO(
        String content,
        int charCount,
        int maxChars,
        int version,
        LocalDateTime updatedAt
) {
}
```

`MemoryBlockService` 的分层写入契约：

```java
public String userPrompt(String userId);

public void saveUserPrompt(String userId, String content);

public void saveGeneratedBlock(String userId, MemoryBlockType type, String content);

public String content(String userId, MemoryBlockType type);
```

实现要求：

- `saveUserPrompt()` 只写 `memory_user_prompt` 和 `memory_block_revision(block_type='USER_PROMPT')`;
- `saveGeneratedBlock()` 只写 `memory_block`，并设置 `generated_edited_at`;
- `processPending()` 只允许调用 `MemoryBlockUpdater.update()`，不允许写 `memory_user_prompt`;
- 固定提示词和自动块的每次变更都写 revision。

### Step 3: 手动保存校验

```java
if (content.length() > type.maxChars()) {
    throw new IllegalArgumentException(
            type.name().toLowerCase() + " 超过上限 " + type.maxChars() + " 字");
}

if (prompt.length() > USER_PROMPT_MAX_CHARS) {
    throw new IllegalArgumentException("用户固定提示词超过上限 " + USER_PROMPT_MAX_CHARS + " 字");
}
```

### Step 4: 写控制器测试

```java
class MemoryBlockControllerTest {

    @Test
    void savesUserPromptWithoutTouchingAutoBlocks() {
        mockMvc.perform(put("/api/memory/prompt")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"回复保持简洁\"}"))
                .andExpect(status().isOk());

        verify(service).saveUserPrompt("u1", "回复保持简洁");
        verify(service, never()).saveGeneratedBlock(any(), any(), any());
    }

    @Test
    void rejectsOversizedProfileEdit() {
        mockMvc.perform(put("/api/memory/blocks/profile")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"" + "长".repeat(601) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generatedEditMarksBlockAsGeneratedEdited() {
        mockMvc.perform(put("/api/memory/blocks/profile")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"自动块用户编辑：不吃辣\"}"))
                .andExpect(status().isOk());

        verify(service).saveGeneratedBlock(
                "u1", MemoryBlockType.PROFILE, "自动块用户编辑：不吃辣");
    }
}
```

### Step 5: 跑测试

Run:

```powershell
cd agent-core
mvn -o -Dtest=MemoryBlockControllerTest test
```

Expected: `Tests run: 3, Failures: 0`。

### Step 6: 提交

```bash
git add agent-core/src/main/java/uno/zhuchen/agent/controller/MemoryBlockController.java \
        agent-core/src/main/java/uno/zhuchen/agent/domain/vo/MemoryBlockVO.java \
        agent-core/src/main/java/uno/zhuchen/agent/domain/vo/MemoryPromptVO.java \
        agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockService.java \
        agent-core/src/test/java/uno/zhuchen/agent/controller/MemoryBlockControllerTest.java
git commit -m "feat(memory): expose editable block APIs"
```

---

## Task 7: 前端固定提示词与自动块编辑器

**Files:**

- Create: `frontend/src/hooks/useMemoryBlocks.ts`
- Create: `frontend/src/components/MemoryBlockEditor.tsx`
- Create: `frontend/src/components/MemoryPromptEditor.tsx`
- Modify: `frontend/src/components/MemoryPanel.tsx`
- Modify: `frontend/src/types/index.ts`

### Step 1: 类型

```ts
export interface MemoryPrompt {
  content: string
  charCount: number
  maxChars: number
  version: number
  updatedAt: string
}

export interface MemoryBlock {
  type: 'profile' | 'event'
  content: string
  charCount: number
  maxChars: number
  version: number
  changeSource: 'INIT' | 'KEYWORD' | 'AUTO_CONSOLIDATION' | 'DAILY' | 'MANUAL'
  changedAt?: string
  updatedAt: string
  generatedEdited: boolean
}
```

### Step 2: 固定提示词编辑器

`MemoryPromptEditor` 要求：

- 标题显示“用户固定提示词”
- 副标题显示“由你维护，自动整理不会修改”
- `<textarea>` 受控，实时字数 `charCount/maxChars`
- 超过 90% 变琥珀色，超过上限禁用保存并标红
- 保存调用 `PUT /api/memory/prompt`
- “历史”按钮展开最近 20 条固定提示词历史，可回滚

```tsx
<p className="text-[11px] text-charcoal-400">
  这里放每次都必须遵守的规则。自动整理不会修改这一层。
</p>
```

### Step 3: 自动块编辑器

`MemoryBlockEditor` 要求：

- 标题显示“自动用户信息”或“自动事件记忆”
- 副标题显示“可编辑，但后续自动整理可能重建；重要内容请放入固定提示词”
- 超过 90% 变琥珀色，超过上限禁用保存并标红
- 保存按钮调用 `PUT /api/memory/blocks/{type}`，成功后 version 自增
- “历史”按钮展开最近 20 条，可回滚
- 只做编辑，不提供“立即整理”按钮

示例结构：

```tsx
<div className="flex items-center justify-between text-xs">
  <span>{title}</span>
  <span className={overLimit ? 'text-red-500' : ratio > 0.9 ? 'text-terracotta-500' : 'text-charcoal-400'}>
    {value.length}/{maxChars}
  </span>
</div>
<textarea
  value={value}
  onChange={(e) => setValue(e.target.value)}
  rows={title === '用户信息' ? 8 : 12}
  className="w-full resize-y rounded-lg border border-warm-white-200 bg-white px-3 py-2 text-sm"
/>
```

```tsx
<p className="text-[11px] text-charcoal-400">
  自动块可编辑，但不承诺永久保留；重要规则请放入用户固定提示词。
</p>
```

### Step 4: MemoryPanel 结构

```tsx
<div className="space-y-4">
  <MemoryPromptEditor prompt={userPrompt} onChange={reload} />
  <MemoryBlockEditor block={profile} onChange={reload} />
  <MemoryBlockEditor block={events} onChange={reload} />
</div>
```

移除旧的条目列表、veto 输入框、单条纠正按钮。veto 不在 v1 范围内。

### Step 5: 构建校验

Run:

```powershell
cd frontend
npx tsc --noEmit
npm run build
```

Expected: `built in ...` 且无 TS 错误。

### Step 6: 提交

```bash
git add frontend/src/hooks/useMemoryBlocks.ts \
        frontend/src/components/MemoryBlockEditor.tsx \
        frontend/src/components/MemoryPromptEditor.tsx \
        frontend/src/components/MemoryPanel.tsx \
        frontend/src/types/index.ts
git commit -m "feat(frontend): replace memory list with two block editors"
```

---

## Task 8: 前端反馈只区分本轮变化

**Files:**

- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/hooks/useMemoryBlocks.ts`

### Step 1: 记录本轮开始时的 version

```ts
const knownVersionsRef = useRef<Record<'profile' | 'event', number>>({
  profile: 0,
  event: 0,
})
```

每轮发送前先拉一次 blocks，把当前 version 记为基线。

### Step 2: 只对 KEYWORD / AUTO_CONSOLIDATION 弹 toast

```ts
const changed = blocks.filter((block) => {
  if (block.version === knownVersionsRef.current[block.type]) {
    return false
  }
  return block.changeSource === 'KEYWORD' || block.changeSource === 'AUTO_CONSOLIDATION'
})

if (changed.length > 0) {
  const preview = changed
    .map((block) => block.content.split('\n').at(-1))
    .filter(Boolean)
    .join('；')
  setMemoryToast(preview ? `长期记忆已更新：${preview}` : '长期记忆已更新')
}
```

### Step 3: 每日变更不打扰

`DAILY` 变更不弹 toast。用户打开记忆页时显示：

```tsx
{lastDailyRunAt && (
  <p>最近自动整理：{formatTime(lastDailyRunAt)}</p>
)}
```

### Step 4: 构建校验

Run:

```powershell
cd frontend
npm run build
```

Expected: `built in ...`。

### Step 5: 提交

```bash
git add frontend/src/App.tsx frontend/src/hooks/useMemoryBlocks.ts
git commit -m "feat(frontend): distinguish explicit and daily memory updates"
```

---

## Task 9: 旧链路退出主流程

**Files:**

- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/agent/ReactAgent.java`
- Modify: `agent-core/src/main/resources/application.yml`

### Step 1: 配置

```yaml
app:
  memory:
    ingest-trigger:
      enabled: false
    hybrid:
      enabled: false
```

### Step 2: 停止调用旧链路

不再调用：

- `MemoryTriggerService.onConversationFinished()`
- `UserMemoryService.extractAndStore()`

### Step 3: 回归测试

Run:

```powershell
cd agent-core
mvn -o -DexcludedGroups=integration test
```

Expected: `BUILD SUCCESS`，摘要行 `Failures: 0, Errors: 0`。

### Step 4: 提交

```bash
git add agent-core/src/main/resources/application.yml \
        agent-core/src/main/java/uno/zhuchen/agent/core/agent/ReactAgent.java
git commit -m "refactor(memory): retire legacy extraction from chat flow"
```

---

## Task 10: 端到端验收

### Step 1: 启动依赖

```powershell
docker start milvus-milvus-1 milvus-etcd-1 milvus-minio-1
```

### Step 2: 启动服务

```powershell
Start-Process -FilePath "D:\JAVA\JAVA-21\bin\java.exe" `
  -ArgumentList '-Xmx768m','-jar','D:\Project\Agent\TripAgent\agent-core\target\agent-core-0.0.1-SNAPSHOT.jar' `
  -Environment @{ SERVER_PORT='18080' }
cd frontend
$env:VITE_PROXY_TARGET='http://localhost:18080'
npm run dev -- --host 127.0.0.1
```

### Step 3: 显式触发验收

1. 新会话发送：“记住：我叫张三，性别男，喜欢吃辣”
2. 等待 ≤20 秒
3. 打开偏好记忆页
4. 期望：
   - profile 块出现“张三 / 男 / 喜欢吃辣”
   - 字数 ≤600
   - toast 只出现一次

### Step 4: 非显式不写入验收

1. 新会话发送：“这次带老人出行，酒店最好一楼”
2. 等待 30 秒
3. 期望：
   - 两块文本无变化
   - 该信息只存在于会话历史

### Step 5: 分层编辑验收

1. 在“用户固定提示词”中保存：“称呼我为老板，回复保持简洁”
2. 再发送多条显式记忆和普通消息，等待每日任务执行
3. 期望：
   - 固定提示词原样保留
   - `memory_user_prompt.version` 只有用户保存时才增加
   - worker 日志没有任何对 `memory_user_prompt` 的更新
   - 注入的 system prompt 包含 `[用户固定提示词]`
4. 把自动 profile 块改成“用户编辑：不吃辣”
5. 再发送：“记住：不吃香菜”，等待 worker 完成
6. 期望：
   - 在字数允许时，“用户编辑：不吃辣”和“不吃香菜”同时存在
   - `generated_edited_at` 非空
   - revision 可回滚
   - 页面明确提示自动块不承诺永久保留

### Step 6: 幂等验收

1. 同一会话连续两次发送：“记住：常住杭州”
2. 等待 worker 完成
3. 查询 `memory_raw_event`
4. 期望：
   - 同一 `event_key` 只有一行
   - profile 不重复出现“常住杭州”

### Step 7: 每日任务验收

1. 手动把 watermark 置空
2. 往某用户塞 5 条新消息
3. 测试环境把 cron 临时改为每分钟
4. 等待执行
5. 期望：
   - 生成 5 条 `DAILY` raw event
   - worker 合并后两块文本更新
   - 前端不弹每日 toast
   - 记忆页显示最近自动整理时间

### Step 8: 超限验收

1. 往事件块手动保存超过 1000 字
2. 期望接口返回 400
3. 让模型输出超过 1000 字
4. 期望自动截断到 1000 字以内
5. 若自动块已被用户编辑且能同时放下旧编辑和新内容，期望 best-effort 保留
6. 若整体超限或内容冲突，允许重建，但必须写 revision 并可回滚

---

## 配置汇总

```yaml
app:
  memory:
    blocks:
      enabled: true
      consolidate-interval-ms: 10000
      daily-cron: "0 0 2 * * *"
      daily-timezone: "Asia/Shanghai"
      daily-min-new-messages: 5
      user-prompt-max-chars: 600
      profile-max-chars: 600
      event-max-chars: 1000
      max-attempts: 3
    ingest-trigger:
      enabled: false
    hybrid:
      enabled: false
```

## 成本模型

| 场景 | 模型调用 | 说明 |
|---|---|---|
| 普通对话 | 0 | 不入长期记忆 |
| 显式“记住” | 每用户每 10 秒最多 2 次 | 第一阶段抽取 + 第二阶段加入 |
| 每日任务 | 每活跃用户每天 ≤2 次 | 只在新增消息 ≥5 条时触发 |

## 风险与回滚

| 风险 | 处理 |
|---|---|
| 第一阶段误抽临时信息 | 提示词强制输出 `discarded`，端到端验收覆盖“这次”场景 |
| 用户固定规则被自动整理覆盖 | 固定提示词独立建表，worker 不写该表 |
| 自动块用户编辑丢失 | best-effort 保留 + revision 回滚；重要内容由固定提示词承接 |
| 重复入队 | `(user_id, event_key)` 唯一键 + `INSERT IGNORE` |
| worker 崩溃后事件卡死 | `PENDING / PROCESSING / DONE / FAILED` 状态机 + attempt_count |
| 每日任务重复累计 | watermark 按 `message_id` 推进 |
| 每日变更打扰用户 | `changeSource` 过滤，只在记忆页展示 |
| 新链路不可用 | 关闭 `app.memory.blocks.enabled`，注入降级为无长期记忆 |

## v2 演进方向

事件块接近 1000 字时，把被挤出的条目写入向量库作为“冷记忆”。召回只在用户出现“记得 / 上次 / 以前 / 我去过 / 之前那个”这类意图时触发，取 top 3、每条 ≤200 字，作为事件块的临时补充注入，不写回事件块。

## 验收标准

- [ ] 普通对话不写长期记忆
- [ ] 显式“记住”20 秒内进入对应块
- [ ] 用户固定提示词 ≤600 字且 worker 永不修改
- [ ] 自动两块严格不超过 600 / 1000 字
- [ ] 同一显式消息重复入队只产生一条 raw event
- [ ] 固定提示词不被自动整理覆盖
- [ ] 自动块用户编辑在字数允许时 best-effort 保留，冲突时可回滚
- [ ] 前端可编辑、可看历史、可回滚
- [ ] toast 只在显式或本轮自动整理时出现
- [ ] 每日任务只处理增量，且 <5 条新消息的用户跳过
- [ ] `mvn -o -DexcludedGroups=integration test` 与 `npm run build` 全绿
