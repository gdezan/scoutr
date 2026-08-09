#!/usr/bin/env node
/**
 * cockpit-bridge CLI — development and ops surface.
 *
 *   cockpit-bridge herdr status          → ping + server info
 *   cockpit-bridge herdr snapshot        → print the full session snapshot as JSON
 *   cockpit-bridge herdr watch           → stream live events (Ctrl-C to stop)
 *   cockpit-bridge serve                 → run the bridge daemon (layer 2)
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
          console.error("usage: cockpit-bridge herdr <status|snapshot|watch>");
          process.exitCode = 2;
        }
        break;
      }
      case "serve": {
        const { createCockpitServer } = await import("./server.js");
        const { loadOrCreateConfig } = await import("./config.js");
        const { UsageService } = await import("./usage/providers.js");
        const { NtfyPublisher } = await import("./notify.js");

        const config = await loadOrCreateConfig();
        const feed = new HerdrEventFeed(socketPath);
        await feed.start();
        console.error(`connected to herdr at ${socketPath}`);

        const server = createCockpitServer({
          herdr: new HerdrClient({ socketPath }),
          feed,
          usage: new UsageService(),
          config,
          publisher:
            config.ntfyUrl && config.ntfyTopic
              ? new NtfyPublisher({ baseUrl: config.ntfyUrl, topic: config.ntfyTopic })
              : undefined,
        });
        console.error(`cockpit bridge listening on ${server.url}`);
        console.error(`token: ${config.token}`);
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
          "usage: cockpit-bridge <herdr status|herdr snapshot|herdr watch|serve>",
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
