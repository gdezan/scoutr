# I1 + I4 Image Viewer Blueprint

## Current situation

Verified behavior, anchors, precedent, constraints, and named references.

- **F1–F4 shipped** (`b7a5544`, `83b7eb6`, pushed + deployed, `check:deployed` green). Chat file chips and file-edit **Open file** buttons route to the file-viewer route; the viewer renders markdown / highlighted source / plain text with paging, and shows type-aware triage copy for binaries.
- **Bridge is done for this slice — no bridge changes.** `GET /api/file/bytes?path=` streams raw bytes with `Range` via `sendFile` (`accept-ranges: bytes`), 20 MiB cap → 413, 404/403 triage (`bridge/src/routes/files.ts` → `fileBytes`, `bridge/src/files.ts` → `statWorkspaceFile`, `FILE_BYTES_MAX_BYTES`). Triage fields `sizeBytes`/`mime` ride on `GET /api/file` with no protocol bump; additive feature `file-bytes.v1` (`bridge/src/api-protocol.ts`). `file-bytes.v1` is **not** in the app's `REQUIRED_SCOUTR_API_FEATURES` (`android/.../data/ScoutrApiProtocol.kt`) — the app degrades gracefully on older bridges, no handshake change in this slice.
- **Viewer entry points already land on the viewer route:** browser drill-down (`android/.../ui/nav/FilesGraph.kt` → `fileDestinations` → `FileViewerScreen(viewModel)`) and chat chips/Open-file (`ChatGraph.kt` → `onOpenWorkspaceFile` → `AppRoutes.fileViewer`). The image branch to replace is the `body.binary` triage in `FileViewerBody` (`android/.../ui/screens/FileViewerScreen.kt`): `binaryPreviewTitle` returns `"Image preview is coming"` for `image/*`.
- **Verified gaps:** no image-loading library in `app/build.gradle.kts` (compose-bom, okhttp, textmate, markdown-renderer, zxing, firebase only) or `libs.versions.toml`; no `FileProvider` in `AndroidManifest.xml` (existing `ACTION_VIEW` uses in `NotificationPresenter.kt` are internal deep links, not file handoffs); `ScoutrApi`/`BridgeClient`/`FakeScoutrApi` have **no** raw-bytes method (only `file()` paging + `downloadApk` streaming); browser drill-down dies past depth 6 (`MAX_WALK_DEPTH` in `bridge/src/files.ts`) so acceptance fixtures must live shallow (e.g. `docs/design/assets/`).
- **Precedents to follow:** `BridgeClient.downloadApk` (`android/.../net/BridgeClient.kt`) — authenticated streaming to disk on `Dispatchers.IO` with `Range` resume, 200-restart handling, truncation check, `BridgeException(status, reason)` mapping; `FakeScoutrApi.downloadApk` resume contract (`android/.../commonTest/.../FakeScoutrApi.kt`); viewer `Loadable`/`FailureKind` taxonomy (`android/.../state/Loadable.kt`); existing viewer `testTag`s (`file_viewer_content`, `file_viewer_notice`, `file_viewer_refresh`).
- **Governing references:** `docs/dev-workflow.md` (deploy, scratch-bridge, emulator recipes), `skills/scoutr-verification/SKILL.md` (cheap-checks → review → frozen → acceptance), `skills/scoutr-review/SKILL.md` (pre-commit review), `android/.../ui/theme/DESIGN.md` (always-dark Material 3 contract). Invariant C3: viewer downloads live in the auto-purgeable cache dir, never in persistent storage, never auto-written to gallery.

## Objective and why

Tap any workspace image (PNG/JPG/WebP/GIF) from chat or the file browser and see it rendered full-screen with pinch-zoom, pan, fit on rotate, and animated GIF/WebP playback — then either hand it to a system gallery/editor (**Open with**) or keep a copy in **Downloads** (**Save**). Nothing ever auto-writes to the gallery.

Done = on the emulator against a scratch bridge: PNG opens from browser drill-down and from a chat chip, pinch-zoom/pan works, GIF animates, oversized/missing/forbidden files show triage copy, Open-with fires a `content://` chooser, Save lands the bytes in Downloads, offline/401/403 map to the existing failure taxonomy, and all cheap checks plus `scoutr-review` are clean.

## Scope

**Included (I1 + I4):**

