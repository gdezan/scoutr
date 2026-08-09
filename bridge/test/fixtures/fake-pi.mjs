#!/usr/bin/env node
// Fake `pi --mode rpc` for bridge tests: a scripted JSONL responder.
//
// Behavior:
//   - get_state            -> session info
//   - prompt "ASK"         -> emit extension_ui_request ui-1 (select) + user entry
//   - prompt <anything>    -> user entry + assistant "Echo: <message>" entry
//   - extension_ui_response -> assistant "Approved: <value>" entry
//   - get_entries?since    -> entries strictly after the cursor
//   - anything else        -> {success:false, error:"unknown command"}
// Entries use the same shape get_entries returns ({type:"message", id, ...}).
import { createInterface } from "node:readline";
import { stdin, stdout } from "node:process";

const entries = [];
let seq = 0;

function entry(role, content) {
  seq += 1;
  const record = {
    type: "message",
    id: `e${seq}`,
    parentId: seq === 1 ? null : `e${seq - 1}`,
    timestamp: new Date().toISOString(),
    message: { role, content },
  };
  entries.push(record);
  return record.id;
}

function response(req, data = {}, error) {
  const out = { type: "response", command: req.type, success: !error };
  if (req.id) out.id = req.id;
  if (error) out.error = error;
  else out.data = data;
  stdout.write(JSON.stringify(out) + "\n");
}

const rl = createInterface({ input: stdin, crlfDelay: Infinity });
rl.on("line", (line) => {
  let req;
  try {
    req = JSON.parse(line);
  } catch {
    return;
  }
  switch (req.type) {
    case "get_state":
      response(req, { sessionId: "fake-session", sessionName: "fake" });
      break;
    case "get_entries": {
      const since = req.since ?? null;
      const filtered = since ? entries.filter((e) => e.id > since) : entries;
      response(req, { entries: filtered, leafId: entries.length ? entries[entries.length - 1].id : null });
      break;
    }
    case "prompt": {
      entry("user", [{ type: "text", text: req.message }]);
      if (req.message === "ASK") {
        stdout.write(
          JSON.stringify({
            type: "extension_ui_request",
            id: "ui-1",
            method: "select",
            title: "Test question",
            options: ["Approve", "Reject"],
          }) + "\n",
        );
      } else {
        entry("assistant", [{ type: "text", text: `Echo: ${req.message}` }]);
      }
      response(req, {});
      break;
    }
    case "steer":
      entry("user", [{ type: "text", text: `[steer] ${req.message}` }]);
      entry("assistant", [{ type: "text", text: `Echo: ${req.message}` }]);
      response(req, {});
      break;
    case "extension_ui_response": {
      const value = req.value ?? (req.confirmed ? "true" : "cancelled");
      entry("assistant", [{ type: "text", text: `Approved: ${value}` }]);
      break;
    }
    default:
      response(req, undefined, `unknown command: ${req.type}`);
  }
});
