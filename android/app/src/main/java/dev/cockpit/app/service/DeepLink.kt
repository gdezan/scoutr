package dev.cockpit.app.service

import android.net.Uri

/** The status a notification's title implies ("needs you" → blocked), used to rebuild deep links. */
fun statusForTitle(title: String?): String =
    if (title?.contains("needs you") == true) "blocked" else "working"

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

/** A validated notification deep link: the rebuilt URI plus the pane it targets. */
data class ValidatedNotificationLink(
    val uri: String,
    val paneId: String,
)

/**
 * Turns untrusted ntfy payload into a validated cockpit deep link, the same
 * way MainActivity's entry path validates incoming URIs.
 *
 * A `click` that parses as a `cockpit://chat/<paneId>` URI wins (and is
 * rebuilt canonically, dropping any foreign query junk); an invalid click
 * falls back to the raw `paneId`; null when neither exists. The returned
 * [ValidatedNotificationLink.paneId] is always the validated value, so the
 * inline reply action never trusts a raw payload field when a parsed one
 * exists.
 */
fun resolveNotificationLink(click: String?, paneId: String?, status: String): ValidatedNotificationLink? {
    val parsed = click?.let(::parseCockpitUri)
    if (parsed != null) {
        return ValidatedNotificationLink(cockpitChatUri(parsed.paneId, parsed.status), parsed.paneId)
    }
    val fallbackPane = paneId ?: return null
    return ValidatedNotificationLink(cockpitChatUri(fallbackPane, status), fallbackPane)
}