- Coil 3 image pipeline; `image/png`, `image/jpeg`, `image/gif`, `image/webp` route to the viewer (extension-mime comes from the bridge triage table `WORKSPACE_MIME_BY_EXTENSION`).
- Zoomable/pannable full-screen image surface with loading, error/retry, and over-cap triage states.
- Authenticated byte download through a new `ScoutrApi` method following the `downloadApk` pattern, into `cacheDir/images/` with a bounded LRU trim.
- `FileProvider` (`exported=false`, `grantUriPermissions`, cache-paths only) + Open-with chooser (`ACTION_VIEW`, grant-read) with no-handler copy.
- Save to Downloads: MediaStore on API 29+, Storage Access Framework `ACTION_CREATE_DOCUMENT` on API 26–28. **No storage permission is added on any API level.**
- Triage-copy updates where the "coming soon" image lines die, plus JVM unit tests for the new seams.

**Non-goals:** I2 inline chat thumbnails, I3 multi-image pager, I5 SVG handling (SVG stays on its current path — `image/svg+xml` does **not** route to the image viewer; text/SVG files keep rendering as source/text), `ACTION_SEND` share sheet (stays a separate C2 item), gallery auto-write (forbidden), any bridge change, any protocol/ handshake bump, any chat-transcript parsing for image refs.

## Resolved decisions

Settled product and architecture decisions plus consequential rejected alternatives.

- **D1 — Coil 3, Compose-native (settled 2026-09-03, user).** Artifacts `coil-compose` + `coil-gif` (Coil 3 line, `io.coil-kt.coil3`), pinned in `libs.versions.toml`. `AsyncImage` renders from the cached file; `coil-gif` plays GIF everywhere; animated WebP plays via the platform ImageDecoder on API 28+ and shows its first frame statically on 26–27 (accepted fallback, no custom decoder). *Rejected: hand-rolled `BitmapFactory` + subsampling + manual animation lifecycle — re-owns large-image risk and animation for no product gain.*
- **D2 — I4 is open-with + save only (settled 2026-09-03, user).** No `ACTION_SEND`. The share sheet stays out so I4's review surface is one provider, one chooser, one save path.
- **D3 — Auth stays inside `BridgeClient`; Coil never touches the network.** The app pre-downloads `/api/file/bytes` to a cache file (Bearer + Range via the existing `request`/`withBinding` helpers) and hands Coil a `File`. *Rejected: custom Coil Fetcher/OkHttp integration — spreads auth headers into the image pipeline and duplicates the resume contract.*
- **D4 — Save path without permissions.** MediaStore `Download/` collection with the `IS_PENDING` dance on API 29+; SAF `ACTION_CREATE_DOCUMENT` with the workspace filename as the suggested name on 26–28. No `WRITE_EXTERNAL_STORAGE` (or any storage permission) is added. One explicit Save tap is the only write; nothing is written speculatively or on view.
- **D5 — Least-privilege provider.** One `FileProvider`, `exported=false`, `grantUriPermissions=true`, single `<cache-path name="images" path="images/"/>`. Only cache files the viewer itself downloaded are shareable; nothing else in `cacheDir` or `filesDir` is exposed.
- **D6 — Over-cap stays client-triaged.** The 20 MiB bridge cap is authoritative. The viewer checks `sizeBytes` from `/api/file` before downloading and shows too-large copy; a mid-download 413 surfaces the same copy. No cap negotiation, no partial render.
- **D7 — `file-bytes.v1` stays optional.** The app does not add it to `REQUIRED_SCOUTR_API_FEATURES`. A bridge without it fails the download with the bridge's own reason, mapped through `failureKind()` like any other server failure.

## Approach

The accepted implementation shape and short end-to-end data/control flow.

