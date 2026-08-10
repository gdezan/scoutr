package dev.cockpit.app.service

import android.net.Uri

/** A cockpit://chat/<paneId>?status=<status> deep link from a notification. */
data class CockpitDeepLink(
    val paneId: String,
    val status: String?,
)

/** Parses a cockpit URI; returns null for anything that is not a chat link. */
fun parseCockpitUri(uri: String?): CockpitDeepLink? {
    if (uri == null) return null
    val parsed = Uri.parse(uri)
    if (parsed.scheme != "cockpit") return null
    // cockpit://chat/<paneId>?status=<status> — "chat" is the authority.
    if (parsed.authority != "chat") return null
    val paneId = parsed.pathSegments.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
    return CockpitDeepLink(paneId, parsed.getQueryParameter("status"))
}

/** Builds the deep-link URI for a pane, used by notifications and the reply action. */
fun cockpitChatUri(paneId: String, status: String?): String {
    val base = "cockpit://chat/$paneId"
    return if (status != null) "$base?status=$status" else base
}
