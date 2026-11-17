# Long-Term Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add authenticated, per-user long-term memory with conservative preference extraction and bounded prompt injection across ReAct, Graph, and Multi-Agent paths.

**Architecture:** A `UserMemoryService` owns persistence, deterministic extraction, prompt rendering, and user ownership checks. Controllers and agent entry points call this service with the authenticated `userId`; memory failures are logged and ignored by callers.

**Tech Stack:** Spring Boot 3.5, WebFlux, MyBatis-Plus, Flyway, H2 dev schema, JUnit 5, Mockito, React 19.

---

### Task 1: Add memory schema and persistence

**Files:**
- Create: `agent-core/src/main/resources/db/migration/V10__create_user_memory.sql`
- Modify: `agent-core/src/main/resources/db/dev/schema.sql`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/UserMemoryEntity.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/UserMemoryMapper.java`

- [ ] Define `user_memory` with id, user_id, memory_type, memory_key, memory_value, source_conversation_id, confidence, status, created_at, updated_at.
- [ ] Add a unique index on (`user_id`, `memory_key`, `status`) and an index on (`user_id`, `status`).
- [ ] Add the equivalent H2 dev table.
- [ ] Run `mvn -pl agent-core -DskipTests test-compile` and confirm BUILD SUCCESS.

### Task 2: Implement deterministic extraction and prompt rendering

**Files:**
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/MemoryExtractor.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/MemoryPromptRenderer.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/core/memory/MemoryExtractorTest.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/core/memory/MemoryPromptRendererTest.java`

- [ ] Extract preferences from explicit phrases only: 美食, 自然, 文化, 历史, 购物, 户外, 亲子.
- [ ] Extract defaults for home city, usual budget, usual companions, and no-spicy preference.
- [ ] Normalize keys to `travel.preference.<slug>`, `travel.default.<slug>`, and `travel.profile.<slug>`.
- [ ] Match Chinese and Arabic numerals for companion count.
- [ ] Ignore assistant text, tool output, and vague phrases such as “随便”.
- [ ] Render no section for an empty list; render a `用户长期记忆` section with labels and a max item count.
- [ ] Run the two focused tests and confirm they pass.

### Task 3: Implement the memory service

**Files:**
- Create: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/UserMemoryService.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/core/memory/UserMemoryServiceTest.java`

- [ ] `extractAndStore(userId, conversationId, userMessage)` writes only extracted values and catches all persistence errors.
- [ ] `loadSummary(userId)` returns active memories ordered by updated_at desc and applies item/character caps.
- [ ] `list(userId)`, `delete(userId, memoryId)`, and `deleteAll(userId)` enforce ownership; foreign IDs throw `NotFoundException`.
- [ ] Upsert uses the unique key and reactivates/updates a previously deleted row when the same key is explicitly stated again.
- [ ] All methods return safe empty/default values on mapper failure.
- [ ] Run the focused test and confirm it passes.

### Task 4: Add user-facing memory APIs

**Files:**
- Create: `agent-core/src/main/java/uno/zhuchen/agent/controller/MemoryController.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/common/GlobalExceptionHandler.java`

- [ ] Add `GET /api/memory`, `DELETE /api/memory/{id}`, and `DELETE /api/memory`.
- [ ] Derive the user from `@AuthenticationPrincipal UserPrincipal`; reject any request-supplied userId.
- [ ] Return `Result.success` for reads and deletes, and JSON `404` for cross-user access.
- [ ] Verify application context compiles with `mvn -pl agent-core -DskipTests test-compile`.

### Task 5: Wire memory into ReAct and Multi-Agent

**Files:**
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/config/LLMConfig.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/agent/ReactAgent.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/multi/MultiAgentManager.java`

- [ ] Inject `UserMemoryService` into both agents.
- [ ] Before each run, load the summary and append it to the base system prompt via `MemoryPromptRenderer`.
- [ ] After successful completion, call `extractAndStore` with the current `userId`, `conversationId`, and raw user message.
- [ ] Keep memory loading and storing inside try/catch so failures never affect the response.
- [ ] Run `mvn -pl agent-core -Dtest=MemoryExtractorTest,MemoryPromptRendererTest,UserMemoryServiceTest test`.

### Task 6: Wire memory into Graph

**Files:**
- Modify: `agent-core/src/main/java/uno/zhuchen/workflow/state/TripPlanningStateKeys.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/workflow/state/KeyStrategyFactoryProvider.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/workflow/builder/GraphStreamRunner.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/workflow/agent/ManagerAgent.java`

- [ ] Add `INPUT_USER_MEMORY` state key with Replace strategy.
- [ ] Load the memory summary before building Graph input and put it into the state.
- [ ] In `ManagerAgent.handleFirstTime`, append the memory summary to the extraction user prompt.
- [ ] After Graph completion, call `extractAndStore` with the raw request and conversation ID.
- [ ] Catch all memory failures and preserve the existing Graph behavior.
- [ ] Run focused memory tests and backend compilation.

### Task 7: Add a minimal memory management UI

**Files:**
- Create: `frontend/src/hooks/useMemory.ts`
- Create: `frontend/src/components/MemoryPanel.tsx`
- Modify: `frontend/src/components/Sidebar.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/types/index.ts`

- [ ] Add `UserMemory` and `UserMemoryPage` types.
- [ ] Implement hook methods list/delete/deleteAll through `apiFetch`.
- [ ] Add a sidebar “偏好记忆” view that lists label/value, timestamps, and delete controls.
- [ ] Confirm before deleting one item or all items.
- [ ] Do not expose memory management when unauthenticated; the existing AuthProvider gate remains authoritative.
- [ ] Run `npm run build` and confirm TypeScript passes.

### Task 8: Verify the full feature

- [ ] Run `mvn test -DexcludedGroups=integration`.
- [ ] Run `npm run build`.
- [ ] Start backend + frontend and verify one user's stated preference appears in the memory panel and another user's panel stays empty.
- [ ] Verify deleting the preference removes it from future prompt context.
- [ ] Commit the feature on `codex/feature/session-auth-isolation`.
