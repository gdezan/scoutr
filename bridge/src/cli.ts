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
        console.error("serve is wired in a later layer");
        process.exitCode = 2;
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
