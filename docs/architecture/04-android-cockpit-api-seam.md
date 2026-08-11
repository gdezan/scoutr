# 4. A `CockpitApi` seam on Android

**Strength: Strong.** Independent of plans 1–3; the highest-leverage Android change.

## Files

`android/…/net/BridgeClient.kt` (375 lines), all 8 ViewModels in `android/…/state/`,
`android/…/CockpitApp.kt`, `android/…/service/NotificationReplyReceiver.kt`,
`android/app/src/test/`, `android/app/src/androidTest/`.

## Problem

`BridgeClient` is a plain (therefore `final`) Kotlin class with no interface. AGENTS.md:30
documents the consequence as a rule to work around:

> `BridgeClient` is `final` and cannot be stubbed. Emulator tests use a real BridgeClient +
> a fresh **unsaved** ConnectionStore so ViewModels never start polling.

The project has no mockk and no mockito (`app/build.gradle.kts:82-92`), so there is nothing
to stub even if the class were open. Every behavioural test therefore runs a real HTTP
server:

- 8 of 23 unit-test files spin up `MockWebServer`, each rolling its own dispatcher or
  `stubEndpoints()` helper. Every assertion round-trips through JSON.
- `BoardViewModelTest.kt:36-45` has to clear the `ConnectionStore` *before* constructing the
  subject so `init` does not start polling, then `save()` afterwards — a dance the comment
  explains, which exists purely because there is no seam.
- **The WebSocket path has no test at all.** `sendCommand` (`BridgeClient.kt:322`) and its
  wrappers `steer` / `runSlashCommand` / `answerQuestion` (`:367-374`) would need
  `MockWebServer` with a WS upgrade handler; no test file does one. So `ChatViewModel.send`
  (`:392`), `deliver` (`:499`), `answerQuestion` (`:448`), and `runSlashCommand` (`:485`) are
  covered on the optimistic-UI side only, never on the wire side.
- Auth-header construction is duplicated three times (`request()` `:83-99`, `post()` `:111`,
  `uploadAttachment()` `:170`). The single `net/` unit test exists specifically because a
  literal `"Bearer ${'$'}{token()}"` once shipped from the third copy.

Note what is *not* the problem: `BridgeClient` is not too big. 14 endpoint methods of 2–6
lines each over two generic primitives is a fine façade, and adding an endpoint is genuinely
three lines. The missing piece is a seam, not a decomposition.

## Solution

Declare the interface the ViewModels consume, implement it once over OkHttp, and write one
fake.

```kotlin
// net/CockpitApi.kt
interface CockpitApi {
    suspend fun health(): HealthResponse
    suspend fun agents(): AgentsResponse
    suspend fun session(path: String, since: String?): SessionReadResponse
    suspend fun sessionCatalog(): SessionCatalogResponse
    suspend fun sessionCatalogAction(action: String, path: String, name: String?): CatalogActionResponse
    suspend fun createSession(request: CreateSessionRequest): CreateSessionResponse
    suspend fun controlSession(paneId: String, action: String, text: String?): ControlResponse
    suspend fun models(agent: String?): ModelsResponse
    suspend fun commands(cwd: String?): CommandsResponse
    suspend fun liveOutput(paneId: String): LiveOutputResponse
    suspend fun dirs(path: String?): DirsResponse
    suspend fun repoOverview(root: String): RepoOverviewResponse
    suspend fun repoDiff(root: String, ref: String): RepoDiffResponse
    suspend fun repoArtifacts(root: String): RepoArtifactsResponse
    suspend fun usage(): UsageResponse
    suspend fun uploadAttachment(bytes: ByteArray, name: String, mime: String): AttachmentResponse
    suspend fun sendCommand(command: Map<String, String>): Boolean
}
```

`BridgeClient : CockpitApi` — the existing class, unchanged apart from the declaration and
`override` keywords. Its two public generic primitives (`call`, `post`) become `private`;
nothing outside `net/` should be constructing paths, and today nothing prevents it.

Then one fake in `src/test` (and shared with `androidTest` via a common source set):

