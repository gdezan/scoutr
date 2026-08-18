# Canonical Session Model v3 Blueprint

## Current situation

Scoutr has one user concept — a coding-agent session — represented by several transport/storage identities:

- live Herdr `paneId`, `workspaceId`, and `tabId`;
- persisted `sessionPath`;
- backend/transcript `id`;
- `agentKind` and `cwd`.

`AgentCard` in `bridge/src/routes/agents.ts` is live-pane-first and enriches itself with a resolved session path. `CatalogSession` in `bridge/src/session-catalog.ts` is stored-session-first and joins a live pane when a path matches. Android mirrors both separately in `AgentCard` and `SessionCatalogItem` in `data/Models.kt`; `MainActivity` routes Chat with both `paneId` and optional `sessionPath`, while `SessionHistoryViewModel` starts from the stored path and resolves back to a pane on resume.

The bridge already has the key seams needed to normalize this without adding a database:

- `AgentBackend.resolveSessionPath` owns backend-specific resolution;
- `resolveCatalogSessionPath` validates canonical stored paths;
- `BoardDetailCache` already performs bounded transcript metadata reads for live cards;
- the session catalog already joins persisted metadata with active pane state.

Precedent: bridge-owned backend adapters normalize agent differences before Android sees them. This plan applies the same rule to session identity.

## Objective and why

Make the bridge expose one canonical `SessionDescriptor` shape for both live Board sessions and persisted Sessions/history, with one stable `SessionKey` representing the stored coding-agent session and one optional live attachment representing its current Herdr pane.

Done means Android no longer treats `paneId` and `sessionPath` as peer identities. A session is identified by its canonical key; pane/workspace/tab ids are runtime attachment fields used only when the session is live.

## Scope

Included:

- canonical bridge `SessionKey` and `SessionDescriptor` contracts;
- reuse of that descriptor in `/api/agents` and session-catalog responses;
- Android DTO/model adoption;
- Chat/navigation cleanup so existing sessions are opened by canonical session identity while live actions consume `descriptor.live.paneId`;
- local pin/archive keys upgraded from raw path to canonical session key;
- focused migration of existing on-device pin/archive preferences;
- tests covering live, stored, resumed, missing-live, and backend-specific path resolution.

Non-goals:

- no database or event store;
- no multi-host UI yet;
- no attempt to persist Herdr pane identities across restarts;
- no rewrite of transcript parsing;
- no change to how Herdr creates workspaces/tabs;
- no requirement that an agent have a transcript path before its very first frame after launch. A short bootstrap state is allowed, but it must converge to the canonical descriptor as soon as the backend can resolve the session path.

## Global constraints

- The bridge remains authoritative for backend/session normalization; Android must not infer backend-specific paths.
- `AgentBackend.resolveSessionPath` remains the backend-specific path seam.
- Session key validation must pass through existing registered-backend/session-root guards; never turn an arbitrary client-supplied path into filesystem access.
- Live Herdr ids are ephemeral attachment fields, not durable identity.
- Android remains manual-DI, ViewModel → `ScoutrApi`.
- Final emulator/integration/E2E verification is terminal after review-clean/code-freeze.

## Resolved decisions

### Canonical identity

Use a structured key, not a new persisted registry:

```ts
interface SessionKey {
  agentKind: string;
  path: string; // canonical, backend-owned transcript path
}
```

The bridge already resolves path-kind and id-kind agent session references to a transcript path. That canonical path, namespaced by backend, is sufficient for Scoutr's current single-host model and survives bridge/Android restarts. Multi-host can later namespace the same key with a host id without changing the live/stored ownership model.

Do **not** use `paneId` as fallback durable identity. A newly launched session may temporarily have `key: null`; that is a bootstrap state, not a second identity model.

### Canonical descriptor

```ts
interface SessionDescriptor {
  key: SessionKey | null;
  agentKind: string;
  displayName: string;
  title: string;
  cwd: string | null;
  model: string | null;
  thinkingLevel: string | null;
  capabilities: string[];
  updatedAtMs: number | null;
  latestActivity: string | null;
  live: null | {
    paneId: string;
    workspaceId: string;
    tabId: string;
    status: string;
    statusSinceMs: number | null;
  };
}
```

A stored session is the same descriptor with `live = null`. A live session with an unresolved transcript is the same descriptor with `key = null` until resolution succeeds.

### API migration

- `/api/agents` may retain the top-level `agents` collection for route compatibility, but each item becomes/contains the canonical descriptor instead of a separate live-only `AgentCard` vocabulary.
- `/api/session-catalog` returns the same descriptor plus catalog-only metadata such as created time if needed; avoid duplicating shared fields.
- Android route state should carry an encoded `SessionKey` when available. `paneId` remains a bootstrap route only for a just-created session whose key is not available yet; the first successful session/agent refresh replaces it with canonical state.
- Pin/archive storage moves to a serialized canonical key. Existing raw-path values are migrated by matching them to catalog results and retained when a unique registered backend claims the path.

