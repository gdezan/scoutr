package dev.scoutr.app.state

import dev.scoutr.app.data.BoardState
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.SessionDescriptor

/** One Board row bound to the host profile it came from. */
data class HostedSession(
    val profile: HostProfileKey,
    val session: SessionDescriptor,
)

/** A host the merged Board calls out as unusable rather than rendering empty. */
data class HostIssue(
    val hostId: String,
    val alias: String,
    val message: String,
    val reportedHostId: String? = null,
)

/**
 * Pure merge rules for the unified Board: which hosts' rows are included,
 * their global ordering inside each status section, and how rows group back
 * into the existing [BoardState] sections. Kept out of Compose so tests can
 * pin the ordering without a UI.
 *
 * Incompatible and identity-changed hosts are excluded from rows entirely;
 * they surface through [BoardUiState.hostIssues] instead.
 */
object BoardMerge {

    /** Rows for the current filter, sorted globally by recency, alias, host id. */
    fun hostedSessions(
        hostBoards: Map<String, HostBoardState>,
        statuses: Map<String, HostAvailability>,
        profiles: Map<String, HostProfileKey>,
        aliases: Map<String, String>,
        filter: String?,
    ): List<HostedSession> {
        val included = hostBoards
            .filterKeys { id -> filter == null || id == filter }
            .filterKeys { id -> statuses[id].usableForData() }
        return included.flatMap { (hostId, board) ->
            val profile = profiles[hostId] ?: return@flatMap emptyList()
            board.sessions.map { HostedSession(profile, it) }
        }.sortedWith(
            compareByDescending<HostedSession> { it.session.updatedAtMs ?: 0.0 }
                .thenBy { aliases[it.profile.hostId] ?: it.profile.hostId }
                .thenBy { it.profile.hostId },
        )
    }

    /** Groups ordered rows into the existing status sections, preserving order within each. */
    fun grouped(rows: List<HostedSession>): BoardState = BoardState.group(rows.map { it.session })

    /** Hosts the compact status area must call out instead of rendering rows. */
    fun issues(
        registryOrder: List<String>,
        aliases: Map<String, String>,
        statuses: Map<String, HostAvailability>,
    ): List<HostIssue> = registryOrder.mapNotNull { hostId ->
        when (val status = statuses[hostId] ?: HostAvailability.Unknown) {
            is HostAvailability.Incompatible -> HostIssue(
                hostId = hostId,
                alias = aliases[hostId] ?: hostId,
                message = status.message,
            )
            is HostAvailability.IdentityChanged -> HostIssue(
                hostId = hostId,
                alias = aliases[hostId] ?: hostId,
                message = "Bridge identity changed",
                reportedHostId = status.reportedHostId.takeIf(String::isNotBlank),
            )
            else -> null
        }
    }
}

/** Only Online/Offline hosts feed merged data; blocked hosts are called out separately. */
fun HostAvailability?.usableForData(): Boolean =
    this == null || this is HostAvailability.Unknown ||
        this is HostAvailability.Online || this is HostAvailability.Offline