1. **Dependency:** add Coil 3 (`coil-compose`, `coil-gif`) to `libs.versions.toml` + `app/build.gradle.kts`. No other dependency changes.
2. **Transport:** add `ScoutrApi.downloadWorkspaceFile(destination, path, resumeFrom, onProgress): Long` mirroring `downloadApk` against `GET /api/file/bytes?path=`; implement in `BridgeClient` (authenticated, `Dispatchers.IO`, Range resume, 200-restart, truncation guard); mirror the resume contract in `FakeScoutrApi`.
3. **Cache owner:** small `ImageFileCache` helper (key = sha256 of host/profile + absolute path + extension; `trimToMaxBytes` oldest-first). Lives under `cacheDir/images/`.
4. **ViewModel:** extend `FileViewerViewModel` with an image-download state. `refresh()` keeps the existing `file()` triage call untouched, then — only when `binary && mime in {png,jpeg,gif,webp} && sizeBytes within cap` — downloads to the cache file and exposes `Loadable<File>`. SVG never enters this path. Constructor gains a cache-dir `File` (not a `Context`, stays JVM-testable); `FilesGraph.kt` factory passes `context.cacheDir`.
5. **UI:** in `FileViewerBody`, `binary && image/*` (excluding `image/svg+xml`) renders a new `ImageViewer` composable (Coil `AsyncImage` + `rememberTransformableState` zoom/pan + double-tap toggle + header actions **Open with** / **Save**); all other binaries keep today's triage. The dead `"Image preview is coming"` title/detail lines are removed and replaced by real viewer states (loading / failed-retry / too-large).
6. **System handoff:** `AndroidManifest.xml` provider + `res/xml/file_viewer_paths.xml`; `ACTION_VIEW` chooser with grant-read and no-handler copy; MediaStore/SAF save with success/failure user-visible copy.
7. **Tests + review + acceptance** per Validation. `blast-radius` (FileProvider exposure + content-URI grant + cache lifecycle) is a retained due gate: run it against the diff before code-freeze; implementation does not start its final acceptance until it is clear.

End-to-end flow: tap chip/file → `fileViewer` route → `FileViewerViewModel.refresh()` → `GET /api/file` triage → (image, in-cap) `GET /api/file/bytes` → `cacheDir/images/<key>` → Coil renders → Open-with grants `content://` read to the chosen app, or Save copies to Downloads. 404/403/413/offline/401 surface as triage or `Loadable.Failed` with retry, never a blank screen.

## Contracts and interfaces

Load-bearing types, states, invariants, schemas, lifecycle rules, signatures, and cross-change boundaries.

- **New transport contract** (produced by Change 2, consumed by Changes 3–4):
  ```kotlin
  suspend fun downloadWorkspaceFile(
    destination: File,
    path: String,          // absolute workspace path, sent as ?path=
    resumeFrom: Long = 0,  // bytes already staged; Range: bytes=<resumeFrom>-
    onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
  ): Long                  // bytes now in destination; mirrors downloadApk resume/restart/truncation rules
  ```
  Rules inherited from `downloadApk`: Bearer via `withBinding`; `206` appends, `200`-to-a-Range restarts from zero (truncate); `total>0 && staged!=total` → `BridgeException(code, truncated…)`; cancellation cancels the OkHttp call; non-2xx → `BridgeException(status, bridgeReason)`. `?path=` is URL-encoded through the existing `request()` query map (never string-concatenated).
- **Bridge wire (unchanged, consumed):** `GET /api/file/bytes?path=` → `200` file stream (`Content-Type` = bridge mime, `accept-ranges: bytes`) or `400/403/404/413` JSON `{ok:false,error}`. 20 MiB cap. No new fields, no feature requirement.
- **Image-eligibility predicate** (single owner: ViewModel, mirrored in UI branch): `body.binary && body.mime in {image/png, image/jpeg, image/gif, image/webp} && (sizeBytes == null || sizeBytes <= FILE_CAP)` where the cap check reuses the bridge's 413 as truth (client pre-check is triage, not enforcement). `image/svg+xml` is explicitly excluded.
- **Cache contract:** key `sha256(profileOrHostId + "|" + absolutePath) + extension(filename)`; dir `cacheDir/images/`; bounded with oldest-first trim (default cap 100 MB — exact bytes are local discretion); Android may evict `cacheDir` at any time (C3) so a missing cache file re-downloads transparently. Only files this viewer downloaded are passed to the provider.
- **Provider contract:** authorities `"${applicationId}.fileprovider"`, `exported=false`, `grantUriPermissions=true`, one `<cache-path name="images" path="images/"/>`. Share intent: `ACTION_VIEW`, `setDataAndType(uri, mime)`, `FLAG_GRANT_READ_URI_PERMISSION`, wrapped in `Intent.createChooser`; `ActivityNotFoundException` → "No app can open this image" copy.
- **Save contract:** API 29+ MediaStore `Download/` (`DISPLAY_NAME` = workspace filename, `MIME_TYPE` = triage mime, `IS_PENDING` 1→0, `RELATIVE_PATH` `Download/`); API 26–28 SAF `ACTION_CREATE_DOCUMENT` (type = mime, `EXTRA_TITLE` = filename, copy `content://` bytes on result). No storage permission. Success/failure copy is user-visible; failure never deletes the cache file.
- **State ownership:** `FileViewerViewModel` owns triage (`content: Loadable<FileReadResponse>`, unchanged) plus `imageFile: Loadable<File>` (new; `Idle` until triage proves image-eligible). `refresh()` cancels in-flight work via `viewModelScope` and resets both. Coil owns zoom/pan transform state (screen-local, not in the ViewModel). Text path (`readAllPages`, `MAX_PAGE_BYTES`, `MAX_DISPLAY_BYTES`) is untouched.
- **Failure taxonomy (reuse, no new kinds):** `failureKind()` maps 401→`Unauthorized`, 403→`Rejected`, IO→`Offline`, else `Server`. 404 → existing "File is unavailable" copy; 413/over-cap → new too-large copy with formatted size (`formatViewerBytes`); no-handler open → copy above; save failure → copy with retry affordance.
- **Cross-change boundary table:**

  | Producer → Consumer | Contract |
  |---|---|
  | Change 2 → Changes 4, 5 | `downloadWorkspaceFile` signature + resume/restart/truncation rules |
  | Change 3 → Changes 4, 6 | cache-file path for an absolute workspace path; trim guarantee |
  | Change 4 → Change 5 | `imageFile: Loadable<File>` + eligibility predicate |
  | Change 6 → Change 5 | provider authorities + `uriFor(cacheFile)` helper |
  | Change 5 → Change 7 | `testTag`s + copy strings under test |

