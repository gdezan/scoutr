#!/usr/bin/env node
/**
 * scoutr-bridge CLI — development and ops surface.
 *
 *   scoutr-bridge herdr status          → ping + server info
 *   scoutr-bridge herdr snapshot        → print the full session snapshot as JSON
 *   scoutr-bridge herdr watch           → stream live events (Ctrl-C to stop)
 *   scoutr-bridge pair                  → print the QR code the app scans to connect
 *   scoutr-bridge serve                 → run the bridge daemon (layer 2)
 */
import { HerdrClient, defaultSocketPath, HerdrError } from "./herdr/client.js";
import { HerdrEventFeed } from "./herdr/feed.js";

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
      case "pair": {
        const { loadOrCreateConfig } = await import("./config.js");
        const { buildPairingPayload } = await import("./pairing.js");
        const { execFile } = await import("node:child_process");
        const { default: qr } = await import("qrcode-terminal");

        const config = await loadOrCreateConfig();
        if (!config.ntfyUrl || !config.ntfyTopic) {
          console.error("warning: ntfy not configured — push will not work until it is");
        }
        // Public host resolution order: config.publicHost > SCOUTR_PUBLIC_HOST
        // > the tailnet MagicDNS name (tailscale status) > loopback fallback.
        let host = config.publicHost ?? process.env.SCOUTR_PUBLIC_HOST;
        if (!host) {
          host = await new Promise<string>((resolve) => {
            execFile("tailscale", ["status", "--json"], { timeout: 5000 }, (err, stdout) => {
              if (err) return resolve("");
              try {
                const dns = (JSON.parse(stdout) as { Self?: { DNSName?: string } }).Self?.DNSName;
                resolve(dns ? dns.replace(/\.$/, "") : "");
              } catch {
                resolve("");
              }
            });
          });
        }
        const payload = buildPairingPayload({
          host: host || `http://127.0.0.1:${config.port}`,
          token: config.token,
          ntfyUrl: config.ntfyUrl,
          ntfyTopic: config.ntfyTopic,
        });
        qr.generate(payload, { small: true }, (out: string) => console.log(out));
        console.error("\nScan this QR with the Scoutr app (Connect → Scan QR code).");
        if (!host) {
          console.error("warning: could not detect the tailnet hostname — the QR points at 127.0.0.1.");
          console.error("set publicHost in ~/.config/scoutr/config.json (or SCOUTR_PUBLIC_HOST) first.");
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
        const { NtfyPublisher } = await import("./notify.js");

        const config = await loadOrCreateConfig();
        const feed = new HerdrEventFeed(socketPath);
        await feed.start();
        console.error(`connected to herdr at ${socketPath}`);

        const server = createScoutrServer({
          herdr: new HerdrClient({ socketPath }),
          terminal: new HerdrTerminalLauncher({ socketPath }),
          feed,
          usage: new UsageService(),
          config,
          publisher:
            config.ntfyUrl && config.ntfyTopic
              ? new NtfyPublisher({ baseUrl: config.ntfyUrl, topic: config.ntfyTopic })
              : undefined,
        });
        console.error(`scoutr bridge listening on ${server.url}`);
        // Never print the token: the credential would persist in journald.
        console.error(`token: run 'scoutr-bridge pair' or read ${defaultConfigPath()}`);
        if (config.ntfyUrl && config.ntfyTopic) {
          console.error(`push: ntfy at ${config.ntfyUrl}/topic/${config.ntfyTopic}`);
        }
        console.error(
          `front with: tailscale serve --bg 443 ${server.url} (then the app uses https://<host>/ws + token)`,
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
          "usage: scoutr-bridge <herdr status|herdr snapshot|herdr watch|pair|serve>",
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