```kotlin
class FakeCockpitApi : CockpitApi {
    val calls = mutableListOf<Call>()                 // recorded invocations
    var agentsResult: Result<AgentsResponse> = Result.success(AgentsResponse())
    // …one settable result per endpoint, defaulting to an empty success
    val sentCommands = mutableListOf<Map<String, String>>()   // the WS path, finally observable
}
```

Two supporting cleanups that the interface makes natural:

- **Auth in one place.** Fold `uploadAttachment`'s hand-rolled request into `request()` by
  letting it take a `RequestBody`. That deletes the third copy of the Bearer header and the
  one-off test that guards it.
- **A typed error.** `bridgeFailure()` (`:65-70`) already parses `{"ok":false,"error":…}`
  and throws `IOException("bridge 403: …")`. Give it a `BridgeException(status, reason)` so
  ViewModels can distinguish 401 (re-pair) from 403 (not allowed) from 5xx (retry) instead
  of the current `e.message ?: "…"` string in ~20 places. Plan 6 consumes this.

## Benefits

**Locality.** A ViewModel test states what the bridge returned and asserts what the VM did.
Today that intent is spread across a dispatcher, a JSON fixture, a SharedPreferences dance,
and a real socket.

**Depth.** `CockpitApi` is a wide-but-flat interface, which is fine — its depth comes from
`BridgeClient` hiding auth, host resolution from `ConnectionStore`, JSON codecs, the
`{ok:false}` error convention, WS lifecycle, and multipart upload behind it.

**Tests.** The WS path becomes testable for the first time. Poll behaviour becomes testable
with `kotlinx-coroutines-test` virtual time (already a dependency, currently unusable
because the loops hit a real `viewModelScope` against a real server). The 8 duplicated
`MockWebServer` dispatchers collapse to one fake. Instrumented tests that today construct a
real `BridgeClient` + `OkHttpClient` (`NewSessionSheetTest.kt:72`, `ChatControlsTest.kt:80`)
can take the fake instead, cutting emulator runtime.

**Leverage.** Plan 1 changes wire shapes (`agentKind`, `capabilities`). With a fake, the
Android side of that change is testable before the bridge side ships.

**AGENTS.md:30 gets deleted.** A documented workaround for a structural gap is exactly the
kind of note that should disappear when the gap closes.

## Steps

1. Write `net/CockpitApi.kt` from `BridgeClient`'s current public surface verbatim; add
   `: CockpitApi` and `override`. No behaviour change, suite stays green.
2. Change every ViewModel constructor and `factory()` from `BridgeClient` to `CockpitApi`.
   `AppContainer` (`CockpitApp.kt:33-53`) keeps building the real one.
3. Fix `NotificationReplyReceiver.kt:38`, which constructs its own `BridgeClient` outside the
   container — it should take the container's instance.
4. Write `FakeCockpitApi` in a source set shared by `test` and `androidTest`.
5. Migrate the 8 `MockWebServer` unit-test files to the fake, deleting their dispatchers.
   Keep **one** `MockWebServer` test (`BridgeClientUploadTest`) as the contract test for the
   real client — the fake must not become the only thing tested.
6. Add the first WS tests: `steer` sends `{type:"steer", …}`; `answerQuestion` sends text;
   a send failure surfaces to the UI state.
7. Make `call`/`post` private; fold `uploadAttachment` into `request()`; add
   `BridgeException`.
8. Convert poll-loop tests to virtual time.
9. Delete the AGENTS.md gotcha and replace it with a line pointing at `FakeCockpitApi`.

## Risks

- **The fake drifting from the real client.** Mitigated by keeping a real-transport contract
  test per non-trivial encoding (upload, WS handshake, auth header) and by the fake being a
  plain data holder with no logic of its own.
- **Interface churn during plan 1.** `models(agent: String?)` above already anticipates
  plan 1's parameter. Land the seam first, then change the shape once.
- **Do not add Hilt.** `docs/decisions.md` records manual DI via `AppContainer` as settled.
  An interface plus constructor injection is fully compatible with that; a DI framework is
  not what this plan asks for.
