# Session Authentication and User Isolation Design

## Goal

Add account registration, username/password login, server-side sessions, logout, and strict per-user data isolation to the existing TravelPal agent service.

This phase establishes the identity boundary required by later long-term memory work. It does not yet implement profile extraction or long-term preference storage.

## Architecture

- Spring Security WebFlux owns authentication and authorization.
- Spring Session stores the authenticated `SecurityContext` in Redis in production.
- Development and test environments use the normal in-memory `WebSession` repository.
- The browser receives an opaque `HttpOnly` session cookie. No JWT is exposed to JavaScript.
- CSRF uses a readable `XSRF-TOKEN` cookie plus the `X-XSRF-TOKEN` request header.
- Every business request resolves the user from the server-side authentication context; client-supplied user IDs are ignored.

## Data Model

### `user_account`

- `id`: UUID string
- `username`: normalized lowercase login name, unique
- `password_hash`: BCrypt hash
- `display_name`: user-facing name
- `status`: `active`, `locked`, or `disabled`
- `failed_login_count`: consecutive failures
- `locked_until`: temporary lock expiry
- `last_login_at`: last successful login
- `created_at`, `updated_at`

Passwords are never stored or logged in plaintext. Login failures increment a counter; five failures lock the account for fifteen minutes. Successful authentication resets the counter.

## Authentication API

- `GET /api/auth/csrf`: ensures the CSRF cookie exists.
- `POST /api/auth/register`: creates a user and authenticated session.
- `POST /api/auth/login`: authenticates and rotates the anonymous web session.
- `POST /api/auth/logout`: invalidates the current server-side session.
- `GET /api/auth/me`: returns the current user or `401`.

Registration:

- Username: 3-32 characters, letters, digits, or underscore.
- Password: 8-72 characters.
- Display name: 1-50 characters.
- Duplicate usernames return a stable `409` business response.

## Security Rules

- Only auth bootstrap and health endpoints are public.
- All chat, conversation, message, model, knowledge, and memory-adjacent endpoints require authentication.
- Authentication failures return JSON with HTTP `401`.
- Authorization failures return JSON with HTTP `403`.
- Session fixation is prevented by creating a fresh session after successful login.
- Cookies use `HttpOnly`, `SameSite=Lax`, and `Secure` in production.
- Session policy is thirty minutes of inactivity and twelve hours absolute lifetime.
- Logout deletes the current server-side session immediately.

## Conversation Ownership

- `conversation.user_id` becomes the authoritative owner.
- A conversation is claimed by the authenticated user before any stream starts.
- Loading, renaming, deleting, reading details, submitting clarification answers, and appending messages all verify ownership.
- A foreign conversation is treated as not found to avoid leaking its existence.
- Existing rows owned by `anonymous` remain stored but are inaccessible until explicitly migrated.

## Component Boundaries

- `UserAccount`: persistence entity.
- `UserAccountMapper`: database access.
- `UserPrincipal`: immutable authenticated identity exposed to controllers.
- `UserAccountDetailsService`: loads active users for Spring Security.
- `UserAccountService`: registration, password validation, lockout, and login bookkeeping.
- `AuthController`: JSON auth endpoints and session establishment.
- `SecurityConfig`: filter chain, CSRF, session repository, and JSON error handlers.
- `ConversationAccessService`: one place for ownership checks shared by HTTP and streaming flows.
- `ApiClient`: frontend CSRF-aware fetch wrapper.
- `AuthProvider` and `LoginPage`: frontend authentication lifecycle and UI.

## Data Flow

1. Frontend obtains a CSRF token before sending state-changing requests.
2. Login authenticates via `ReactiveAuthenticationManager`.
3. Security context is saved into a new WebSession and persisted by Spring Session.
4. Controllers receive a `UserPrincipal` from Spring Security.
5. The principal ID is passed explicitly into ReAct, Graph, and Multi-Agent execution.
6. `ChatMemory` and `ConversationService` enforce ownership before reading or writing data.

## Error Handling

- Invalid credentials use a generic message to avoid username enumeration.
- Locked accounts report a retry time but not password details.
- Duplicate registration returns `409`.
- CSRF failures return `403`.
- Expired or missing sessions return `401`.
- Cross-user conversation access returns `404`.

## Testing

- Unit tests cover registration validation, BCrypt hashing, lockout transitions, and principal mapping.
- Security tests cover public auth endpoints, protected business endpoints, CSRF rejection, login/logout, and unauthenticated `401`.
- Isolation tests create two users and prove list, detail, rename, delete, chat-memory load, and clarification answer access cannot cross user boundaries.
- Frontend must pass `npm run build`.
- Backend must pass focused tests and `mvn test -DexcludedGroups=integration`.

## Non-Goals

- Long-term user preference extraction and retrieval.
- OIDC, SSO, MFA, password reset, email verification, and administrative user management.
- Revoking all sessions across devices; current-phase logout revokes the active server-side session.

## Acceptance Criteria

- Two users can register and log in independently.
- Refreshing the page preserves the authenticated session.
- Logout immediately makes protected API calls fail with `401`.
- A user cannot read, mutate, or continue another user's conversation.
- SSE chat, Graph, and Multi-Agent endpoints all use the server-authenticated user ID.
- Production configuration uses Redis-backed sessions; dev/test startup does not require Redis.
- Existing anonymous conversations are not exposed to newly registered users.
