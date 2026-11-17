# Session Authentication and User Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Spring Security server-side session login and enforce per-user ownership across all conversation and agent flows.

**Architecture:** Spring Security WebFlux authenticates users from a database-backed account table. Spring Session persists authenticated sessions in Redis in production and uses in-memory sessions in dev/test. Controllers derive user identity from `UserPrincipal`; conversation ownership is enforced before any message read, write, or stream starts.

**Tech Stack:** Spring Boot 3.5, WebFlux, Spring Security, Spring Session Redis, MyBatis-Plus, Flyway, H2 tests, React 19, TypeScript, Tailwind CSS.

---

### Task 1: Add security and session dependencies

**Files:**
- Modify: `agent-core/pom.xml`
- Modify: `agent-core/src/main/resources/application.yml`

- [ ] Add `spring-boot-starter-security` and `spring-session-data-redis`.
- [ ] Configure thirty-minute idle timeout and twelve-hour maximum session timeout.
- [ ] Configure `HttpOnly`, `SameSite=Lax`, and production-only secure cookies.
- [ ] Run `mvn -pl agent-core dependency:tree -Dincludes=org.springframework.security,org.springframework.session` and confirm both dependencies resolve.

### Task 2: Create the user account schema and persistence

**Files:**
- Create: `agent-core/src/main/resources/db/migration/V9__create_user_account.sql`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/entity/UserAccountEntity.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/UserAccountMapper.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/security/UserAccountRepository.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/security/UserAccountRepositoryTest.java`

- [ ] Write a repository test that inserts an account, loads it by normalized username, and asserts the password hash is not plaintext.
- [ ] Run `mvn -pl agent-core -Dtest=UserAccountRepositoryTest test` and confirm the test fails because the schema and repository do not exist.
- [ ] Add the `user_account` table with unique username and status indexes.
- [ ] Implement the entity and mapper.
- [ ] Implement blocking repository access wrapped with `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`.
- [ ] Re-run the focused test and confirm it passes.

### Task 3: Implement user principal and password-backed authentication

**Files:**
- Create: `agent-core/src/main/java/uno/zhuchen/agent/security/UserPrincipal.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/security/UserAccountDetailsService.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/security/UserAccountService.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/dto/RegisterRequest.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/dto/LoginRequest.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/domain/vo/AuthUserVO.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/security/UserAccountServiceTest.java`

- [ ] Write failing tests for valid registration, duplicate usernames, BCrypt hashing, five-failure lockout, successful-login reset, and mapping to `UserPrincipal`.
- [ ] Run `mvn -pl agent-core -Dtest=UserAccountServiceTest test` and confirm the expected failures.
- [ ] Implement registration validation and lower-case username normalization.
- [ ] Use `BCryptPasswordEncoder` with strength 12.
- [ ] Implement lockout transitions and generic invalid-credential behavior.
- [ ] Implement a reactive `UserDetailsService` that rejects locked or disabled accounts.
- [ ] Re-run the tests and confirm they pass.

### Task 4: Configure Spring Security and JSON auth endpoints

**Files:**
- Create: `agent-core/src/main/java/uno/zhuchen/agent/security/SecurityConfig.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/security/ProductionRedisSessionConfig.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/security/JsonAuthenticationEntryPoint.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/security/JsonAccessDeniedHandler.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/controller/AuthController.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/common/GlobalExceptionHandler.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/security/SecurityConfigTest.java`

- [ ] Write a `WebTestClient` test proving `/api/auth/csrf` is public, `/api/models` is protected, and a valid login establishes a session.
- [ ] Run the test and confirm it fails.
- [ ] Configure a stateless login endpoint with a stateful `WebSessionServerSecurityContextRepository`.
- [ ] Configure `CookieServerCsrfTokenRepository.withHttpOnlyFalse()`.
- [ ] Disable form login, HTTP Basic, and Spring Security's default logout page.
- [ ] Add JSON `401` and `403` handlers.
- [ ] Implement `GET /api/auth/csrf`, `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/logout`, and `GET /api/auth/me`.
- [ ] Ensure successful register/login saves a new authenticated session.
- [ ] Ensure logout invalidates the current session.
- [ ] Re-run the security test and confirm it passes.

### Task 5: Add conversation ownership enforcement

**Files:**
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/ChatMemory.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/InMemoryChatMemory.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/memory/PersistentChatMemory.java`
- Create: `agent-core/src/main/java/uno/zhuchen/agent/persistence/service/ConversationAccessService.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/persistence/service/ConversationService.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/persistence/mapper/ConversationMapper.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/persistence/service/ConversationIsolationTest.java`

