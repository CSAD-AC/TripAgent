# 长期记忆 v1：两块文本 + 两种触发 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把现有“五类白名单 + 每轮异步抽取 + pinned/episodic 双路召回”的长期记忆，收敛为“用户信息块 ≤600 字 + 事件记忆块 ≤1000 字”两块可编辑文本，只由显式关键词和每日增量任务写入。

**Architecture:** 会话记忆完全沿用现有 `conversation` / `message` / `ChatMemory`，不参与长期记忆写入；长期记忆新增 `memory_block`（两块派生文本）、`memory_raw_event`（append-only 原料）、`memory_block_revision`（审计）与 `memory_consolidation_watermark`（每日增量水位）。关键词触发即时入队，每日 02:00 扫描有 >5 条新消息的用户，两者最终都由同一个 `MemoryBlockConsolidator` 调模型合并进两块文本。

**Tech Stack:** Spring Boot 3 / WebFlux / MyBatis-Plus / MySQL / Flyway / Spring AI / React + TypeScript + Vite / JUnit 5 + Mockito

---

## 背景与边界

### 要解决的问题

当前实现把会话上下文和跨会话记忆混在一起：

- `ReactAgent.remember()` 每轮都触发抽取，命中文旅词就写库；
- `PROFILE` / `CONSTRAINT` 同时承载长期属性和“本次出行”状态；
- 旧链路 `UserMemoryService.extractAndStore()` 与新链路双写 `memory_item`；
- 注入时 pinned/episodic 两条路把行程级信息当永久偏好。

### 概念边界（硬约束）

| 概念 | 存储 | 作用域 | 注入方式 | 是否写入长期记忆 |
|---|---|---|---|---|
| 会话记忆 | `conversation` + `message` | 单个 conversation | `ChatMemory.load()` 作为历史消息 | 否 |
| 长期记忆 | `memory_block` 两块文本 | 跨 conversation | user 角色背景块 | 是 |

任何“本次/这次/这趟”表述默认留在会话记忆里，只有用户显式说“记住”或每日增量任务判断为稳定事实时才进入长期记忆。

### 非目标（v1 不做）

- 不做五类白名单（`PROFILE/PREFERENCE/CONSTRAINT/VETO/EPISODIC`）；
- 不做每轮自动抽取；
- 不做 pinned/episodic 双路召回；
- 不做 Milvus 向量召回（保留代码，但退出主链路）；
- 不提供“立即整理”手动触发按钮；
- 不做记忆条目级 UI，只做两个文本块。

---

## 文件结构

### 新增

| 文件 | 职责 |
|---|---|
| `agent-core/src/main/resources/db/migration/V14__create_long_term_memory_blocks.sql` | 四张新表 |
| `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryBlockEntity.java` | 两块文本实体 |
| `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryRawEventEntity.java` | 原始事件实体 |
| `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryBlockRevisionEntity.java` | 块修订实体 |
| `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryWatermarkEntity.java` | 每日水位实体 |
| `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryBlockMapper.java` | 块 Mapper |
| `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryRawEventMapper.java` | 原始事件 Mapper |
| `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryBlockRevisionMapper.java` | 修订 Mapper |
| `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryWatermarkMapper.java` | 水位 Mapper |
| `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockType.java` | 枚举 `PROFILE` / `EVENT` |
| `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockService.java` | 读写两块文本、修订、回滚 |
| `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryKeywordTrigger.java` | 显式关键词正则 |
| `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryRawEventService.java` | 原始事件落库（幂等） |
| `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockConsolidator.java` | LLM 合并 + 字数预算 + 回退 |
| `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockWorker.java` | 10 秒轮询未处理事件 |
| `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryDailyConsolidator.java` | 每日 02:00 增量扫描 |
| `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/LongTermMemoryContextBuilder.java` | 两块文本 → 注入块 |
| `agent-core/src/main/java/uno/zhuchen/agent/controller/MemoryBlockController.java` | 块读取/编辑/历史/回滚 |
| `agent-core/src/main/java/uno/zhuchen/agent/domain/vo/MemoryBlockVO.java` | 前端块视图 |
| `frontend/src/hooks/useMemoryBlocks.ts` | 前端块 API 封装 |
| `frontend/src/components/MemoryBlockEditor.tsx` | 单块编辑器 |

