package dev.scoutr.app.service

import android.content.Intent
import android.net.Uri
import dev.scoutr.app.data.HostPaneKey
import dev.scoutr.app.data.HostProfile
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.HostRegistryStore
import dev.scoutr.app.data.decodeHostPaneKey
import dev.scoutr.app.data.decodeHostProfileKey
import dev.scoutr.app.data.encode

/**
 * A notification destination. [profile] is null only for a pre-migration
 * unqualified link; new links always carry the generation-qualified profile.
 */
data class ScoutrDeepLink(
    val paneId: String,
    val status: String?,
    val profile: HostProfileKey? = null,
)

/** Notification destination for an orphan PI-workflow run: progress, never Chat. */
data class ScoutrSubagentLink(
    val runId: String,
    val profile: HostProfileKey,
)

/** Parses a scoutr URI; returns null for anything that is not a chat link. */
fun parseScoutrUri(uri: String?): ScoutrDeepLink? {
    if (uri == null) return null
    val parsed = Uri.parse(uri)
    if (parsed.scheme != "scoutr" || parsed.authority != "chat") return null

    val segments = parsed.pathSegments
    val paneAndProfile = when (segments.size) {
        // Legacy: scoutr://chat/<paneId>. It is accepted by the caller only
        // after checking the registry's legacyLinkGeneration marker.
        1 -> {
            val pane = segments[0].takeIf { it.isNotBlank() } ?: return null
            val hostId = parsed.getQueryParameter(QUERY_HOST_ID)
            val generation = parsed.getQueryParameter(QUERY_PROFILE_GENERATION)
            if (hostId == null && generation == null) {
                pane to null
            } else {
                val profile = parseProfile(hostId, generation) ?: return null
                pane to profile
            }
        }

        // New immutable form: scoutr://chat/<hostId>/<generation>/<paneId>.
        3 -> {
            val profile = parseProfile(segments[0], segments[1]) ?: return null
            segments[2].takeIf { it.isNotBlank() }?.let { it to profile } ?: return null
        }

        else -> return null
    }
    return ScoutrDeepLink(
        paneId = paneAndProfile.first,
        status = parsed.getQueryParameter(QUERY_STATUS),
        profile = paneAndProfile.second,
    )
}

/** Parses a scoutr subagent URI; returns null for anything that is not a progress link. */
fun parseScoutrSubagentUri(uri: String?): ScoutrSubagentLink? {
    if (uri == null) return null
    val parsed = Uri.parse(uri)
    if (parsed.scheme != "scoutr" || parsed.authority != "subagent") return null
    val segments = parsed.pathSegments
    if (segments.size != 3) return null
    val profile = parseProfile(segments[0], segments[1]) ?: return null
    val runId = segments[2].takeIf { it.isNotBlank() } ?: return null
    return ScoutrSubagentLink(runId = runId, profile = profile)
}

/** Builds scoutr://subagent/<hostId>/<generation>/<runId>. */
fun scoutrSubagentUri(profile: HostProfileKey, runId: String): String =
    Uri.Builder()
        .scheme("scoutr")
        .authority("subagent")
        .appendPath(profile.hostId)
        .appendPath(profile.profileGeneration.toString())
        .appendPath(runId)
        .build()
        .toString()

/** Resolves a parsed subagent destination against the current registry generation. */
fun resolveCurrentSubagentLink(
    link: ScoutrSubagentLink,
    registry: HostRegistryStore,
    isRetiring: (String) -> Boolean = { false },
) : ScoutrSubagentLink? {
    val profile = currentHostProfile(registry, link.profile, isRetiring) ?: return null
    return link.copy(profile = HostProfileKey(profile.hostId, profile.profileGeneration))
}

/** Builds the legacy, unqualified form retained for old tests and old links. */
fun scoutrChatUri(paneId: String, status: String?): String {
    val base = "scoutr://chat/$paneId"
    return if (status != null) "$base?status=$status" else base
}

/** Builds a new immutable destination containing host id and profile generation. */
fun scoutrChatUri(
    hostId: String,
    profileGeneration: Long,
    paneId: String,
    status: String?,
): String = scoutrChatUri(HostProfileKey(hostId, profileGeneration), paneId, status)

fun scoutrChatUri(profile: HostProfileKey, paneId: String, status: String?): String {
    val builder = Uri.Builder()
        .scheme("scoutr")
        .authority("chat")
        .appendPath(profile.hostId)
        .appendPath(profile.profileGeneration.toString())
        .appendPath(paneId)
    if (status != null) builder.appendQueryParameter(QUERY_STATUS, status)
    return builder.build().toString()
}

/** A validated notification deep link: the rebuilt URI plus its target identity. */
data class ValidatedNotificationLink(
    val uri: String,
    val paneId: String,
    val profile: HostProfileKey? = null,
)

/**
 * Turns an untrusted push payload/link into a canonical destination. A
 * qualified caller never accepts an unqualified click string: doing so would
 * let an old destination silently bind to a different host.
 */
fun resolveNotificationLink(
    click: String?,
    paneId: String?,
    status: String,
): ValidatedNotificationLink? = resolveNotificationLink(click, paneId, status, null)