- [ ] Write failing tests showing user B cannot load, append, list, read, rename, or delete user A's conversation.
- [ ] Run the focused test and confirm it fails.
- [ ] Change `ChatMemory` to require `userId` on claim, load, save, and clear operations.
- [ ] Create the owner row before a stream starts and fail closed if an existing row has another owner.
- [ ] Treat foreign conversations as not found for HTTP requests.
- [ ] Update conversation mapper queries to require both `id` and `user_id`.
- [ ] Re-run the isolation tests and confirm they pass.

### Task 6: Thread authenticated user identity through every agent path

**Files:**
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/controller/AgentController.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/controller/ConversationController.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/controller/MessageController.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/agent/ReactAgent.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/agent/core/multi/MultiAgentManager.java`
- Modify: `agent-core/src/main/java/uno/zhuchen/workflow/builder/GraphStreamRunner.java`
- Test: `agent-core/src/test/java/uno/zhuchen/agent/controller/AgentUserIsolationTest.java`

- [ ] Write failing tests proving ReAct, Graph, Multi, sync chat, and clarification answer all receive the authenticated user ID rather than request data.
- [ ] Add `@AuthenticationPrincipal UserPrincipal` to protected controllers.
- [ ] Remove the `userId` query parameter from conversation listing.
- [ ] Pass `principal.userId()` explicitly into ReAct, Graph, and Multi entry points.
- [ ] Claim the conversation as owned before starting each stream.
- [ ] Verify clarification submission belongs to the authenticated user before routing it to the broker.
- [ ] Re-run controller isolation tests and confirm they pass.

### Task 7: Add the frontend authentication client and login flow

**Files:**
- Create: `frontend/src/lib/apiClient.ts`
- Create: `frontend/src/auth/AuthProvider.tsx`
- Create: `frontend/src/auth/LoginPage.tsx`
- Modify: `frontend/src/main.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/types/index.ts`

- [ ] Add an `apiFetch` wrapper that sends cookies and copies `XSRF-TOKEN` into `X-XSRF-TOKEN` for mutating requests.
- [ ] Implement `AuthProvider` with `me`, `login`, `register`, `logout`, and unauthorized handling.
- [ ] Render `LoginPage` when no valid session exists.
- [ ] Render the existing app only after authentication succeeds.
- [ ] Add a logout control with the authenticated display name.
- [ ] Run `npm run build` and confirm TypeScript and Vite succeed.

### Task 8: Route all frontend API calls through the authenticated client

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/hooks/useChat.ts`
- Modify: `frontend/src/hooks/useConversations.ts`
- Modify: `frontend/src/hooks/useKnowledge.ts`
- Modify: `frontend/src/components/ChatMessage.tsx`

- [ ] Replace direct `fetch` calls with `apiFetch`.
- [ ] Ensure SSE POSTs include the current CSRF token.
- [ ] On `401`, clear local authentication state and show the login page.
- [ ] Remove all client-side `userId` query parameters.
- [ ] Run `npm run build` and confirm it passes.

### Task 9: Verify backend, frontend, and isolation behavior

**Files:**
- Modify: `README.md`
- Modify: `agent-core/src/main/resources/.env.example`
- Verify: all files above

- [ ] Document registration/login endpoints, session storage, and required security environment variables.
- [ ] Run `mvn -pl agent-core -Dtest=UserAccountServiceTest,UserAccountRepositoryTest,SecurityConfigTest,ConversationIsolationTest,AgentUserIsolationTest test`.
- [ ] Run `mvn test -DexcludedGroups=integration`.
- [ ] Run `npm run build` from `frontend`.
- [ ] Start the backend and frontend, register two users, verify two isolated conversation lists, and verify logout produces `401`.
- [ ] Commit the finished change on `codex/feature/session-auth-isolation`.