### 修改

| 文件 | 改动 |
|---|---|
| `agent-core/src/main/java/uno/zhuchen/agent/core/agent/ReactAgent.java` | `remember()` 只调关键词触发；注入改用两块文本 |
| `agent-core/src/main/java/uno/zhuchen/agent/core/multi/MultiAgentManager.java` | 同上 |
| `agent-core/src/main/java/uno/zhuchen/workflow/builder/GraphStreamRunner.java` | 同上 |
| `agent-core/src/main/resources/application.yml` | 新增 `app.memory.blocks.*` |
| `frontend/src/components/MemoryPanel.tsx` | 改为两块编辑器 |
| `frontend/src/App.tsx` | 记忆反馈改为比较块 version |
| `frontend/src/types/index.ts` | 新增 `MemoryBlock` 类型 |

### 保留但退出主链路

`MemoryTriggerService`、`MemoryIngestWorker`、`MemoryExtractionWorker`、`MemoryRetrievalService`、`HybridMemoryPromptRenderer` 不删除，v1 稳定后单独清理。

---

## Task 1: 建表与实体

**Files:**
- Create: `agent-core/src/main/resources/db/migration/V14__create_long_term_memory_blocks.sql`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryBlockEntity.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryRawEventEntity.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryBlockRevisionEntity.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/MemoryWatermarkEntity.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryBlockMapper.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryRawEventMapper.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryBlockRevisionMapper.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/MemoryWatermarkMapper.java`

- [ ] **Step 1: 写迁移 SQL**

```sql
CREATE TABLE memory_block (
    id               VARCHAR(64)  NOT NULL PRIMARY KEY,
    user_id          VARCHAR(64)  NOT NULL,
    block_type       VARCHAR(16)  NOT NULL,
    content          TEXT         NOT NULL,
    char_count       INT          NOT NULL DEFAULT 0,
    version          INT          NOT NULL DEFAULT 0,
    manual_edited_at DATETIME     NULL,
    created_at       DATETIME     NOT NULL,
    updated_at       DATETIME     NOT NULL,
    UNIQUE KEY uk_memory_block (user_id, block_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE memory_raw_event (
    id              VARCHAR(64)   NOT NULL PRIMARY KEY,
    user_id         VARCHAR(64)   NOT NULL,
    conversation_id VARCHAR(64)   NULL,
    message_id      BIGINT        NULL,
    content         VARCHAR(2000) NOT NULL,
    source          VARCHAR(16)   NOT NULL,
    processed_at    DATETIME      NULL,
    created_at      DATETIME      NOT NULL,
    UNIQUE KEY uk_raw_event_message (user_id, message_id),
    KEY idx_raw_event_pending (processed_at, created_at)
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

- [ ] **Step 2: 写四个实体**

按现有 `MemoryItemEntity` 的 Lombok + MyBatis-Plus 风格：

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
      private LocalDateTime manualEditedAt;
      private LocalDateTime createdAt;
      private LocalDateTime updatedAt;
  }
```

其余三个实体字段与 SQL 一一对应，`MemoryRawEventEntity.messageId` 用 `Long`。

- [ ] **Step 3: 写四个 Mapper**

```java
  @Mapper
  public interface MemoryBlockMapper extends BaseMapper<MemoryBlockEntity> {
  }
```

其余三个同构。

- [ ] **Step 4: 跑迁移测试**

Run: `cd agent-core && mvn -o -Dtest='FlywayMigrationTest' -DfailIfNoSpecifiedTests=false test`

Expected: `BUILD SUCCESS`，日志出现 `Successfully applied 1 migration ... version "14"`。

- [ ] **Step 5: 提交**

```bash
git add agent-core/src/main/resources/db/migration/V14__create_long_term_memory_blocks.sql \
        agent-core/src/main/java/uno/zhuchen/agent/domain/entity/ \
        agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/
git commit -m "feat(memory): add long-term memory block schema"
```

---

## Task 2: 显式关键词触发

**Files:**
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryKeywordTrigger.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryRawEventService.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/agent/ReactAgent.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/core/memory/block/MemoryKeywordTriggerTest.java`

- [ ] **Step 1: 写失败用例**

```java
  class MemoryKeywordTriggerTest {
      @Test
      void matchesExplicitRememberKeywords() {
          assertTrue(MemoryKeywordTrigger.matches("记住：我不吃辣"));
          assertTrue(MemoryKeywordTrigger.matches("帮我记住，我常住杭州"));
          assertFalse(MemoryKeywordTrigger.matches("明天北京天气怎么样"));
          assertFalse(MemoryKeywordTrigger.matches("这次带老人出行"));
      }
  }
```

Run: `mvn -o -Dtest=MemoryKeywordTriggerTest test`

Expected: 编译失败，`MemoryKeywordTrigger` 不存在。

- [ ] **Step 2: 实现正则触发器**

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

- [ ] **Step 3: 写原始事件服务（按 messageId 幂等）**

```java
  @Service
  public class MemoryRawEventService {
      private final MemoryRawEventMapper mapper;

      public String append(String userId, String conversationId, Long messageId,
                           String content, String source) {
          if (userId == null || content == null || content.isBlank()) {
              return null;
          }
          if (messageId != null && exists(userId, messageId)) {
              return null;
          }
          MemoryRawEventEntity entity = new MemoryRawEventEntity();
          entity.setId(UUID.randomUUID().toString());
          entity.setUserId(userId);
          entity.setConversationId(conversationId);
          entity.setMessageId(messageId);
          entity.setContent(content.length() > 2000 ? content.substring(0, 2000) : content);
          entity.setSource(source);
          entity.setCreatedAt(LocalDateTime.now());
          mapper.insert(entity);
          return entity.getId();
      }
  }
```

- [ ] **Step 4: 改 `ReactAgent.remember()`**

```java
  private void remember(String userId, String conversationId, String userInput) {
      try {
          if (!MemoryKeywordTrigger.matches(userInput)) {
              return; // 非显式记忆不写长期记忆，留在会话历史里
          }
          memoryRawEventService.append(userId, conversationId, null, userInput, "KEYWORD");
      } catch (Exception e) {
          log.warn("长期记忆入队失败: userId={}, error={}", userId, e.getMessage());
      }
  }
```

`MultiAgentManager.remember()`、`GraphStreamRunner.remember()` 做同样替换。

- [ ] **Step 5: 跑测试**

Run: `mvn -o -Dtest=MemoryKeywordTriggerTest test`

Expected: `Tests run: 1, Failures: 0`。

- [ ] **Step 6: 提交**

```bash
git add agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/ \
        agent-core/src/main/java/uno/zhuchen/agent/core/agent/ReactAgent.java \
        agent-core/src/main/java/uno/zhuchen/agent/core/multi/MultiAgentManager.java \
        agent-core/src/main/java/uno/zhuchen/workflow/builder/GraphStreamRunner.java
git commit -m "feat(memory): trigger long-term memory only on explicit keywords"
```

---

## Task 3: 两块文本合并与字数预算

**Files:**
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockType.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockService.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockConsolidator.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/core/memory/block/MemoryBlockConsolidatorTest.java`

- [ ] **Step 1: 定义类型与限额**

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

- [ ] **Step 2: 写失败用例**

```java
  @Test
  void fallsBackToDeterministicTruncationWhenModelOverLimit() {
      ModelRouter router = mock(ModelRouter.class);
      when(router.route(any())).thenReturn(fixedModel(
              "{\"profile\":\"" + "长".repeat(700) + "\",\"events\":\"事件\"}"));
      when(router.resolveModelName(any())).thenReturn("m");

      MemoryBlockConsolidator consolidator =
              new MemoryBlockConsolidator(router, new ObjectMapper(), true, "");
      ConsolidateResult result = consolidator.consolidate(
              "", "", List.of("记住：我不吃辣"));

      assertTrue(result.profile().length() <= 600);
  }
```

- [ ] **Step 3: 实现合并器**

返回值统一用同一个 record，避免后续任务对不上：

```java
  public record ConsolidateResult(String profile, String events, boolean modelMerged) {
  }
```

固定提示词要点：

```text
你是长期记忆整理器。输入是当前两块记忆和新增事件，输出严格 JSON：
{"profile":"...","events":"..."}

规则：
1. 只压缩与合并，禁止新增原文没有的事实
2. 金额、日期、地名、人数必须原样保留
3. profile 只放长期稳定信息（常住城市、长期偏好、长期同行人）
4. events 放具体经历，每条一行，行尾保留日期
5. profile 不超过 600 字，events 不超过 1000 字
6. events 超限时优先删除最久远的条目
7. 不要输出解释，不要用代码块包裹
```

```java
  public ConsolidateResult consolidate(String profile, String events, List<String> newEvents) {
      if (newEvents.isEmpty()) {
          return new ConsolidateResult(profile, events, false);
      }
      try {
          String raw = callModel(profile, events, newEvents);
          JsonNode node = new JsonExtractor(objectMapper).extract(raw);
          String nextProfile = node.path("profile").asText(profile);
          String nextEvents = node.path("events").asText(events);
          return new ConsolidateResult(
                  fit(nextProfile, MemoryBlockType.PROFILE),
                  fit(nextEvents, MemoryBlockType.EVENT),
                  true);
      } catch (Exception e) {
          log.warn("[memory-block] 合并失败，改为追加+确定性截断: {}", e.getMessage());
          return new ConsolidateResult(
                  fit(profile, MemoryBlockType.PROFILE),
                  fit(appendEvents(events, newEvents), MemoryBlockType.EVENT),
                  false);
      }
  }
```

- [ ] **Step 4: 确定性兜底**

```java
  static String fit(String content, MemoryBlockType type) {
      String text = content == null ? "" : content.trim();
      if (text.length() <= type.maxChars()) {
          return text;
      }
      if (type == MemoryBlockType.EVENT) {
          List<String> lines = new ArrayList<>(List.of(text.split("\n")));
          while (!lines.isEmpty() && String.join("\n", lines).length() > type.maxChars()) {
              lines.remove(0); // 最久远在前
          }
          return String.join("\n", lines);
      }
      return text.substring(0, type.maxChars());
  }
```

- [ ] **Step 5: 跑测试**

Run: `mvn -o -Dtest=MemoryBlockConsolidatorTest test`

Expected: `Tests run: 2, Failures: 0`。

- [ ] **Step 6: 提交**

```bash
git add agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/ \
        agent-core/src/test/java/uno/zhuchen/agent/core/memory/block/
git commit -m "feat(memory): merge long-term memory into two bounded blocks"
```

---

## Task 4: 后台消费与每日增量任务

**Files:**
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockWorker.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryDailyConsolidator.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryWatermarkService.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/persistence/query/MessageQuery.java`
- Modify: `agent-core/src/main/resources/application.yml`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/core/memory/block/MemoryDailyConsolidatorTest.java`

- [ ] **Step 1: 配置项**

```yaml
app:
  memory:
    blocks:
      enabled: ${MEMORY_BLOCKS_ENABLED:true}
      consolidate-interval-ms: 10000
      daily-cron: "0 0 2 * * *"
      daily-min-new-messages: 5
      profile-max-chars: 600
      event-max-chars: 1000
```

- [ ] **Step 2: 写 10 秒消费 worker**

```java
  @Component
  @ConditionalOnProperty(name = "app.memory.blocks.enabled",
          havingValue = "true", matchIfMissing = true)
  public class MemoryBlockWorker {

      @Scheduled(fixedDelayString = "${app.memory.blocks.consolidate-interval-ms:10000}")
      public void run() {
          try {
              consolidator.processPending(50);
          } catch (Exception e) {
              log.warn("[memory-block] 消费失败: {}", e.getMessage());
          }
      }
  }
```

`processPending` 按 user 分组，一次 LLM 调用合并该用户所有未处理事件，成功后写 `processed_at`。

- [ ] **Step 3: 写每日增量任务（带水位线）**

`MessageQuery.newUserMessagesAfter` 负责按用户取增量：先查该用户的 conversationId 列表，再按 `id > lastMessageId` 且 `role='user'` 升序取消息，最多 200 条。

```java
  @Scheduled(cron = "${app.memory.blocks.daily-cron:0 0 2 * * *}")
  public void dailyRun() {
      for (String userId : watermarkService.activeUserIds()) {
          Long lastId = watermarkService.lastMessageId(userId);
          List<MessageEntity> fresh = messageQuery.newUserMessagesAfter(userId, lastId, 200);
          if (fresh.size() < minNewMessages) {
              watermarkService.touch(userId, lastId, LocalDateTime.now());
              continue;
          }
          for (MessageEntity m : fresh) {
              rawEventService.append(userId, m.getConversationId(), m.getId(), m.getContent(), "DAILY");
          }
          watermarkService.touch(userId, fresh.get(fresh.size() - 1).getId(), LocalDateTime.now());
      }
  }
```

这里只把消息转成 raw event，真正合并由 `MemoryBlockWorker` 完成，两个任务解耦。

- [ ] **Step 4: 写水位线测试**

```java
  @Test
  void skipsUsersWithLessThanFiveNewMessages() {
      when(watermarkService.activeUserIds()).thenReturn(List.of("u1"));
      when(messageQuery.newUserMessagesAfter("u1", 10L, 200))
              .thenReturn(List.of(msg(11L), msg(12L), msg(13L), msg(14L)));
      daily.dailyRun();
      verify(rawEventService, never()).append(any(), any(), any(), any(), any());
      verify(watermarkService).touch(eq("u1"), eq(10L), any());
  }
```

- [ ] **Step 5: 跑测试**

Run: `mvn -o -Dtest=MemoryDailyConsolidatorTest test`

Expected: `Tests run: 2, Failures: 0`。

- [ ] **Step 6: 提交**

```bash
git add agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/ \
        agent-core/src/main/resources/application.yml \
        agent-core/src/test/java/uno/zhuchen/agent/core/memory/block/
git commit -m "feat(memory): add block worker and daily incremental consolidation"
```

---

## Task 5: 注入切换到两块文本

**Files:**
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/LongTermMemoryContextBuilder.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/agent/ReactAgent.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/multi/MultiAgentManager.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/workflow/builder/GraphStreamRunner.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/core/memory/block/LongTermMemoryContextBuilderTest.java`

- [ ] **Step 1: 写渲染器**

```java
  @Component
  public class LongTermMemoryContextBuilder {
      private final MemoryBlockService blockService;

      public String render(String userId) {
          String profile = blockService.content(userId, MemoryBlockType.PROFILE);
          String events = blockService.content(userId, MemoryBlockType.EVENT);
          if ((profile == null || profile.isBlank()) && (events == null || events.isBlank())) {
              return null;
          }
          StringBuilder b = new StringBuilder("[长期记忆]\n");
          if (profile != null && !profile.isBlank()) {
              b.append("用户信息：\n").append(profile.trim()).append("\n\n");
          }
          if (events != null && !events.isBlank()) {
              b.append("事件记忆：\n").append(events.trim()).append("\n");
          }
          return b.toString().trim();
      }
  }
```

- [ ] **Step 2: 替换 `ReactAgent.memoryContextBlock`**

```java
  private String memoryContextBlock(String userId, String userInput) {
      String block = longTermMemoryContextBuilder.render(userId);
      log.debug("[memory-inject] userId={}, blockChars={}",
              userId, block == null ? 0 : block.length());
      return block;
  }
```

`MultiAgentManager` 与 `GraphStreamRunner` 同样改为设置 `state.setContextBlock(...)`。

- [ ] **Step 3: 写渲染测试**

```java
  @Test
  void rendersTwoSectionsAndSkipsEmptyBlocks() {
      when(blockService.content("u1", MemoryBlockType.PROFILE))
              .thenReturn("用户叫张三，性别男");
      when(blockService.content("u1", MemoryBlockType.EVENT))
              .thenReturn("2025-11-07 去北京玩了三天");
      String text = builder.render("u1");
      assertTrue(text.contains("[长期记忆]"));
      assertTrue(text.contains("用户信息"));
      assertTrue(text.contains("事件记忆"));
  }
```

- [ ] **Step 4: 跑测试**

Run: `mvn -o -Dtest=LongTermMemoryContextBuilderTest test`

Expected: `Tests run: 2, Failures: 0`。

- [ ] **Step 5: 提交**

```bash
git add agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/LongTermMemoryContextBuilder.java \
        agent-core/src/main/java/uno/zhuchen/agent/core/agent/ReactAgent.java \
        agent-core/src/main/java/uno/zhuchen/agent/core/multi/MultiAgentManager.java \
        agent-core/src/main/java/uno/zhuchen/workflow/builder/GraphStreamRunner.java
git commit -m "feat(memory): inject two long-term memory blocks instead of retrieval pipeline"
```

---

## Task 6: 块读写 API

**Files:**
- Create: `agent-core/src/main/java/uno/zhuchen/agent/controller/MemoryBlockController.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/vo/MemoryBlockVO.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockService.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/controller/MemoryBlockControllerTest.java`

- [ ] **Step 1: 定义接口**

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/memory/blocks` | 两块文本 + 字数 + 上限 + version + 最近变更摘要 |
| PUT | `/api/memory/blocks/{type}` | 手动保存，写 revision，置 manual_edited_at |
| GET | `/api/memory/blocks/{type}/revisions` | 最近 20 条历史 |
| POST | `/api/memory/blocks/{type}/rollback/{revisionId}` | 回滚到指定版本 |

`type` 只接受 `profile` / `event`。

- [ ] **Step 2: VO**

```java
  public record MemoryBlockVO(
          String type,
          String content,
          int charCount,
          int maxChars,
          int version,
          LocalDateTime updatedAt,
          boolean manuallyEdited,
          String lastChangePreview
  ) {
  }
```

- [ ] **Step 3: 手动保存校验**

```java
  if (content.length() > type.maxChars()) {
      throw new IllegalArgumentException(
              type.name().toLowerCase() + " 超过上限 " + type.maxChars() + " 字");
  }
```

- [ ] **Step 4: 写控制器测试**

```java
  @Test
  void rejectsOversizedProfileEdit() {
      mockMvc.perform(put("/api/memory/blocks/profile")
              .contentType(APPLICATION_JSON)
              .content("{\"content\":\"" + "长".repeat(601) + "\"}"))
              .andExpect(status().isBadRequest());
  }
```

- [ ] **Step 5: 跑测试**

Run: `mvn -o -Dtest=MemoryBlockControllerTest test`

Expected: `Tests run: 4, Failures: 0`。

- [ ] **Step 6: 提交**

```bash
git add agent-core/src/main/java/uno/zhuchen/agent/controller/MemoryBlockController.java \
        agent-core/src/main/java/uno/zhuchen/agent/domain/vo/MemoryBlockVO.java \
        agent-core/src/main/java/uno/zhuchen/agent/core/memory/block/MemoryBlockService.java
git commit -m "feat(memory): expose block read, edit and revision APIs"
```

---

## Task 7: 前端两块编辑器

**Files:**
- Create: `frontend/src/hooks/useMemoryBlocks.ts`
- Create: `frontend/src/components/MemoryBlockEditor.tsx`
- Modify: `frontend/src/components/MemoryPanel.tsx`
- Modify: `frontend/src/types/index.ts`

- [ ] **Step 1: 类型**

```ts
  export interface MemoryBlock {
    type: 'profile' | 'event'
    content: string
    charCount: number
    maxChars: number
    version: number
    updatedAt: string
    manuallyEdited: boolean
    lastChangePreview?: string
  }
```

- [ ] **Step 2: 编辑器组件**

要求：

- `<textarea>` 受控，实时字数 `charCount/maxChars`；
- 超过 90% 变琥珀色，超过上限禁用保存并标红；
- 保存按钮调用 PUT，成功后 version 自增；
- “历史”按钮展开最近 20 条，可回滚；
- 只做编辑，不提供“立即整理”按钮。

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

- [ ] **Step 3: MemoryPanel 结构**

```tsx
  <div className="space-y-4">
    <MemoryBlockEditor block={profile} onChange={reload} />
    <MemoryBlockEditor block={events} onChange={reload} />
  </div>
```

移除旧的条目列表、veto 输入框、单条纠正按钮。veto 能力不在 v1 范围内。

- [ ] **Step 4: 构建校验**

Run: `cd frontend && npx tsc --noEmit && npm run build`

Expected: `built in` 且无 TS 错误。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/hooks/useMemoryBlocks.ts \
        frontend/src/components/MemoryBlockEditor.tsx \
        frontend/src/components/MemoryPanel.tsx \
        frontend/src/types/index.ts
git commit -m "feat(frontend): replace memory item list with two block editors"
```

---

## Task 8: 保留前端“已记住”反馈

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/hooks/useMemoryBlocks.ts`

- [ ] **Step 1: 反馈语义改成块版本 diff**

每轮对话结束后仍按 6 / 12 / 20 秒轮询，但比较的是两块 `version`：

```ts
  const changed = blocks.filter(b => knownVersions.current[b.type] !== b.version)
  if (changed.length > 0) {
    const preview = changed.map(b => b.lastChangePreview).filter(Boolean).join('；')
    setMemoryToast(preview ? `长期记忆已更新：${preview}` : '长期记忆已更新')
  }
```

- [ ] **Step 2: 首次加载记录基线 version**

```ts
  useEffect(() => {
    void fetchBlocks().then(blocks => {
      knownVersions.current = Object.fromEntries(blocks.map(b => [b.type, b.version]))
    })
  }, [fetchBlocks])
```

- [ ] **Step 3: 每日批量不打扰**

每日任务产生的变更不弹窗；只在用户下次打开“偏好记忆”页时，页面顶部显示“最近自动整理：2026-11-19 02:00”。

- [ ] **Step 4: 构建校验**

Run: `cd frontend && npm run build`

Expected: `built in ...`。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/App.tsx frontend/src/hooks/useMemoryBlocks.ts
git commit -m "feat(frontend): keep memory updated toast using block versions"
```

---

## Task 9: 旧链路退出主流程

**Files:**
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/agent/ReactAgent.java`
- Modify: `agent-core/src/main/resources/application.yml`

- [ ] **Step 1: 关闭旧触发与旧注入**

```yaml
app:
  memory:
    ingest-trigger:
      enabled: false
    hybrid:
      enabled: false
```

- [ ] **Step 2: 保留数据但停止写入**

不再调用 `MemoryTriggerService.onConversationFinished()`、`UserMemoryService.extractAndStore()`。

- [ ] **Step 3: 回归测试**

Run: `cd agent-core && mvn -o -DexcludedGroups=integration test`

Expected: `BUILD SUCCESS`，摘要行 `Failures: 0, Errors: 0`。

- [ ] **Step 4: 提交**

```bash
git add agent-core/src/main/resources/application.yml \
        agent-core/src/main/java/uno/zhuchen/agent/core/agent/ReactAgent.java
git commit -m "refactor(memory): retire legacy extraction from main chat flow"
```

---

## Task 10: 端到端验收

**Files:** 无代码改动，仅验证。

- [ ] **Step 1: 启动依赖**

```powershell
docker start milvus-milvus-1 milvus-etcd-1 milvus-minio-1
```

- [ ] **Step 2: 启动服务**

```powershell
Start-Process -FilePath "D:\JAVA\JAVA-21\bin\java.exe" `
  -ArgumentList '-Xmx768m','-jar','D:\Project\Agent\TripAgent\agent-core\target\agent-core-0.0.1-SNAPSHOT.jar' `
  -Environment @{ SERVER_PORT='18080' }
cd frontend; $env:VITE_PROXY_TARGET='http://localhost:18080'; npm run dev -- --host 127.0.0.1
```

- [ ] **Step 3: 显式触发验收**

1. 新会话发送：“记住：我叫张三，性别男，喜欢吃辣”
2. 等待 ≤20 秒
3. 打开偏好记忆页
4. 期望：用户信息块出现上述内容，字数 ≤600，“已记住”toast 出现一次

- [ ] **Step 4: 非显式不写入验收**

1. 新会话发送：“这次带老人出行，酒店最好一楼”
2. 等待 30 秒
3. 期望：两块文本均无变化；该信息只存在于会话历史，同一会话下一轮仍可被引用

- [ ] **Step 5: 手动编辑验收**

1. 在用户信息块追加“预算一般 5000 元”
2. 保存
3. 期望：字数更新、version +1、历史里出现旧版本
4. 再发一条显式“记住”，期望模型保留手改内容

- [ ] **Step 6: 每日任务验收**

1. 手动把 watermark 置空，往某用户塞 6 条消息
2. 测试环境把 cron 临时改为每分钟，等待执行
3. 期望：生成 6 条 raw event，随后被 worker 合并进事件块

- [ ] **Step 7: 超限验收**

1. 往事件块塞超过 1000 字的内容
2. 保存应被拒绝
3. 让模型合并超限输入，期望自动裁剪到 1000 字以内且最久远条目被删除

---

## 配置汇总

```yaml
app:
  memory:
    blocks:
      enabled: true
      consolidate-interval-ms: 10000
      daily-cron: "0 0 2 * * *"
      daily-min-new-messages: 5
      profile-max-chars: 600
      event-max-chars: 1000
    ingest-trigger:
      enabled: false
    hybrid:
      enabled: false
```

## 成本模型

| 场景 | 调用次数 | 说明 |
|---|---|---|
| 普通对话 | 0 | 不触发长期记忆 |
| 显式“记住” | 每用户每 10 秒最多 1 次合并 | 多条 raw event 合批 |
| 每日任务 | 每活跃用户每天 ≤1 次 | 仅新增消息 >5 |

对比现状：每轮命中文旅词就抽一次，成本下降约一个数量级。

## 风险与回滚

| 风险 | 处理 |
|---|---|
| 模型压缩丢事实 | 保留 `memory_raw_event` 与 `memory_block_revision`，可回滚 |
| 用户手改被覆盖 | `manual_edited_at` 非空时提示词标记为不可删减 |
| 并发生成覆盖手改 | `version` 乐观锁，更新条件带旧 version |
| 每日任务重复累计 | watermark 按 `last_message_id` 推进，raw event 按 `(user_id, message_id)` 唯一 |
| 新链路不可用 | 配置 `app.memory.blocks.enabled=false` 即停写，注入降级为无长期记忆 |

## v2 演进方向

事件块作为“热记忆”常驻注入；当事件块接近 1000 字上限时，把被挤出的条目写入向量库作为“冷记忆”。

召回必须做意图门控，只在用户出现“记得 / 上次 / 以前 / 我去过 / 之前那个”这类表述时才查向量库，避免每轮都产生检索延迟。冷记忆命中后只取 top 3、每条 ≤200 字，作为事件块的临时补充注入，不写回事件块。

## 验收标准

- [ ] 普通对话不写长期记忆；
- [ ] 显式“记住”20 秒内出现在对应块；
- [ ] 两个块严格不超过 600 / 1000 字；
- [ ] 前端可编辑、可看历史、可回滚；
- [ ] “已记住”toast 保留且只在块实际变化时出现；
- [ ] 每日任务只处理增量且 <5 条新消息的用户跳过；
- [ ] `mvn -o -DexcludedGroups=integration test` 与 `npm run build` 全绿。
