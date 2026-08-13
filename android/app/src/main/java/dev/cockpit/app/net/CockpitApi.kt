package dev.cockpit.app.net

import dev.cockpit.app.data.CatalogAction
import dev.cockpit.app.data.SessionAction
import dev.cockpit.app.data.AgentKindsResponse
import dev.cockpit.app.data.AgentsResponse
import dev.cockpit.app.data.AttachmentResponse
import dev.cockpit.app.data.CommandsCatalogResponse
import dev.cockpit.app.data.ControlResponse
import dev.cockpit.app.data.CreatedSessionResponse
import dev.cockpit.app.data.DirListingResponse
import dev.cockpit.app.data.HealthResponse
import dev.cockpit.app.data.ModelsCatalogResponse
import dev.cockpit.app.data.RepoArtifactsResponse
import dev.cockpit.app.data.RepoDiffResponse
import dev.cockpit.app.data.RepoOverviewResponse
import dev.cockpit.app.data.SessionCatalogResponse
import dev.cockpit.app.data.SessionReadResponse
import dev.cockpit.app.data.UsageResponse
import dev.cockpit.app.data.WsFrame
import kotlinx.serialization.json.JsonObject

/**
 * The typed bridge surface ViewModels consume. [BridgeClient] is the one
 * production implementation (OkHttp + ConnectionStore); tests use
 * [FakeCockpitApi] so behaviour can be asserted without an HTTP server.
 */
interface CockpitApi {
    val connectedHost: String?

    suspend fun health(host: String? = null, token: String? = null): HealthResponse
    suspend fun agents(): AgentsResponse
    suspend fun session(path: String, since: String? = null): SessionReadResponse
    suspend fun sessionCatalog(query: String? = null, limit: Int? = null): SessionCatalogResponse
    suspend fun sessionCatalogAction(action: CatalogAction, path: String, text: String? = null): CreatedSessionResponse
    suspend fun createSession(
        cwd: String,
        model: String,
        name: String? = null,
        initialPrompt: String? = null,
        thinkingLevel: String? = null,
        agent: String? = null,
    ): CreatedSessionResponse
    suspend fun controlSession(paneId: String, action: SessionAction, text: String? = null): ControlResponse
    suspend fun models(agent: String? = null): ModelsCatalogResponse
    suspend fun commands(cwd: String? = null, agent: String? = null): CommandsCatalogResponse
    suspend fun agentKinds(): AgentKindsResponse
    suspend fun dirs(path: String? = null): DirListingResponse
    suspend fun repoOverview(path: String): RepoOverviewResponse
    suspend fun repoDiff(path: String, base: String = "HEAD", kind: String = "working"): RepoDiffResponse
    suspend fun repoArtifacts(path: String): RepoArtifactsResponse
    suspend fun usage(): UsageResponse
    suspend fun uploadAttachment(name: String, mime: String, bytes: ByteArray): AttachmentResponse

    /** Opens a short-lived WS, sends one command, and waits for the first ack frame. */
    suspend fun sendCommand(command: Map<String, String>): WsFrame
    suspend fun sendCommandJson(command: JsonObject): WsFrame
    suspend fun steer(target: String, text: String): WsFrame
    suspend fun runSlashCommand(paneId: String, text: String): WsFrame
    suspend fun answerQuestion(
        paneId: String,
        text: String,
        keys: List<String> = emptyList(),
        trailingKeys: List<String> = emptyList(),
    ): WsFrame
}
