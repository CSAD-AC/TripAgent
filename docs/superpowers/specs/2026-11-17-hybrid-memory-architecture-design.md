# Hybrid Long-Term Memory Architecture Design

## Status

Approved for specification. This document describes the target architecture and phased delivery plan. It does not authorize implementation by itself.

## Problem Statement

The current long-term memory implementation stores a small set of deterministic, user-stated preferences and injects all active items into every request. This is safe and predictable, but it cannot recall past plans, decisions, or travel experiences across conversations, and it does not distinguish durable profile facts from short-lived task context.

Users of a travel agent repeatedly restate constraints and lose continuity between sessions. For an enterprise-grade system, memory also needs source tracking, correction, deletion propagation, tenant and user isolation, prompt budgets, auditability, and graceful degradation when the vector store is unavailable.

## Goals

1. Support both structured user memory and episodic recall across conversations.
2. Keep structured memory as the source of truth and treat vector indexes as rebuildable derived data.
3. Bound memory injection with explicit token and item budgets so long-term memory cannot flood the prompt.
4. Provide user-visible controls for review, correction, deletion, and “do not remember” rules.
5. Preserve strict isolation by authenticated user and prepared tenant and project scopes.
6. Provide measurable quality, latency, privacy, and reliability targets before rollout.

## Non-Goals

1. Full graph memory or organization-wide entity graph in v1. The schema and metadata should leave room for it, but graph traversal is not delivered in this phase.
2. Storing raw credentials, health data, government identifiers, or unredacted sensitive content. These categories are blocked by policy.
3. Treating every chat message as a durable memory. Raw transcript remains conversation history, not memory.
4. Replacing conversation summaries or short-term chat memory. The memory layer complements them.
5. Cross-user sharing, global memory, or administrator viewing of user memory contents without an explicit compliance workflow.

## User Stories

### End User

- As a traveler, I want the agent to remember my stable preferences and constraints so that I do not repeat them in every conversation.
- As a traveler, I want the agent to recall relevant past trips and planning decisions so that follow-up questions have continuity.
- As a traveler, I want to see what the agent remembers about me so that I can correct or delete it.
- As a traveler, I want a “do not mention this again” rule so that sensitive or stale details stop influencing future responses.
- As a traveler, I want my current explicit request to override any remembered preference.

### Product and Operations

- As a product owner, I want memory-related interactions to be measurable so that we can tell whether recall improves task completion.
- As an operator, I want vector indexes to be rebuildable from the relational source of truth so that model upgrades and outages are recoverable.
- As a compliance owner, I want source, scope, model version, and deletion history recorded so that memory behavior is auditable.

## Target Architecture

### Memory Layers

| Layer | Contents | Source of truth | Retrieval |
|---|---|---|---|
| Short-term session | Latest messages, tool outputs, temporary task state | Existing conversation storage or Redis | Direct read by `userId + conversationId` |
| Structured profile | `profile`, `preference`, `constraint`, `veto` | MySQL | Schema-bound direct read |
| Episodic memory | Past trips, plans, decisions, important outcomes | MySQL | SQL filters plus Milvus recall |
|治理与审计 | Source, version, model, confidence, deletion, revision history | MySQL | Management API and audit queries |

