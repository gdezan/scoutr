package dev.scoutr.app.net

import dev.scoutr.app.data.CatalogAction
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.HostRegistryStore
import dev.scoutr.app.data.SessionAction
import dev.scoutr.app.data.SessionKey
import dev.scoutr.app.data.TerminalHierarchyCommand
import java.io.File
import kotlinx.coroutines.CancellationException

/** Raised before an old route can dispatch to a newly re-paired host id. */
class StaleHostRouteException(profile: HostProfileKey) :
    CancellationException("Host route is stale: ${profile.hostId}/${profile.profileGeneration}")

/**
 * Generation gate for route-scoped remote work. HostClientFactory selects the
 * host id; this wrapper adds the profile generation carried by the route.
 */
class GenerationGuardedScoutrApi(
    private val registry: HostRegistryStore,
    private val profile: HostProfileKey,
    private val delegate: ScoutrApi,
    private val connectionRevision: Long? = null,
) : ScoutrApi {
    override val connectedHost: String?
        get() {
            ensureCurrent()
            return delegate.connectedHost
        }

    private fun ensureCurrent() {
        val current = registry.snapshot().profiles.firstOrNull { it.hostId == profile.hostId }
        if (current?.profileGeneration != profile.profileGeneration ||
            connectionRevision != null && current.connectionRevision != connectionRevision
        ) {
            throw StaleHostRouteException(profile)
        }
    }

    override suspend fun health() = checked { delegate.health() }
    override suspend fun agents() = checked { delegate.agents() }
    override suspend fun subagentProgress(runId: String) = checked { delegate.subagentProgress(runId) }
    override suspend fun registerDevice(fcmToken: String, profileGeneration: Long) = checked {
        delegate.registerDevice(fcmToken, profileGeneration)
    }
    override suspend fun unregisterDevice(fcmToken: String) = checked { delegate.unregisterDevice(fcmToken) }
    override suspend fun session(key: SessionKey, since: String?, before: String?, limit: Int?) =
        checked { delegate.session(key, since = since, before = before, limit = limit) }
    override suspend fun sessionCatalog(query: String?, limit: Int?) = checked { delegate.sessionCatalog(query, limit) }
    override suspend fun sessionCatalogAction(action: CatalogAction, key: SessionKey, text: String?) = checked {
        delegate.sessionCatalogAction(action, key, text)
    }
    override suspend fun createSession(
        cwd: String,
        model: String,
        name: String?,
        initialPrompt: String?,
        thinkingLevel: String?,
        agent: String?,
    ) = checked { delegate.createSession(cwd, model, name, initialPrompt, thinkingLevel, agent) }
    override suspend fun controlSession(paneId: String, action: SessionAction, text: String?) = checked {
        delegate.controlSession(paneId, action, text)
    }
    override suspend fun models(agent: String?) = checked { delegate.models(agent) }
    override suspend fun commands(cwd: String?, agent: String?) = checked { delegate.commands(cwd, agent) }
    override suspend fun agentKinds() = checked { delegate.agentKinds() }
    override suspend fun dirs(path: String?) = checked { delegate.dirs(path) }
    override suspend fun files(cwd: String, includeHidden: Boolean) = checked { delegate.files(cwd, includeHidden) }
    override suspend fun repoOverview(path: String) = checked { delegate.repoOverview(path) }
    override suspend fun repoDiff(path: String, base: String, kind: String) = checked {
        delegate.repoDiff(path, base, kind)
    }
    override suspend fun repoFileDiff(path: String, base: String, kind: String, file: String) = checked {
        delegate.repoFileDiff(path, base, kind, file)
    }
    override suspend fun file(path: String, offset: Long, limit: Int) = checked {
        delegate.file(path, offset, limit)
    }
    override suspend fun repoFile(path: String, base: String, kind: String, file: String) = checked {
        delegate.repoFile(path, base, kind, file)
    }
    override suspend fun repoArtifacts(path: String) = checked { delegate.repoArtifacts(path) }
    override suspend fun usage() = checked { delegate.usage() }
    override suspend fun uploadAttachment(name: String, mime: String, bytes: ByteArray) = checked {
        delegate.uploadAttachment(name, mime, bytes)
    }
    override suspend fun terminalHierarchy(command: TerminalHierarchyCommand) = checked {
        delegate.terminalHierarchy(command)
    }
    override suspend fun snapshot() = checked { delegate.snapshot() }
    override suspend fun steer(target: String, text: String) = checked { delegate.steer(target, text) }
    override suspend fun runSlashCommand(paneId: String, text: String) = checked {
        delegate.runSlashCommand(paneId, text)
    }
    override suspend fun answerAsk(
        paneId: String,
        callId: String,
        answers: List<AskAnswer>,
        text: String,
    ) = checked { delegate.answerAsk(paneId, callId, answers, text) }
    override suspend fun dismissAsk(paneId: String) = checked { delegate.dismissAsk(paneId) }
    override suspend fun updateStatus(commit: String, version: String, dirty: Boolean) = checked {
        delegate.updateStatus(commit, version, dirty)
    }
    override suspend fun updateBuild() = checked { delegate.updateBuild() }
    override suspend fun updateApkStatus() = checked { delegate.updateApkStatus() }
    override suspend fun downloadApk(destination: File, resumeFrom: Long, onProgress: (Long, Long) -> Unit) = checked {
        delegate.downloadApk(destination, resumeFrom, onProgress)
    }
    override suspend fun downloadWorkspaceFile(
        destination: File,
        path: String,
        resumeFrom: Long,
        onProgress: (Long, Long) -> Unit,
    ) = checked { delegate.downloadWorkspaceFile(destination, path, resumeFrom, onProgress) }
    private suspend fun <T> checked(call: suspend () -> T): T {
        ensureCurrent()
        val result = call()
        ensureCurrent()
        return result
    }
}