fun resolveNotificationLink(
    click: String?,
    paneId: String?,
    status: String,
    profile: HostProfileKey?,
): ValidatedNotificationLink? {
    val parsed = click?.let(::parseScoutrUri)
    if (parsed != null) {
        if (profile != null && parsed.profile != profile) return null
        val resolvedProfile = parsed.profile ?: profile
        val uri = if (resolvedProfile == null) {
            scoutrChatUri(parsed.paneId, parsed.status)
        } else {
            scoutrChatUri(resolvedProfile, parsed.paneId, parsed.status)
        }
        return ValidatedNotificationLink(uri, parsed.paneId, resolvedProfile)
    }

    val fallbackPane = paneId?.takeIf { it.isNotBlank() } ?: return null
    val uri = profile?.let { scoutrChatUri(it, fallbackPane, status) }
        ?: scoutrChatUri(fallbackPane, status)
    return ValidatedNotificationLink(uri, fallbackPane, profile)
}

/**
 * Resolves a parsed destination against the current registry. Unqualified
 * links are the sole compatibility exception: exactly one profile and the
 * matching migration marker are required.
 */
fun resolveCurrentNotificationLink(
    link: ScoutrDeepLink,
    registry: HostRegistryStore,
    isRetiring: (String) -> Boolean = { false },
): ScoutrDeepLink? {
    val state = registry.snapshot()
    val profile = if (link.profile != null) {
        state.profiles.firstOrNull {
            it.hostId == link.profile.hostId && it.profileGeneration == link.profile.profileGeneration
        }
    } else {
        state.profiles.singleOrNull()?.takeIf {
            state.legacyLinkGeneration == it.profileGeneration
        }
    } ?: return null
    if (isRetiring(profile.hostId)) return null
    return link.copy(profile = HostProfileKey(profile.hostId, profile.profileGeneration))
}

/** Exact current-profile gate shared by FCM and notification action receivers. */
internal fun currentHostProfile(
    registry: HostRegistryStore,
    key: HostProfileKey,
    isRetiring: (String) -> Boolean = { false },
): HostProfile? {
    if (isRetiring(key.hostId)) return null
    return registry.snapshot().profiles.firstOrNull {
        it.hostId == key.hostId && it.profileGeneration == key.profileGeneration
    }
}

/** Strictly parses the positive decimal generation used on the push wire. */
internal fun parseProfileGeneration(value: String?): Long? {
    if (value == null || !value.matches(Regex("[1-9][0-9]*"))) return null
    return value.toLongOrNull()?.takeIf { it > 0L }
}

/** Identity extras used by notification content and action PendingIntents. */
internal const val EXTRA_HOST_ID = "scoutr.hostId"
internal const val EXTRA_PROFILE_GENERATION = "scoutr.profileGeneration"
internal const val EXTRA_HOST_PROFILE_KEY = "scoutr.hostProfileKey"
internal const val EXTRA_PANE_ID = "scoutr.paneId"
internal const val EXTRA_ACTION_KIND = "scoutr.actionKind"

internal fun Intent.putHostPaneIdentity(key: HostPaneKey, actionKind: String): Intent = apply {
    putExtra(EXTRA_HOST_ID, key.profile.hostId)
    putExtra(EXTRA_PROFILE_GENERATION, key.profile.profileGeneration)
    putExtra(EXTRA_HOST_PROFILE_KEY, key.profile.encode())
    putExtra(EXTRA_PANE_ID, key.paneId)
    putExtra(EXTRA_ACTION_KIND, actionKind)
}

/** Parses both the encoded profile extra and its explicit fields defensively. */
internal fun hostPaneKeyFromIntent(intent: Intent): HostPaneKey? {
    val encoded = intent.getStringExtra(EXTRA_HOST_PROFILE_KEY)
    val fromEncoded = if (encoded == null) null else decodeHostProfileKey(encoded) ?: return null
    val hostId = intent.getStringExtra(EXTRA_HOST_ID)
    val generation = when (val raw = intent.extras?.get(EXTRA_PROFILE_GENERATION)) {
        is Number -> raw.toLong()
        is String -> raw.toLongOrNull()
        else -> null
    }
    val profile = when {
        fromEncoded != null -> {
            if (hostId != null && hostId != fromEncoded.hostId) return null
            if (generation != null && generation != fromEncoded.profileGeneration) return null
            fromEncoded
        }
        hostId != null && generation != null && generation > 0L ->
            HostProfileKey(hostId, generation)
        else -> return null
    }
    val paneId = intent.getStringExtra(EXTRA_PANE_ID)?.takeIf { it.isNotBlank() } ?: return null
    return HostPaneKey(profile, paneId)
}

private fun parseProfile(hostId: String?, generation: String?): HostProfileKey? {
    val cleanHost = hostId?.takeIf { it.isNotBlank() } ?: return null
    val cleanGeneration = parseProfileGeneration(generation) ?: return null
    return HostProfileKey(cleanHost, cleanGeneration)
}

private const val QUERY_STATUS = "status"
private const val QUERY_HOST_ID = "hostId"
private const val QUERY_PROFILE_GENERATION = "profileGeneration"