## Changes

- [x] **1. Add Coil 3 dependencies**
  - Anchor: `android/gradle/libs.versions.toml` → `[versions]`/`[libraries]`; `android/app/build.gradle.kts` → `dependencies` (precedent: neighboring `implementation(libs.…)` lines).
  - Work: new `coil3` version (latest stable 3.x at implementation time) + `coil3-compose` (`io.coil-kt.coil3:coil-compose`) and `coil3-gif` (`io.coil-kt.coil3:coil-gif`) libraries; two `implementation(...)` lines. No version-catalog or plugin changes. Proguard: none (Coil ships its rules; release `isMinifyEnabled` stays as-is — if R8 warns, add the narrow `-keep` the warning names, nothing speculative).
  - Proof: `cd android && ./gradlew :app:assembleDebug` greens; no other module touched.
- [x] **2. Authenticated workspace-byte download on `ScoutrApi`**
  - Anchor: `android/.../net/ScoutrApi.kt` → interface; `android/.../net/BridgeClient.kt` → `downloadApk` (pattern to mirror); `android/.../commonTest/.../FakeScoutrApi.kt` → `downloadApk` fake.
  - Work: add `downloadWorkspaceFile` per the Contracts signature; `BridgeClient` hits `/api/file/bytes` with `?path=` through `request()`/`withBinding`, `Dispatchers.IO` blocking `execute` + 64 KiB copy loop, Range resume, 200-restart truncation, truncation guard, `bridgeReason` mapping, coroutine-cancel cancels the call. `FakeScoutrApi` mirrors the resume contract (tail-append when `resumeFrom in 1..total`, else full write) with `downloadWorkspaceFileFailure` + byte fixture. No handshake/protocol change.
  - Proof: new JVM tests pin resume/restart/query-encoding/cancellation against `MockWebServer` (precedent: `BridgeClientUploadTest` style); `FakeScoutrApi` test pins the tail-append rule.
- [x] **3. Bounded image cache helper**
  - Anchor: new `android/.../state/ImageFileCache.kt` (owner: viewer state layer); consumers: `FileViewerViewModel`, provider helper.
  - Work: `cacheFileFor(hostKey, absolutePath, filename): File` (sha256 key + original extension, under `cacheDir/images/`), `trimToMaxBytes(maxBytes: Long)` oldest-first by `lastModified`, called after each completed download. Missing/evicted files are a normal miss (re-download). Pure `java.io` + `MessageDigest` — JVM-testable, no Context.
  - Proof: JVM unit tests — stable key per host+path, distinct hosts/paths don't collide, extension preserved, trim deletes oldest first and keeps total under cap.
