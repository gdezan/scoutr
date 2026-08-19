package dev.scoutr.app.service

import android.net.Uri

/** A scoutr://chat/<paneId>?status=<status> deep link from a notification. */
data class ScoutrDeepLink(
    val paneId: String,
    val status: String?,
)

/** Parses a scoutr URI; returns null for anything that is not a chat link. */
fun parseScoutrUri(uri: String?): ScoutrDeepLink? {
    if (uri == null) return null
    val parsed = Uri.parse(uri)
    if (parsed.scheme != "scoutr") return null
    // scoutr://chat/<paneId>?status=<status> — "chat" is the authority.
    if (parsed.authority != "chat") return null
    val paneId = parsed.pathSegments.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
    return ScoutrDeepLink(paneId, parsed.getQueryParameter("status"))
}

/** Builds the deep-link URI for a pane, used by notifications and the reply action. */
fun scoutrChatUri(paneId: String, status: String?): String {
    val base = "scoutr://chat/$paneId"
    return if (status != null) "$base?status=$status" else base
}

/** A validated notification deep link: the rebuilt URI plus the pane it targets. */
data class ValidatedNotificationLink(
    val uri: String,
    val paneId: String,
)

/**
 * Turns an untrusted push payload into a validated scoutr deep link, the same
 * way MainActivity's entry path validates incoming URIs.
 *
 * A `click` that parses as a `scoutr://chat/<paneId>` URI wins (and is
 * rebuilt canonically, dropping any foreign query junk); an invalid click
 * falls back to the raw `paneId`; null when neither exists. The returned
 * [ValidatedNotificationLink.paneId] is always the validated value, so the
 * inline reply action never trusts a raw payload field when a parsed one
 * exists.
 */
fun resolveNotificationLink(click: String?, paneId: String?, status: String): ValidatedNotificationLink? {
    val parsed = click?.let(::parseScoutrUri)
    if (parsed != null) {
        return ValidatedNotificationLink(scoutrChatUri(parsed.paneId, parsed.status), parsed.paneId)
    }
    val fallbackPane = paneId ?: return null
    return ValidatedNotificationLink(scoutrChatUri(fallbackPane, status), fallbackPane)
}
