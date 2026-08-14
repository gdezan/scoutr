# Vendored: Termux terminal-emulator + terminal-view

This directory vendors a minimal, adapted subset of the [Termux](https://github.com/termux/termux-app)
terminal stack for Scoutr's full-screen interactive terminal. The upstream code is Apache-2.0
licensed (see below); all adaptations are documented in this file.

## Upstream source

- Repository: https://github.com/termux/termux-app
- Pinned commit: `3df69d1da197dd9bd71a3bafd902dffd720576b4`
  ("Revert: Add Warp sponsors logo", 2026-07-15)
- Re-vendor with:

  ```sh
  git clone --filter=blob:none --no-checkout https://github.com/termux/termux-app.git /tmp/termux-app
  git -C /tmp/termux-app checkout 3df69d1da197dd9bd71a3bafd902dffd720576b4
  ```

## License and provenance

The termux-app repository root `LICENSE.md` is GPLv3-only **with explicit exceptions**: the
`terminal-emulator` and `terminal-view` modules are the "Terminal Emulator for Android" /
jackpal Android-Terminal-Emulator code, licensed under the Apache License 2.0. This vendor tree
therefore carries the Apache-2.0 text in [`LICENSE`](LICENSE) and a provenance notice in
[`NOTICE`](NOTICE). Upstream files carry no per-file license headers; the module-level license
applies.

- `terminal-emulator/` — `com.termux.terminal` emulator core + tests.
- `terminal-view/` — `com.termux.view` Android view + renderer + text selection.

Both upstream modules publish as `com.termux:terminal-emulator:0.118.0` and
`com.termux:terminal-view:0.118.0`; the vendored code corresponds to that release line.

## Included vs excluded

Included:

- `terminal-emulator/src/main/java/com/termux/terminal/` — all upstream classes **except**
  `JNI.java` (see below). `TerminalSession.java` is adapted (see Adaptations).
- `terminal-emulator/src/test/java/com/termux/terminal/` — all upstream JUnit tests
  (ANSI/Unicode/modes/scrollback), kept byte-identical.
- `terminal-emulator/src/main/AndroidManifest.xml`, `terminal-view/` sources, manifest and
  `res/` — byte-identical to upstream.
- `build.gradle.kts` per module — new (upstream used Groovy DSL plus NDK/native + publishing).

Excluded (local-process/PTY/JNI path, never part of the remote model):

- `terminal-emulator/src/main/java/com/termux/terminal/JNI.java` — native PTY bridge.
- `terminal-emulator/src/main/jni/` — `Android.mk` + `termux.c` native sources.
- Everything else in termux-app: `termux-shared`, `app`, `terminal-view`'s `text-selection`
  dependencies on app code, etc.

## Adaptations (Scoutr)

Exactly two source files are modified from upstream; everything else in `src/` is byte-identical.

### `terminal-emulator/.../TerminalSession.java` — transport-neutral session

Upstream `TerminalSession` spawns a local shell subprocess on a PTY (`JNI.createSubprocess`,
reader/writer/waiter threads, `finishIfRunning()`, `getPid()`, `getCwd()`, byte queues, a
main-thread `Handler`, exit-status reporting). Scoutr's terminal has no local process: bytes come
from and go to a remote transport. The adapted class:

- keeps the name and `extends TerminalOutput`, so `TerminalView`'s contract is unchanged
  (`write`, `writeCodePoint`, `getEmulator`, `updateSize`);
- drops `final` (Scoutr subclasses it as `RemoteTerminalSession` in the app module);
- constructor becomes `TerminalSession(Integer transcriptRows, TerminalSessionClient client)` —
  no shell path/cwd/args/env;
- adds `appendOutput(byte[], int, int)` — remote output in, emulator `append()` + screen notify
  (replaces the process-reader thread);
- adds `setInputCallback(TerminalInputCallback)` with nested `TerminalInputCallback` interface —
  session writes route here instead of to a PTY (replaces the process-writer thread); bytes are
  dropped when no callback is set, mirroring upstream's drop-before-process-start;
- `initializeEmulator(...)` now merely (re)creates the `TerminalEmulator`, which doubles as the
  generation-reset mechanism;
- removes all `android.os`/`android.system` imports, `Handler`, `ByteQueue` fields, `JNI` calls
  and `/proc/<pid>/cwd` access; `updateSize` no longer calls `JNI.setPtyWindowSize`.

### `terminal-view/.../TerminalView.java` — `refreshEmulator()`

Scoutr's `RemoteTerminalSession` replaces its `TerminalEmulator` on every remote stream generation
(pane switch, reconnect, takeover; see `resetForGeneration` above), while upstream Termux never
replaces an emulator inside a live session. The view's private `mEmulator` reference would
otherwise stay stale and repaint the previous generation's content forever. Added one public
method, `refreshEmulator()`, which re-fetches the emulator from the attached session, re-points the
cursor blinker at it, and repaints. The app wires it as the session's `onScreenUpdated` callback
(`TerminalScreen`). All other `TerminalView` behavior is untouched.

`TerminalEmulator` and the rest of `com.termux.terminal` are untouched, so all upstream emulator
tests still pass unmodified.

## Known upstream behavior at this pin

- OSC 52 (clipboard set) is decoded with `android.util.Base64` and delivered via
  `TerminalOutput.onCopyTextToClipboard` → `TerminalSessionClient.onCopyTextToClipboard`.
  Scoutr blocks it in `RemoteTerminalSession` (see app `terminal/` package).
- The pinned emulator has **no** OSC 8 / hyperlink parsing; link handling, when it arrives, must
  be app-side; current terminal ownership and runtime limits are documented in `docs/terminal.md`.
- OSC 0/1/2 → `TerminalSessionClient.onTitleChanged`; BEL → `onBell`; OSC 4/10/11/12/104 →
  `onColorsChanged`.

## Verification

Byte-identity of untouched files (paths relative to this dir):

```sh
UP=/tmp/termux-app
diff -rq $UP/terminal-emulator/src/test android/vendor/termux/terminal-emulator/src/test
diff -rq $UP/terminal-view/src/main android/vendor/termux/terminal-view/src/main
diff -rq --exclude=JNI.java --exclude=TerminalSession.java \
  $UP/terminal-emulator/src/main android/vendor/termux/terminal-emulator/src/main
```

Gradle:

```sh
./gradlew :vendor:termux:terminal-emulator:testDebugUnitTest \
          :vendor:termux:terminal-view:assembleDebug \
          :app:testDebugUnitTest
```