### Rejected alternatives

- A bridge database/registry was rejected: current session stores already provide durable identity and adding another persisted source of truth would create the synchronization problem this plan is trying to remove.
- Hash-only opaque ids were rejected: they require a lookup index/scanning layer to reverse on every request and add no security boundary because the app already legitimately receives session paths.

## Approach

Create a small bridge session-model module responsible for constructing `SessionKey`/`SessionDescriptor` from live snapshot entries and catalog transcript metadata. Reuse backend path resolution and bounded detail reads. Refactor the two API surfaces to emit this shared model. Mirror the model once on Android and adapt Board/History/Chat callers to consume it.

The key architectural invariant is:

> persisted session identity belongs to the transcript/backend store; current pane identity belongs to `descriptor.live` and can appear/disappear without changing the session key.

## Contracts and interfaces

### Bridge module

Create a module such as `bridge/src/session-model.ts` exporting the shared types and constructors/helpers. It must not own Herdr transport or filesystem policy; it composes existing seams.

At minimum:

```ts
export interface SessionKey { agentKind: string; path: string }
export interface SessionLiveAttachment { ... }
export interface SessionDescriptor { ... }

export async function keyForAgent(
  backend: AgentBackend,
  ref: AgentSessionInfo,
  cwd?: string,
): Promise<SessionKey | null>
```

A client-supplied key used to read/mutate a stored session must be validated through `resolveCatalogSessionPath`/backend ownership before use.

### Android model

Mirror `SessionKey`, `SessionLiveAttachment`, and `SessionDescriptor` once in `data/Models.kt` or split DTO files if that improves readability. UI-derived helpers such as `blocked` may live as Kotlin extension properties.

### Cross-change interface table

| Change | Consumes | Produces |
|---|---|---|
| 1 | backend path resolution + transcript metadata | bridge `SessionKey` / `SessionDescriptor` |
| 2 | shared descriptor | `/api/agents` and `/api/session-catalog` normalized payloads |
| 3 | normalized payloads | Android canonical session models |
| 4 | Android session models | Board/History/Chat/navigation using key + live attachment |
| 5 | canonical key | migrated pin/archive preference keys |

## Changes

- [x] **1 — Introduce the bridge canonical session model**
  - Anchor: `bridge/src/agents/types.ts` → `AgentBackend.resolveSessionPath`
  - Anchor: `bridge/src/board-detail.ts` → `BoardDetailCache.detailFor`
  - Create shared session-model types/helpers.
  - Extend bounded live metadata reads only as needed to obtain shared title/model/activity/session metadata; do not add unbounded transcript reads.
  - Build live attachment exclusively from the current Herdr snapshot.
  - Proof: unit tests construct the same `SessionKey` for the same stored session whether reached from a live agent reference or catalog path.

- [x] **2 — Normalize Board and session-catalog API payloads**
  - Anchor: `bridge/src/routes/agents.ts` → `deriveAgentCardsWithDetail`, `agents`
  - Anchor: `bridge/src/session-catalog.ts` → `CatalogSession`, `listSessionCatalog`
  - Replace duplicated common session fields with the shared descriptor shape. Preserve route-specific collection wrappers and catalog-only fields where they carry real meaning.
  - A live agent whose path cannot yet resolve returns `key: null` but still has `live` and enough title/cwd/backend data to render.
  - A stored inactive session returns the same key and `live: null`.
  - Proof: bridge tests cover active, inactive, resumed, and unresolved-bootstrap descriptors for pi plus at least one id-kind backend such as Claude.

- [x] **3 — Mirror the canonical model in `ScoutrApi`/Android DTOs**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/data/Models.kt` → `AgentCard`, `SessionCatalogItem`
  - Anchor: `android/app/src/main/java/dev/scoutr/app/net/ScoutrApi.kt` → agents/catalog/session methods
  - Introduce one `SessionDescriptor` data model and adapt route response wrappers to use it.
  - Keep UI helpers derived rather than duplicating wire state.
  - Update `FakeScoutrApi` fixtures/builders so tests create sessions through the canonical shape.
  - Proof: Android unit suite compiles with no separate pane-first vs catalog-first shared field models.

- [x] **4 — Make navigation and ViewModels session-key-first**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/MainActivity.kt` → `Routes.CHAT`, `Routes.chat`, Board and Sessions navigation callbacks
  - Anchor: `android/app/src/main/java/dev/scoutr/app/state/ChatViewModel.kt` → session identity/bootstrap state
  - Anchor: `android/app/src/main/java/dev/scoutr/app/state/SessionHistoryViewModel.kt` → resume/fork/open result
  - Existing stored/live sessions navigate with encoded `SessionKey`; Chat resolves current live attachment from bridge state rather than treating a route `paneId` as permanent identity.
  - Preserve a clearly isolated bootstrap route/state for a just-created agent with `key == null`; once a canonical key appears, replace the bootstrap identity without creating a duplicate chat/session.
  - Live controls must fail clearly when `descriptor.live == null` instead of using a stale pane id.
  - Proof: tests cover opening from Board, opening stored history, resume then open, live pane disappearing while transcript remains, and bootstrap convergence.

