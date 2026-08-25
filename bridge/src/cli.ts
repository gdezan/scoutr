#!/usr/bin/env node
/**
 * scoutr-bridge CLI — development and ops surface.
 *
 *   scoutr-bridge herdr status          → ping + server info
 *   scoutr-bridge herdr snapshot        → print the full session snapshot as JSON
 *   scoutr-bridge herdr watch           → stream live events (Ctrl-C to stop)
 *   scoutr-bridge pair                  → print the QR code the app scans to connect
 *   scoutr-bridge serve                 → run the bridge daemon (layer 2)
 *   scoutr-bridge install-claude-hook   → let Claude report its open questions
 *   scoutr-bridge hook claude           → the hook itself (stdin: Claude's payload)
 */
import { HerdrClient, defaultSocketPath, HerdrError } from "./herdr/client.js";
import { HerdrEventFeed } from "./herdr/feed.js";
// Type-only, so the runtime keeps the lazy imports below.
import type { JsonDeviceRegistry } from "./push/devices.js";
import type { FcmPublisher } from "./push/publisher.js";

async function main(): Promise<void> {
  const [command, subcommand] = process.argv.slice(2);
  const socketPath = process.env.HERDR_SOCKET_PATH ?? defaultSocketPath();

  try {
    switch (command) {
      case "herdr": {
        if (subcommand === "status") {
          const client = new HerdrClient({ socketPath });
          const pong = await client.ping();
          const snapshot = await client.snapshot();
          console.log(
            JSON.stringify(
              {
                socket: socketPath,
                version: pong.version,
                protocol: pong.protocol,
                capabilities: pong.capabilities,
                agents: snapshot.agents.length,
                panes: snapshot.panes.length,
                workspaces: snapshot.workspaces.length,
                focusedPane: snapshot.focused_pane_id,
              },
              null,
              2,
            ),
          );
        } else if (subcommand === "snapshot") {
          const client = new HerdrClient({ socketPath });
          const snapshot = await client.snapshot();
          console.log(JSON.stringify(snapshot, null, 2));
        } else if (subcommand === "watch") {
          const feed = new HerdrEventFeed(socketPath, (message) => {
            console.log(JSON.stringify(message));
          });
          await feed.start();
          console.error(`watching herdr at ${socketPath} — Ctrl-C to stop`);
          await new Promise<void>((resolve) => {
            process.on("SIGINT", () => {
              void feed.stop().then(resolve);
            });
          });
        } else {
          console.error("usage: scoutr-bridge herdr <status|snapshot|watch>");
          process.exitCode = 2;
        }
        break;
      }
      case "hook": {
        // Called by Claude Code with the hook payload on stdin. It must stay
        // silent on stdout (Claude reads it) and always exit 0: a hook that
        // fails or stalls would disrupt the agent it only observes.
        if (subcommand !== "claude") {
          console.error("usage: scoutr-bridge hook claude   (reads the hook JSON on stdin)");
          process.exitCode = 2;
          break;
        }
        const { handleClaudeHook } = await import("./agents/claude/hook.js");
        const chunks: Buffer[] = [];
        for await (const chunk of process.stdin) {
          // SAFETY: process.stdin yields Buffer chunks; the cast preserves the Buffer type.
          const buffer = chunk as Buffer;
          chunks.push(buffer);
        }
        try {
          console.error(handleClaudeHook(Buffer.concat(chunks).toString("utf8")));
        } catch (error) {
          console.error(`scoutr hook: ${error instanceof Error ? error.message : String(error)}`);
        }
        process.exitCode = 0;
        break;
      }
      case "install-claude-hook": {
        const { installClaudeHook } = await import("./agents/claude/hook.js");
        const { path, command, changed } = await installClaudeHook();
        console.log(
          changed
            ? `added ${command} to PreToolUse/PostToolUse in ${path}`
            : `already installed in ${path}`,
        );
        console.log("restart any running claude session for the hook to take effect.");
        break;
      }
      case "pair": {
        const { loadOrCreateConfig } = await import("./config.js");
        const { buildPairingPayload } = await import("./pairing.js");
        const { resolveExposure, ExposureError } = await import("./exposure.js");
        const { default: qr } = await import("qrcode-terminal");

        const config = await loadOrCreateConfig();
        if (!config.fcmServiceAccountPath) {
          console.error("warning: push not configured — set fcmServiceAccountPath in the config to enable it");
        }
        let exposure;
        try {
          exposure = await resolveExposure(config);
        } catch (error) {
          if (!(error instanceof ExposureError)) throw error;
          console.error(`error: ${error.message}`);
          process.exitCode = 1;
          break;
        }
        const payload = buildPairingPayload({ exposure, token: config.token });
        // Host and exposure kind are safe to log; the token never is.
        console.error(`exposure: ${exposure.kind} → ${exposure.publicUrl}`);
        qr.generate(payload, { small: true }, (out: string) => console.log(out));
        console.error("\nScan this QR with the Scoutr app (Connect → Scan QR code).");
        if (exposure.loopbackFallback) {
          console.error("warning: could not detect the public hostname — the QR points at 127.0.0.1.");
          console.error("set exposure.publicUrl in ~/.config/scoutr/config.json (or SCOUTR_PUBLIC_HOST) first.");
        }
        console.error("If scanning fails, type the fields below into the app:");
        console.log(payload);
        break;
      }
      case "serve": {
        const { createScoutrServer } = await import("./server.js");
        const { loadOrCreateConfig, defaultConfigPath } = await import("./config.js");
        const { UsageService } = await import("./usage/providers.js");
        const { HerdrTerminalLauncher } = await import("./terminal/process.js");
        const { createFcmSender } = await import("./push/fcm.js");
        const { JsonDeviceRegistry } = await import("./push/devices.js");
        const { FcmPublisher } = await import("./push/publisher.js");
        const { FileWorkspaceRootStore } = await import("./workspace-roots.js");

        const { pruneStalePendingAsks } = await import("./agents/claude/pending-asks.js");

        const config = await loadOrCreateConfig();
        // A session killed mid-ask leaves its sidecar behind; nothing else
        // would ever clear it, and it would show as a phantom card.
        const stale = pruneStalePendingAsks();
        if (stale > 0) console.error(`cleared ${stale} stale pending ask(s)`);
        const feed = new HerdrEventFeed(socketPath);
        await feed.start();
        console.error(`connected to herdr at ${socketPath}`);

        // Push is optional. Without a readable key file the publisher stays
        // undefined and the bridge behaves exactly as it does today; a broken
        // credential must never keep the bridge from serving.
        let push: { publisher: FcmPublisher; devices: JsonDeviceRegistry } | undefined;
        if (config.fcmServiceAccountPath) {
          try {
            const devices = await JsonDeviceRegistry.open(config.configDir);
            const { makeErrorStopInspector } = await import("./push/stopped-on-error.js");
            push = {
              publisher: new FcmPublisher(
                await createFcmSender(config.fcmServiceAccountPath),
                devices,
                config.hostId,
                // Settle-to-idle events ask this whether the transcript ends in
                // a failed model call; only then does the phone get woken.
                makeErrorStopInspector(feed),
              ),
              devices,
            };
          } catch (error) {
            console.error(`warning: push disabled — ${error instanceof Error ? error.message : String(error)}`);
          }
        }

        const server = createScoutrServer({
          herdr: new HerdrClient({ socketPath }),
          terminal: new HerdrTerminalLauncher({ socketPath }),
          feed,
          usage: new UsageService(),
          config,
          publisher: push?.publisher,
          devices: push?.devices,
          workspaceRoots: new FileWorkspaceRootStore(config.configDir),
        });
        console.error(`scoutr bridge listening on ${server.url}`);
        // Never print the token: the credential would persist in journald.
        console.error(`token: run 'scoutr-bridge pair' or read ${defaultConfigPath()}`);
        console.error(
          push
            ? `push: FCM (${push.devices.list().length} device(s) registered)`
            : "push: disabled (no fcmServiceAccountPath in the config)",
        );
        console.error(
          `exposure: ${config.exposure.kind}${config.exposure.publicUrl ? ` → ${config.exposure.publicUrl}` : ""} ` +
            `(the provider fronts ${server.url} with TLS; run 'scoutr-bridge pair' for the app's QR)`,
        );

        await new Promise<void>((resolve) => {
          let done = false;
          const shutdown = (): void => {
            if (done) return;
            done = true;
            console.error("shutting down…");
            // Give the server a moment to drain, then force-exit so the port
            // is always released (stuck keep-alive sockets would hang it).
            const timer = setTimeout(() => process.exit(0), 2000);
            timer.unref();
            void server.close().then(resolve);
          };
          process.on("SIGINT", shutdown);
          process.on("SIGTERM", shutdown);
        });
        break;
      }
      case "help":
      case "--help":
      case "-h":
      default: {
        console.error(
          "usage: scoutr-bridge <herdr status|herdr snapshot|herdr watch|pair|serve|install-claude-hook|hook claude>",
        );
        process.exitCode = 2;
      }
    }
  } catch (error) {
    if (error instanceof HerdrError) {
      console.error(`herdr error: ${error.message}`);
      process.exitCode = 1;
    } else if (error instanceof Error) {
      console.error(`error: ${error.message}`);
      process.exitCode = 1;
    } else {
      console.error("unknown error", error);
      process.exitCode = 1;
    }
  }
}

void main();
