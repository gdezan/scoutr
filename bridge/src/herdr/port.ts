import type { AgentReadResponse } from "./client.js";
import type { HerdrPong, JsonValue, SessionSnapshot } from "./types.js";

/**
 * The herdr surface the bridge actually calls. Every agent backend (pi today,
 * a second adapter later) implements this; HerdrClient is the live-socket
 * implementation. Keep the list to what the bridge calls today — a port that
 * mirrors the whole herdr RPC surface is a second copy of the client, not a
 * seam.
 */
export interface HerdrPort {
  ping(): Promise<HerdrPong>;
  snapshot(): Promise<SessionSnapshot>;
  workspaceCreate(params: {
    cwd?: string | null;
    label?: string | null;
    focus?: boolean;
  }): Promise<{ workspace: { workspace_id?: string }; root_pane?: { pane_id?: string } }>;
  /** Create a tab in a workspace; herdr pre-creates one root pane in it. */
  tabCreate(params: {
    workspace_id?: string | null;
    cwd?: string | null;
    label?: string | null;
    focus?: boolean;
  }): Promise<{ tab: { tab_id?: string }; root_pane?: { pane_id?: string } }>;
  tabRename(tab_id: string, label: string): Promise<void>;
  tabClose(tab_id: string): Promise<void>;
  paneRename(pane_id: string, label: string): Promise<void>;
  paneClose(pane_id: string): Promise<void>;
  workspaceRename(workspace_id: string, label: string): Promise<void>;
  workspaceClose(workspace_id: string): Promise<void>;
  paneSendInput(pane_id: string, text: string, keys?: string[]): Promise<void>;
  paneSendKeys(pane_id: string, keys: string[]): Promise<void>;
  paneSendText(pane_id: string, text: string): Promise<void>;
  agentPrompt(target: string, text: string): Promise<JsonValue>;
  agentGet(target: string, timeoutMs?: number): Promise<JsonValue>;
  agentRead(
    target: string,
    source: string,
    options?: { lines?: number; format?: string; stripAnsi?: boolean; requestTimeoutMs?: number },
  ): Promise<AgentReadResponse>;
}
