# Long-Term Memory Design

## Goal

Add a basic cross-conversation user memory layer keyed by the authenticated `userId`. The first version stores only explicitly stated, low-risk travel preferences and usable defaults.

## Scope

In scope:

- Stable travel preferences such as `food`, `nature`, `culture`, `history`, `shopping`, `outdoor`, `family`.
- Usable defaults such as home city, typical budget, companion count, mobility or dietary preferences when the user states them.
- Per-user list, delete-one, and delete-all APIs.
- Injection of a short memory summary into ReAct, Graph, and Multi-Agent prompts.

Out of scope:

- Sensitive attributes, inferred identity, health, politics, religion, or demographic inference.
- Silent retention of tool results or assistant reasoning as memory.
- A second LLM call for memory extraction in the basic version.
- Cross-user sharing or global memory.

## Data Model

Table `user_memory`:

- `id`: UUID string
- `user_id`: authenticated user ID, indexed
- `memory_type`: `preference`, `default`, or `profile`
- `memory_key`: normalized key such as `travel.preference.food`
- `memory_value`: normalized short value
- `source_conversation_id`: conversation where the value was learned
- `confidence`: numeric score, default `0.8`
- `status`: `active` or `deleted`
- `created_at`, `updated_at`

Unique key: (`user_id`, `memory_key`, `status`). One active value per user and key. Updating the same key overwrites the old value and records its source.

## Extraction Rules

The extractor is deterministic and conservative:

- It sees only the current user message.
- It matches explicit phrases such as “我喜欢美食”, “我偏好自然风光”, “我来自上海”, “预算通常 5000 元”, “我不吃辣”.
- It never infers from names, assistant output, or tool results.
- It stores normalized keys and values, for example `travel.preference.food = true`.
- Empty results are normal and produce no writes.

## Runtime Flow

1. A user sends a message to ReAct, Graph, or Multi-Agent.
2. The authenticated `userId` is already resolved by Spring Security.
3. The entry point loads an active memory summary with a hard item limit and character cap.
4. The summary is inserted as an additional `SystemMessage` or Graph input field.
5. After a successful run, the raw user message is passed to the deterministic extractor.
6. Extracted items are upserted into `user_memory` with the current `conversationId`.
7. Memory failures log a warning and never fail the chat request.

## Prompt Format

The injected section is explicitly advisory:

```text
[用户长期记忆]
以下是用户过去明确表达的偏好，仅作为参考；若与本次明确要求冲突，以本次要求为准：
- 偏好：美食、自然
- 常用出发城市：上海
```

No memory section is injected when there are no active items.

## API

- `GET /api/memory`: list the current user's active memories.
- `DELETE /api/memory/{id}`: soft-delete one item owned by the current user.
- `DELETE /api/memory`: soft-delete all active memories of the current user.

Foreign or missing IDs are reported as `404` to avoid leaking existence.

## Failure Handling

- Database or mapper failures are caught at the memory service boundary; chat continues.
- Prompt injection uses bounded size to prevent memory from flooding context.
- Invalid extracted values are discarded.
- Soft-deleted memories are never injected.

## Testing

- Extractor tests for positive phrases, negation, conflict with current request semantics, and no-match cases.
- Repository or service tests for upsert, bounded listing, ownership enforcement, and soft delete.
- Prompt tests proving the memory section appears only when data exists and is capped.
- Controller or service tests proving user B cannot see or delete user A's memories.
- Full backend test suite and frontend build must pass.

## Acceptance Criteria

- The same user can state a preference in one conversation and receive it as context in another.
- Different users never see each other's memories.
- A user can inspect and delete their stored memories.
- Memory extraction requires no extra model call.
- Memory read/write failures do not block chat.
- No sensitive category is stored by the deterministic extractor.