- [x] **4. ViewModel image-download state**
  - Anchor: `android/.../state/FileViewerViewModel.kt` → `FileViewerUiState`/`refresh()`; `android/.../ui/nav/FilesGraph.kt` → viewer factory; precedent: `FileBrowserViewModelTest`/`FileViewerViewModelTest` (`android/.../test/.../state/`).
  - Anchor: `android/.../state/FileViewerViewModel.kt` → `FileViewerUiState`/`refresh()`; `android/.../ui/nav/FilesGraph.kt` → viewer factory; precedent: `FileBrowserViewModelTest`/`FileViewerViewModelTest` (`android/.../test/.../state/`). Extend `FileViewerViewModel` (`imageCacheDir: File`, `hostKey: String` ctor args) with `imageFile: Loadable<File>`; add `ImageFileCache(cacheDir)` (`cacheFileFor`, 100MB `trimToMaxBytes`, `IMAGE_MIMES`, `isImagePreviewable`) and route state owns `viewerImageCacheDir = File(appContext.cacheDir, "images")` (factory passes `container.viewerImageCacheDir`, `profile.encode()`). `refresh()`: existing `file()` triage first (unchanged, including `readAllPages` text path); only when the eligibility predicate holds, set `imageFile=Loading` and `downloadWorkspaceFile` into the cache file — resume only a strictly partial prefix of the triaged size (a complete or unknown-size file restarts from zero, and the triage size versions the cache key, so an overwritten image can never mix a stale prefix with a fresh tail) — then `Ready(file)`; 413/over-cap → `Failed` carrying the too-large reason; any other throw → `Failed(reason, error.failureKind())`. Non-image binaries leave `imageFile=Idle` (UI keeps triage). Cancellation via `viewModelScope` job (abandoned partials stay for resume). Top-level `tooLargeReason`/`formatViewerBytes` shared by UI.
  - Proof: `FileViewerViewModelTest`-style JVM tests with `FakeScoutrApi` — image triage triggers one download and `Ready`; text triage never calls it; 413 maps to too-large `Failed`; 403 maps to `Rejected`; refresh-while-loading supersedes (no stale `Ready`).
- [x] **5. Image viewer UI with zoom/pan + Open-with/Save actions**
  - Anchor: `android/.../ui/screens/FileViewerScreen.kt` → `FileViewerBody` binary branch + `binaryPreviewTitle/Detail` + `FileViewerHeader`; theme tokens per `ui/theme/DESIGN.md`.
  - Work: when `binary && mime in {png,jpeg,gif,webp}` render new `ImageViewer(imageState, mime, sizeBytes, onOpenWith, onSave, onRetry)` instead of `ViewerMessage`: Coil `AsyncImage(model = cacheFile)` with crossfade, `rememberTransformableState` pinch-zoom (1×–8×) + pan with `graphicsLayer`, double-tap toggles fit/2×, rotate re-fits (initial fit on size change — that is the "rotate-to-fit" behavior); states: `Loading` → "Loading image…", `Failed` → reason + Retry, over-cap → too-large copy with `formatViewerBytes`. Header gains **Open with** and **Save** icon/text actions (`testTag`s `file_viewer_open_with`, `file_viewer_save`; image surface `file_viewer_image`). Remove the dead `"Image preview is coming"` title and the image line of `binaryPreviewDetail` (HTML/PDF triage lines stay for their slices). Always-dark M3, `ScoutrSpace`/`ScoutrType` tokens, multiline-safe (no text input here). Animation: Coil plays GIF/WebP; no autoplay code of our own (I2 constraint noted, not built).
  - Proof: JVM/Robolectric test for triage-branch selection (no instrumentation in the inner loop); the final emulator drive (Validation) proves real pixels, zoom, and animation.
- [x] **6. FileProvider + open-with handoff**
  - Anchor: `android/app/src/main/AndroidManifest.xml` → `<application>` (precedent: unexported receivers/services); new `android/app/src/main/res/xml/file_viewer_paths.xml`; opener helper next to the viewer (e.g. `ImageShare.kt`, local discretion on filename).
  - Work: `<provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.fileprovider" android:exported="false" android:grantUriPermissions="true">` + `FILE_PROVIDER_PATHS` meta-data; paths file exposes only `<cache-path name="images" path="images/"/>`. `uriFor(cacheFile)` refuses files outside `cacheDir/images/` (throws, never falls back to `file://`). Open-with: `ACTION_VIEW` + `setDataAndType(uri, mime ?: "image/*")` + `FLAG_GRANT_READ_URI_PERMISSION` in a chooser; `ActivityNotFoundException` → "No app can open this image" copy. `mime` comes from triage; unknown → `image/*`.
  - Proof: Robolectric/JVM test that `uriFor` refuses escapes + emits the configured authority; emulator acceptance shows the system chooser for a PNG (screenshot). `blast-radius` reviews this diff before freeze (exported-component + grant implications).
