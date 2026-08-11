import type { AgentReadResponse } from "./client.js";
import type { HerdrPong, SessionSnapshot } from "./types.js";

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
  workspaceRename(workspace_id: string, label: string): Promise<unknown>;
  workspaceClose(workspace_id: string): Promise<unknown>;
  paneSendInput(pane_id: string, text: string, keys?: string[]): Promise<unknown>;
  paneSendKeys(pane_id: string, keys: string[]): Promise<unknown>;
  paneSendText(pane_id: string, text: string): Promise<unknown>;
  agentPrompt(target: string, text: string): Promise<unknown>;
  agentGet(target: string, timeoutMs?: number): Promise<unknown>;
  agentRead(
    target: string,
    source: string,
    options?: { lines?: number; format?: string; stripAnsi?: boolean; requestTimeoutMs?: number },
  ): Promise<AgentReadResponse>;
}