The architecture follows a two-tier pattern: short-lived session context remains separate from durable memory, and durable memory uses relational storage for governance plus vector storage for semantic recall. This is consistent with current enterprise memory guidance that separates buffer, summary, episodic, and entity/rule memory and uses hybrid retrieval over structured and vector data [AlloyDB long-term memory](https://cloud.google.com/blog/products/databases/implementing-long-term-ai-agent-memory-in-alloydb-and-memorystore).

Design references:

- ChatGPT separates saved memories from chat-history references and provides explicit review, correction, deletion, and project-scoped memory controls [Memory in ChatGPT](https://help.openai.com/en/articles/8590148-memory-in-chatgpt).
- Mem0 uses SQL as the source of truth for facts and metadata, vector storage for semantic retrieval, and scopes memory by user, agent, app, and session [Mem0 how it works](https://github.com/mem0ai/mem0/blob/main/docs/core-concepts/how-it-works.mdx).
- Google Memory Bank uses fixed-schema structured profiles for low-latency, consistent profile access [Memory Profiles](https://docs.cloud.google.com/gemini-enterprise-agent-platform/scale/memory-bank/profiles).

### Memory Types

| Type | Purpose | Examples | Update policy |
|---|---|---|---|
| `profile` | Stable user facts useful for personalization | home city, language, usual departure city | Explicit statement required; supersede old value |
| `preference` | Soft preferences | likes nature, beach, local food | Merge or overwrite by normalized key |
| `constraint` | Hard rules for planning | non-spicy, no red-eye flights, wheelchair accessible | High priority; explicit correction required |
| `veto` | Suppression rule | do not mention this topic, do not remember this category | Highest priority; never surfaced raw in prompt |
| `episodic` | Time-bound experiences and decisions | visited Kyoto, planned Sichuan trip, rejected hotel area | Append by default; mark superseded rather than overwrite |
| `summary` | Compressed session or project history | long-running task summary | Stored in session/project memory, not durable fact store |

### Source-of-Truth Rule

MySQL is authoritative for all memory records. Milvus contains derived embeddings keyed by the MySQL memory item ID. Any Milvus collection can be rebuilt from MySQL. Deletion first marks the MySQL row inactive, then removes or filters the corresponding vector. Milvus failure never prevents storing the relational memory.

## Data Model

This design replaces the current single-purpose `user_memory` table with a generalized `memory_item`, or migrates the current table into that shape. Flyway manages the schema.

### `memory_item`

- `id`: UUID, primary key.
- `tenant_id`: tenant identifier, default `default`.
- `user_id`: authenticated user identifier. Mandatory.
- `project_id`: nullable project or workspace scope.
- `memory_type`: `profile`, `preference`, `constraint`, `veto`, or `episodic`.
- `memory_key`: normalized key, for example `travel.preference.food` or `trip.visited.city`.
- `memory_value`: short normalized value.
- `content`: display text rendered from key and value.
- `attributes_json`: structured metadata such as city, date range, budget amount, entity tags.
- `source_type`: `user_message`, `assistant_message`, `tool_result`, `import`, or `system`.
- `source_conversation_id`: source conversation ID.
- `source_message_id`: source message ID when available.
- `confidence`: decimal from 0 to 1.
- `importance`: decimal from 0 to 1, used in ranking.
- `status`: `active`, `deleted`, `superseded`, or `rejected`.
- `superseded_by`: memory item ID that replaced this item, when applicable.
- `expires_at`: optional expiry time.
- `embedding_status`: `not_required`, `pending`, `indexed`, `failed`, or `deleted`.
- `embedding_model`: model identifier used for the vector.
- `embedding_version`: version or fingerprint of the embedding model.
- `extractor_version`: memory extraction prompt or rule version.
- `created_at`, `updated_at`, `deleted_at`.

Indexes:

- Unique or conditional unique scope on (`tenant_id`, `user_id`, `project_id`, `memory_type`, `memory_key`, `status`) for non-episodic types.
- Index on (`tenant_id`, `user_id`, `status`, `memory_type`, `updated_at`).
- Index on (`tenant_id`, `user_id`, `status`, `expires_at`).

### `memory_ingest_event`

- `id`: UUID.
- `tenant_id`, `user_id`, `conversation_id`.
- `event_type`: `message`, `tool_result`, `correction`, or `delete`.
- `payload_json`: bounded serialized input.
- `status`: `pending`, `processing`, `done`, `failed`, or `skipped`.
- `attempt_count`, `last_error`, `created_at`, `processed_at`.

The event table enables asynchronous extraction and replay.

### `memory_revision`

- `id`, `memory_item_id`, `operation`, `previous_value`, `new_value`, `actor`, `reason`, `created_at`.

Revisions support user correction and compliance audits.

### Milvus Collection

Collection: `user_episodic_memory_v1`

Fields:

- `id`: memory item ID as the primary key.
- `vector`: embedding of `content` plus relevant structured fields.
- `tenant_id`, `user_id`, `project_id`.
- `memory_type`, `status`, `importance`, `created_at`, `updated_at`.
- `source_conversation_id`, `embedding_model`, `embedding_version`.

All searches must apply server-side filter expressions for `tenant_id`, `user_id`, `status = active`, and optional project scope. Callers cannot supply arbitrary filters.

## Write Path

1. A chat request finishes and the framework emits a completed-turn event containing the raw user message, final assistant response, conversation ID, authenticated user ID, and selected tool outputs.
2. A synchronous fast extractor stores only high-confidence, explicitly stated structured values. This preserves current behavior and keeps low-risk preferences immediately available.
3. The framework enqueues a `memory_ingest_event` for deeper extraction.
4. A background worker loads recent related memories from MySQL for deduplication and conflict detection.
5. An LLM extracts candidates as fixed JSON. The prompt includes memory type definitions, schema constraints, sensitive-category exclusions, and the current query context.
6. Policy checks run before persistence: sensitive data redaction, tenant and user binding, veto enforcement, duplicate detection, confidence threshold, and expiry assignment.
7. Structured candidates are upserted or superseded according to type policy.
8. Episodic candidates are appended to `memory_item` with `embedding_status = pending`.
9. An embedding worker vectorizes pending items and upserts them into Milvus.
10. If Milvus fails, the relational item remains active with `embedding_status = failed` and a retry task is scheduled.

### Extraction Rules

- Use a small, versioned JSON schema for extraction output.
- Store only reusable facts: preferences, constraints, goals, decisions, plans, and experiences.
- Do not store assistant reasoning, secrets, raw credentials, health data, government identifiers, or emotional profanity.
- An update such as “I moved from Shanghai to Hangzhou” creates a new current value and marks the previous one superseded.
- A veto such as “do not remember my salary” creates or updates a `veto` rule and blocks matching future candidates.
- Extractor prompts and rule versions are recorded on each memory item.

## Read Path

1. The authenticated principal supplies `tenantId` and `userId`. Project scope, when present, comes from trusted server context.
2. Load pinned structured memories: active `profile`, `constraint`, and `veto` records. Veto rules are converted into injection suppressions, not injected as raw personal data.
3. Build a retrieval query from the current message plus the latest one or two conversation turns.
4. Search Milvus for episodic candidates with `topK = 20` and mandatory tenant/user/status filters.
5. Query MySQL for structured matches, recent episodic items, entity key matches, and superseding relations.
6. Fuse candidates using weighted ranking:
   - semantic similarity
   - keyword or entity overlap
   - recency
   - confidence
   - importance
   - source reliability
   - user feedback
7. Apply veto and suppression rules.
8. Compress selected items into a bounded prompt section using `finalTopK = 6`.
9. Inject into ReAct and Multi-Agent system prompts; inject into Graph via `INPUT_USER_MEMORY` in the initial state.
10. If retrieval fails, continue with pinned structured memories only.

### Ranking Formula

The initial ranking formula is intentionally tunable:

```text
score = 0.35 * semantic
      + 0.20 * lexical
      + 0.15 * entity
      + 0.15 * recency
      + 0.10 * confidence
      + 0.05 * importance
```

Weights are configuration, not constants. Evaluation results may adjust them without a schema migration.

## Prompt Budget

Configuration keys:

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

Rules:

- Total injected memory never exceeds `total-max-tokens`.
- Structured profile and constraints are injected first.
- Episodic items fill the remaining budget only after ranking.
- Current explicit user requests always override stored preferences.
- The prompt contains source labels and dates when useful, for example “2026-11-17: planned a Kyoto trip”.
- Empty memory produces no injected section.

## Module Boundaries

| Module | Responsibility | Depends on |
|---|---|---|
| `MemoryIngestService` | Receives completed-turn events and enqueues extraction work | Agent framework, event table |
| `MemoryExtractionWorker` | Async candidate extraction, validation, supersede logic | LLM gateway, policy service, MySQL |
| `MemoryPolicyService` | Sensitive filtering, veto checks, confidence thresholds | Policy config |
| `MemoryItemService` | CRUD, ownership, revisions, expiry, soft delete | MySQL mappers |
| `MemoryVectorService` | Embedding, upsert, delete, rebuild, collection health | Embedding client, Milvus |
| `MemoryRetrievalService` | Hybrid recall, fusion, rerank, suppression | MySQL, Milvus |
| `MemoryPromptRenderer` | Bounded prompt rendering for all agent modes | Retrieval result |
| `MemoryController` | User-facing list, update, delete, veto, settings | Memory item service |
| `MemoryAdminController` | Reindex, health, queue stats, audit search | Operations services |

Keep these modules independent enough to unit test without a live vector store. The existing RAG retrieval code in the MCP server can be reused for embedding client configuration and Milvus access patterns, but user memory must use a separate collection and filter namespace from knowledge RAG.

## API Design

Existing endpoints continue to work and are backed by structured memory.

- `GET /api/memory`: list active user memories with type and source metadata.
- `DELETE /api/memory/{id}`: soft-delete one owned item and enqueue vector deletion.
- `DELETE /api/memory`: soft-delete all active items for the current user.

New endpoints:

- `PATCH /api/memory/{id}`: correct value or status.
- `POST /api/memory/veto`: create a suppression rule.
- `GET /api/memory/revisions/{id}`: list revisions for an owned item.
- `POST /api/memory/reindex`: enqueue rebuild of the current user's episodic vectors.
- `GET /api/admin/memory/health`: index freshness, pending items, failed events.

Foreign or missing IDs return `404` to avoid leaking existence.

## Isolation and Governance

- `tenant_id` and `user_id` are injected by the server from authentication and session context. They are never accepted from the request body for business APIs.
- All MySQL queries include tenant and user predicates.
- All Milvus searches use server-built filter expressions.
- Knowledge RAG, user memory, and any future graph memory use separate collections or namespaces.
- Deletion is soft in MySQL and asynchronously propagated to Milvus. The SLA is that deleted items are not searchable within five minutes.
- Memory settings allow disabling personalization and disabling episodic recall independently.
- Temporary or unpersonalized chats do not create or update memory.
- Sensitive data policy is applied before embedding. Rejected candidates are stored only in the ingest event with a status of `skipped` or `rejected`.
- Each memory item records source message and extractor version for audit.

## Failure Handling

- Structured memory failure: log warning, continue chat.
- Milvus timeout: use MySQL structured memory only.
- Embedding failure: keep item active with failed index status and retry with backoff.
- Extraction LLM failure: mark event failed and retry; never block the user response.
- Ranking failure: fall back to pinned structured memory.
- Reindex is idempotent and uses memory item IDs as vector primary keys.

## Phased Delivery and Timeline Considerations

### Phase 1: Structured Profile Enhancement

- Migrate or adapt `user_memory` to `memory_item` fields.
- Add source, confidence, importance, status, expiry, revision, and veto support.
- Keep deterministic extraction initially.
- Add strict prompt budget and conflict priority.

Exit criteria: profiles and constraints are governed, auditable, and bounded.

### Phase 2: Episodic Memory and Vector Infrastructure

- Add `memory_ingest_event` and background worker.
- Add LLM-based candidate extraction with fixed JSON schema.
- Create `user_episodic_memory_v1` in Milvus.
- Implement embedding, indexing, deletion propagation, and reindex.

Exit criteria: an episodic memory can be created in one conversation and rebuilt from MySQL into Milvus.

### Phase 3: Hybrid Retrieval and Injection

- Implement structured plus vector recall, fusion, rerank, and suppression.
- Integrate with ReAct, Graph, and Multi-Agent.
- Add configuration, metrics, and fail-open behavior.

Exit criteria: relevant episodic memories are injected within the configured budget, and outages degrade safely.

### Phase 4: Administration and Consolidation

- Add user management UI, admin audit, retention policies, TTL, and periodic consolidation.
- Consider graph memory only after retrieval quality data justifies it.

Exit criteria: operations can observe and maintain memory health without direct database access.

## Success Metrics

### Leading Indicators

- Memory recall precision@6: at least 0.80 on the memory evaluation set.
- Memory recall recall@6: at least 0.70 for annotated relevant memories.
- Prompt memory size: p95 at or below 1000 tokens.
- Retrieval added latency: p95 at or below 250 ms.
- Cross-user leakage: zero in automated tests and manual smoke tests.
- Deletion propagation: 95% of vectors filtered or removed within five minutes.

### Lagging Indicators

- Reduction in repeated preference statements across sessions by at least 30% after four weeks.
- Follow-up task completion rate in multi-session travel planning improves by at least 10%.
- User memory correction or deletion actions remain below 5% of sessions with memory injection.
- No increase in chat failure rate attributable to memory services.

Measurement method: offline evaluation set plus production metrics collected from request traces and memory service events.

## Testing Strategy

- Unit tests for extraction schema validation, policy checks, supersede logic, ranking, budgeting, and prompt rendering.
- Repository tests for ownership, soft delete, re-add after delete, expiry, and revision history.
- Vector tests with a fake vector store in unit tests and a real Milvus testcontainer or integration tag for end-to-end indexing.
- Isolation tests proving user A cannot read, mutate, delete, or retrieve user B's memories.
- Failure-injection tests for Milvus timeout, embedding failure, extraction failure, and ranking failure.
- Prompt snapshot tests proving memory order, labels, bounds, and current-request precedence.
- Offline evaluation set covering profile recall, episodic recall, contradictory facts, veto rules, stale facts, and sensitive content rejection.

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Wrong or stale memory influences response | Source labels, recency weighting, supersede relations, user correction, explicit-current-request precedence |
| Vector store leaks across users | Server-built filters, separate collection, mandatory tenant/user predicates, isolation tests |
| Prompt bloat | Hard token and item budgets, ranking before rendering |
| Sensitive data indexed | Policy filter before MySQL commit and before embedding, veto rules, audit |
| Async extraction lag | Synchronous high-confidence path, event status visibility, retries |
| Reindex cost | Idempotent upserts by memory ID, batch embedding, per-user reindex |
| Complex graph expansion | Deferred to phase 4 after measured retrieval quality gaps |

## Open Questions

1. Engineering: should the first implementation migrate `user_memory` to `memory_item`, or create `memory_item` and keep `user_memory` as a compatibility view? Recommendation: migrate in a Flyway migration with a compatibility service adapter.
2. Product: what project or workspace scope exists in the current product model? Recommendation: use `project_id = null` until project-level chat is introduced.
3. Data: which embedding model should be used for user memory? Recommendation: reuse the configured RAG embedding model initially and record `embedding_model` and `embedding_version` on every item.
4. Compliance: what retention window is required for deleted memory revisions? Recommendation: 30 days for safety and debugging, configurable.
5. Engineering: should extraction LLM calls use the same provider as chat? Recommendation: yes initially, with a separate model identifier so cost and quality can be measured.

## Acceptance Criteria

- A user's stable preference stated in one conversation is available in another conversation.
- A user's past planned trip can be recalled by a semantically related question without matching exact keywords.
- Structured profile, constraint, and veto items are always loaded without a vector search.
- Injected memory respects all configured token and item limits.
- Current explicit user requests override stored preferences in generated responses.
- A deleted memory is no longer listed, injected, or searchable after the deletion SLA.
- A user cannot access another user's memory through any API, prompt injection path, or vector search.
- Vector store and extraction failures do not fail chat requests.
- All memory items can be rebuilt into Milvus from MySQL.
- Unit, repository, isolation, prompt, failure-injection, and frontend build checks pass before release.