- [x] **7. Save to Downloads (MediaStore Q+ / SAF pre-Q, no permission)**
  - Anchor: saver helper beside Change 6 (same owner); `AndroidManifest.xml` gains **no** permission (assert in review).
  - Work: API 29+: `MediaStore.Downloads` insert (`DISPLAY_NAME`, `MIME_TYPE`, `RELATIVE_PATH=Download/`, `IS_PENDING` 1→write→0), failures delete the pending row; API 26–28: `ACTION_CREATE_DOCUMENT` (`CATEGORY_OPENABLE`, type, `EXTRA_TITLE`) and stream the cache file on result. Success → "Saved to Downloads" (+ filename); failure → reason + retry; the cache file is never deleted by save. Save is always an explicit tap.
  - Proof: JVM tests for filename/mime mapping + pending-row cleanup logic where separable; emulator acceptance saves a PNG and reads it back (`MediaStore` query on Q+, SAF stub pre-Q is best-effort — Q+ path is the acceptance gate; minSdk path covered by unit test + code inspection).

## Failure handling

Invalid inputs, partial failures, cleanup, cancellation, concurrency, recovery, observability, and user-visible errors that apply to this work.

- **Triage first, download second:** blank `cwd`/`file` → existing "File path is not available"; `!exists` → "File is unavailable"; `!binary` → text path untouched. Image download never starts without image-eligible triage.
- **Status mapping:** 404 → unavailable copy; 403 → `Rejected` ("outside an active agent workspace" family — surface the bridge reason, never probe existence); 401 → `Unauthorized` (re-pair affordance per existing taxonomy); 413 or `sizeBytes` over cap → too-large copy with formatted size; 5xx/parse → `Server` + Retry. `failureKind()` is the single mapper — no ad-hoc splits.
- **Partial/resumed downloads:** 206 appends; 200-to-Range truncates and restarts; staged≠total → truncation `BridgeException` + Retry (stale prefix is overwritten on next attempt, never rendered). `ensureActive` in the copy loop; scope cancellation abandons the file (next refresh resumes or restarts by the same rules).
- **Cache pressure:** evicted/missing cache → transparent re-download; trim failures are best-effort and never fail the view. Save/open never write into the cache (read-only consumers).
- **Provider safety:** `uriFor` throws on any file outside `cacheDir/images/`; no `file://` fallback; grant is read-only, one-intent, chooser-wrapped. No persisted URI permissions.
- **Save safety:** MediaStore pending-row cleanup on any failure; SAF cancel is silent success-path abort (no error copy); save never touches the gallery (`Download/`, never `Pictures/`).
- **Large-image memory:** Coil subsampling + its memory cache own this; no `android:largeHeap`, no manual bitmap handling. Over-cap files never reach the decoder.
- **SVG exclusion:** `image/svg+xml` falls through to the existing binary triage (its future is I5); if the bridge ever serves SVG as text, the text path renders it as source — either way it never hits the image decoder.
- **Observability:** reuse `BridgeException(status, reason)` messages and existing `ViewerFailure` copy patterns; no new logging surface except Coil's own error placeholder (local discretion).

## Validation

Focused checks during implementation and the repository-appropriate final acceptance evidence.