- [x] **5 — Move local session metadata to canonical keys**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/data/SessionCatalogStore.kt` → pinned/archive storage
  - Store pin/archive membership by serialized `(agentKind, canonicalPath)` rather than raw path alone.
  - On first read after upgrade, migrate legacy raw paths that can be uniquely associated with a catalog item; leave ambiguous/unresolvable legacy entries untouched until a matching catalog item appears rather than assigning the wrong backend.
  - Do not make bridge writes for pin/archive; these remain device preferences.
  - Proof: store tests show old pins/archives survive migration and two backend-qualified keys cannot collide.

## Failure handling

- Live agent has no resolvable transcript yet: `key = null`, never synthesize a pane-based durable id.
- Stored key points outside a registered backend store: reject through existing path guards.
- Pane closes: descriptor keeps the same key and becomes `live = null`; Chat/history remains readable.
- Session file disappears: stored-session read becomes not-found; a stale live attachment must not authorize arbitrary path fallback.
- Backend resolution fails transiently: keep live descriptor with null key and retry on next normal refresh.
- Legacy pin/archive migration cannot identify backend uniquely: do not guess.

## Validation

1. Focused bridge session-model, agents-route, and catalog tests.
2. Focused Android model/ViewModel/navigation/store tests.
3. `make bridge-test`.
4. `make android-test`.
5. Independent review using `skills/scoutr-review/SKILL.md` with special attention to stale-live identity and path authorization.
6. After code freeze, final runtime acceptance via `skills/scoutr-verification/SKILL.md`: open one live session from Board, one stored session from Sessions, close/reopen/resume, and verify the app preserves one session identity throughout.

## Local discretion

- Exact file split for Android DTOs after removing the current oversized shared model file.
- Exact serialization form for `SessionKey` in navigation/prefs, provided it is versionable and round-trips arbitrary valid paths.
- Whether catalog-only timestamps wrap the descriptor or extend a catalog row type.

## Escalation triggers

- A backend cannot produce a stable canonical transcript path for an active session after startup.
- Session paths are expected to move/rename independently of the session and therefore cannot serve as durable identity.
- Supporting multi-host is pulled into this implementation; that requires host namespace decisions beyond this plan.
- Resolving a key requires an unbounded global catalog scan on ordinary chat/control requests.
- Callers begin needing pane ids to reconstruct or validate the canonical key; that reverses the ownership boundary.

## Review handoff

The reviewer must trace one session through: persisted catalog → resume → live Board → Chat → pane close → persisted history. The `SessionKey` must remain constant while only `live` changes. Verify every filesystem use of a client-provided key passes the existing registered-backend/path validation boundary and no UI layer derives backend paths.

Rerun `make bridge-test` and `make android-test`, then perform final runtime acceptance only after all review findings are resolved.

## Completion checklist

- [x] Shared bridge `SessionKey` and `SessionDescriptor` exist.
- [x] Board and catalog expose the same common session model.
- [x] Android has one canonical session model.
- [x] Pane/workspace/tab ids live only inside optional runtime attachment state.
- [x] Chat/navigation are session-key-first with one isolated fresh-launch bootstrap path.
- [x] Pin/archive metadata is canonical-keyed and legacy values migrate safely.
- [x] Path/backend authorization remains unchanged or stricter.
- [x] Bridge and Android cheap suites pass.
- [ ] Independent review is clean.
- [ ] Final runtime acceptance passes once, last.

## References

- `AGENTS.md`
- `bridge/src/agents/types.ts`
- `bridge/src/routes/agents.ts`
- `bridge/src/board-detail.ts`
- `bridge/src/session-catalog.ts`
- `bridge/src/sessions.ts`
- `android/app/src/main/java/dev/scoutr/app/data/Models.kt`
- `android/app/src/main/java/dev/scoutr/app/data/SessionCatalogStore.kt`
- `android/app/src/main/java/dev/scoutr/app/state/ChatViewModel.kt`
- `android/app/src/main/java/dev/scoutr/app/state/SessionHistoryViewModel.kt`
- `android/app/src/main/java/dev/scoutr/app/MainActivity.kt`
- `docs/decisions.md`
- `skills/scoutr-review/SKILL.md`
- `skills/scoutr-verification/SKILL.md`