- **During implementation (cheap only, serial Gradle):** `:app:assembleDebug` after Change 1; focused JVM suites after each change (`FileViewerViewModelTest`, new `ImageFileCacheTest`, new `BridgeClientFileBytesTest`, triage-copy tests) — `cd android && ./gradlew testDebugUnitTest --tests '…'` one class at a time; `cd bridge && npm run typecheck` only if a bridge file was touched by accident (none planned — if touched, stop and escalate). No emulator, no instrumentation, no `scripts/verify.sh` in the inner loop.
- **Review gate:** `scoutr-review` per `skills/scoutr-review/SKILL.md`; resolve or dismiss every finding; re-run only invalidated cheap checks. `blast-radius` runs against the manifest/provider/URI/cache diff before code-freeze (retained due gate from routing) — freeze only when both are clean.
- **Final acceptance (terminal, after freeze, emulator-5554 on-demand per `docs/dev-workflow.md`):** scratch bridge (`XDG_CONFIG_HOME=/tmp/scoutr-scratch`, `SCOUTR_REPO_ROOTS` including a shallow image fixture dir — fixtures must sit within depth 6, e.g. under `docs/design/assets/` copies), app repointed at `http://10.0.2.2:<port>`; prove: (a) browser drill-down opens PNG + byte-identical render, pinch-zoom/pan responds, rotate re-fits; (b) chat chip opens the same image; (c) GIF animates (two screencaps differ) while static PNG does not; (d) missing/403/413 fixtures show triage copy; (e) Open-with raises the system chooser (`uiautomator` dump); (f) Save produces a Downloads entry with the right name/mime; (g) airplane-mode/401 maps to Offline/Unauthorized copy with Retry. `adb -s emulator-5554 exec-out screencap -p` evidence, inspected directly (or via `scoutr-vision` if the model lacks vision). Kill the emulator when done (`adb -s emulator-5554 emu kill`).
- **Not run:** full `scripts/verify.sh` / managed-device suite (unjustified for this slice unless final acceptance exposes systemic risk — then return to review, fix cheap, re-freeze, re-accept).

## Local discretion

Routine choices that cannot change the settled behavior, contracts, ownership, or compatibility.

- Exact Coil 3 patch version (latest stable 3.x at build time), crossfade duration, zoom bounds within 1×–8×, double-tap step, placeholder/error drawable styling (must stay always-dark M3).
- Helper file names (`ImageFileCache`, opener/saver helper) and whether open/save live in the header or a bottom action row (must keep the three `testTag`s and DESIGN.md tokens).
- Exact copy wording beyond the locked strings ("Saved to Downloads", "No app can open this image", too-large/unavailable families) — keep it terse and consistent with existing triage voice.
- Trim cap bytes (default 100 MB) and progress-UI granularity (progress bar vs indeterminate — download is fast for in-cap images; indeterminate is acceptable).
- Which Robolectric vs plain-JVM harness each unit test uses (follow the neighboring test file's harness).

## Escalation triggers

Facts or repeated implementation friction that would invalidate the settled plan and must return to `gd`.

- Coil 3's actual artifacts/behavior at build time differ materially (no `coil-gif`, WebP animation story changed, minSdk 26 conflict, compose-bom clash) — stop, don't hand-roll around it silently.
- `FileProvider`/MediaStore/SAF behavior contradicts the contracts (authority clash, `cache-path` insufficient on targetSdk 36, MediaStore columns changed) or review demands a storage permission — permission additions need an explicit user decision.
- Bridge reality differs (cap ≠ 20 MiB, `Range` unsupported on `file-bytes`, mime table changed, `file-bytes.v1` withdrawn) — the plan assumes today's `files.ts`/`send-file.ts` behavior.
- `MAX_WALK_DEPTH` or containment blocks the acceptance fixtures in a way shallow placement can't fix — don't widen bridge traversal inside this slice.
- Any demand for `ACTION_SEND`, chat thumbnails, pager, or SVG rendering inside this slice — those are C2/I2/I3/I5, cut back to scope.
- Three failed causal fixes in a row on one symptom — reset the causal model via `diagnosing-bugs`/`architecture-analysis`/`research` per routing rules instead of retrying.

## Completion checklist

A short finite checklist for implementation, proof, cleanup, and handoff to the normal review tail.

- [ ] Changes 1–7 implemented in order, each with its focused proof green.
- [ ] `blast-radius` (provider + URI grant + cache) and `scoutr-review` clean; code frozen.
- [ ] Final emulator acceptance (a)–(g) evidenced with screenshots/dumps; emulator killed.
- [ ] No bridge files touched; no `REQUIRED_SCOUTR_API_FEATURES` change; no storage permission; `.plans/` untracked-only (this file is not committed); working tree otherwise clean.
- [ ] Commit on `main` + `git push --follow-tags`, then `npm run deploy` + `check:deployed` green per `docs/dev-workflow.md` (live-path push).

## References

Repository paths and URLs for source artifacts.

- `android/app/src/main/java/dev/scoutr/app/ui/screens/FileViewerScreen.kt` (binary branch, triage copy, header)
- `android/app/src/main/java/dev/scoutr/app/state/FileViewerViewModel.kt` (triage + paging; image state goes here)
- `android/app/src/main/java/dev/scoutr/app/net/ScoutrApi.kt`, `android/app/src/main/java/dev/scoutr/app/net/BridgeClient.kt` (`downloadApk` pattern), `android/app/src/commonTest/kotlin/dev/scoutr/app/net/FakeScoutrApi.kt`
- `android/app/src/main/java/dev/scoutr/app/state/WorkspacePaths.kt` (chip/Open-file refs), `android/app/src/main/java/dev/scoutr/app/state/Loadable.kt` (taxonomy)
- `android/app/src/main/java/dev/scoutr/app/ui/nav/FilesGraph.kt`, `ChatGraph.kt`, `AppRoutes.kt` (viewer routing)
- `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/res/xml/` (provider + paths go here)
- `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts` (Coil goes here)
- `bridge/src/files.ts`, `bridge/src/routes/files.ts`, `bridge/src/send-file.ts`, `bridge/src/api-protocol.ts` (unchanged wire)
- `bridge/test/files.test.ts`, `bridge/test/server.test.ts` (byte-route proof, already green)
- `android/.../test/.../state/FileViewerViewModelTest.kt`, `WorkspacePathsTest.kt` (test precedent)
- `docs/dev-workflow.md`, `skills/scoutr-verification/SKILL.md`, `skills/scoutr-review/SKILL.md`, `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md`
- Feature source: session discussion 2026-09-03 (F1–F4, I1–I5); handoff `/tmp/scoutr-i1-i4-handoff.md`; settled answers 2026-09-03 (Coil / open-with+save-only / blueprint-first).

---

Route state (gd):

```text
route: blueprint
status: settled | working-change: blueprint written, implementation not started
evidence: FileViewerScreen binary branch + triage lines; no Coil in build.gradle.kts/libs.versions.toml; no FileProvider in manifest; ScoutrApi/BridgeClient have file()+downloadApk but no file-bytes method; bridge fileBytes Range + 20MiB cap + file-bytes.v1 optional; MAX_WALK_DEPTH 6; git log b7a5544/83b7eb6 + clean tree except .plans/
result: D1 Coil3 / D2 open-with+save-only, no ACTION_SEND / D3 auth-in-BridgeClient / D4 MediaStore Q+ + SAF pre-Q, no permission / D5 least-privilege provider / D6 client-triaged cap / D7 file-bytes stays optional
proof: read anchors (no fresh commands; cheap checks + acceptance belong to implementation)
due: blast-radius (FileProvider exported/grant exposure + content-URI grant + cache lifecycle) — pending, clears against the diff before code-freeze
execution: parent
unresolved: none blocking the blueprint; Coil patch version + trim bytes + header/action-row layout are local discretion
next: stop (blueprint handoff) — implementer runs Changes 1-7, then stabilize tail
```

Implementation record (2026-09-03, Changes 1-7 done):
- Coil pinned to 3.2.0 (3.6.1 needs compileSdk 37/AGP 9; 3.5.0 needs kotlin-stdlib 2.4.0 vs compiler 2.2.0).
- Change 4 refinement: cache key versions content by triage sizeBytes; resume only a strictly
  partial prefix (complete/unknown-size restarts from zero). Residual: same-size overwrite between
  an interrupted download and its resume can mix versions (no bridge validator); next refresh
  self-heals. Review finding B1 adjudicated as decision-conflict with planned resume.
- SVG arrives from the bridge as text (valid UTF-8; binary sniff is NUL-based) but the viewer
  keeps image/svg+xml in binary triage ('Binary file') per this plan's letter.
- Review fixes: Open-with pre-checks PackageManager (chooser always resolves; CATEGORY_DEFAULT
  added) with manifest <queries> for image VIEW; download-time 404 maps to 'File is unavailable';
  IS_PENDING update==0 throws and cleans the pending row.
- Unit: 668/668 green incl. FileViewerViewModelTest 15, ImageFileCacheTest 5,
  FileViewerScreenTest 4, ImageShareTest 6, BridgeClientFileBytesTest 5.
- Emulator acceptance (cockpit, SDK 36, scratch bridge + fixture agent in /tmp/imgview-fixture):
  PNG/GIF/JPG render full-bleed (vision-witnessed), WebP ImageView full-bleed, double-tap zoom
  engages, rotation letterboxes (status-bar overlap is pre-existing app-wide, also on text
  viewer), Open-with opens Photos which renders the grant, Save lands byte-identical in
  Downloads, over-cap shows exact too-large copy, SVG/.bin show Binary file, PDF shows handoff,
  header Refresh re-downloads and converges. No bridge changes; no new permissions.
