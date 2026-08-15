package dev.scoutr.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import dev.scoutr.app.ui.theme.ScoutrType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.scoutr.app.data.UsageSnapshot
import dev.scoutr.app.data.UsageWindow
import dev.scoutr.app.state.UsageUiState
import dev.scoutr.app.state.UsageViewModel
import dev.scoutr.app.state.Loadable
import dev.scoutr.app.ui.components.PullRefreshIndicator
import dev.scoutr.app.ui.components.pullRefreshSemantics
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
@Composable
fun UsageScreen(
    viewModel: UsageViewModel,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    // Usage owns a retained ViewModel, so its producer must follow the screen
    // lifecycle rather than ViewModel lifetime.
    LifecycleStartEffect(Unit) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }

    val nowMillis = rememberMinuteClock()
    UsageContent(ui = ui, onRefresh = viewModel::refreshUsage, nowMillis = nowMillis, modifier = modifier)
}

@Composable
private fun rememberMinuteClock(): Long {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(60_000 - (System.currentTimeMillis() % 60_000))
            nowMillis = System.currentTimeMillis()
        }
    }
    return nowMillis
}

@Composable
internal fun UsageContent(
    ui: UsageUiState,
    onRefresh: () -> Unit,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    val refreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = ui.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier
            .fillMaxSize()
            .pullRefreshSemantics(onRefresh)
            .testTag("usage_refresh_root"),
        state = refreshState,
        indicator = { PullRefreshIndicator(refreshState, ui.isRefreshing) },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val load = ui.providers) {
                is Loadable.Loading -> UsageLoading()
                is Loadable.Idle -> UsageEmpty(onRefresh)
                is Loadable.Failed -> UsageError(load.reason, onRefresh)
                is Loadable.Ready -> {
                    if (load.value.isEmpty()) {
                        UsageEmpty(onRefresh)
                    } else {
                        ui.error?.let { UsageError(it, onRefresh) }
                        for (provider in load.value) {
                            key(provider.provider) { ProviderCard(provider, nowMillis, onRefresh) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(provider: UsageSnapshot, nowMillis: Long, onRefresh: () -> Unit) {
    Card(
        // Tiles are filled, never outlined: the tonal step off the canvas is the
        // whole hierarchy, and a stroke on top of it reads as a second edge (§9c).
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth().testTag("usage_${provider.provider}"),
    ) {
        // A provider that failed with nothing cached has no bars to show, so it
        // collapses to its name plus one muted line. Signed-out or unreachable
        // providers then sit quietly instead of shouting over the ones that work.
        val collapsed = provider.error != null && provider.windows.isEmpty()
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(if (collapsed) 6.dp else 14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = provider.label,
                    // Tile titles are the 13.5sp semibold slot, matching the
                    // board/session tiles the Usage tile borrows from (§9c).
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                updatedLabel(provider.updatedAt, nowMillis)?.let { updated ->
                    Text(
                        text = updated,
                        style = ScoutrType.monoMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            if (collapsed) {
                Text(
                    text = provider.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("usage_collapsed_error_${provider.provider}"),
                )
            } else {
                provider.error?.let { ProviderError(it, provider.windows.isNotEmpty(), onRefresh) }

                if (provider.windows.isEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Usage unavailable",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "This provider did not report a usage limit.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    for (window in provider.windows) {
                        key(window.label, window.currency, window.windowSeconds) {
                            if (window.isBalance()) {
                                BalanceRow(window)
                            } else {
                                UsageBar(window, provider.provider, nowMillis / 1_000)
                            }
                        }
                    }
                }

                // DeepSeek is the only covered provider that bills by the UTC clock;
                // amber is the warning tier, quiet gray the settled off-peak state.
                if (provider.provider == "deepseek" && provider.windows.isNotEmpty()) {
                    val pricing = deepseekPricing(nowMillis / 1_000)
                    Text(
                        text = pricing.label,
                        style = ScoutrType.monoMeta,
                        color = if (pricing.peak) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderError(message: String, hasCachedData: Boolean, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (hasCachedData) "Showing last known usage. $message" else message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRefresh) { Text("Retry") }
    }
}

@Composable
private fun UsageBar(window: UsageWindow, provider: String, nowSeconds: Long) {
    val percent = window.usedPercent.coerceIn(0.0, 100.0)
    val progress by animateFloatAsState(
        targetValue = (percent / 100.0).toFloat(),
        animationSpec = tween(durationMillis = 300),
        label = "usage progress",
    )
    val color = when {
        percent >= 90 -> MaterialTheme.colorScheme.error
        percent >= 75 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    // Teal is data, so it stays on the meter alone; only a crossed threshold is
    // allowed to color the label and the number (DESIGN.md, "Usage").
    val elevated = percent >= 75
    val reset = resetLabel(window.resetAt, nowSeconds)
    val amount = amountLabel(window)
    val description = buildString {
        append(windowTitle(window.label))
        append(", ${percent.roundToInt()}% used")
        reset?.let { append(", $it") }
        amount?.let { append(", $it") }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("usage_bar_${provider}_${window.label}"),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // The window name is a mono-caps section header, not prose: it
                // titles the meter the way `WEEKLY LIMIT` does in §9c, and it
                // takes the threshold color only once the threshold is crossed.
                text = windowTitle(window.label).uppercase(Locale.US),
                style = ScoutrType.monoSection,
                color = if (elevated) color else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${percent.roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = if (elevated) color else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        // The 4dp meter sits on the canvas color; the marker is a quiet time
        // reference, not another status color or a text annotation.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(10.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.background),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(2.dp))
                        .background(color),
                )
            }
            quotaTimeProgress(window, nowSeconds)?.let { timeProgress ->
                Box(
                    Modifier
                        .padding(start = maxWidth * timeProgress)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.onSurfaceVariant)
                        .testTag("usage_time_marker_${provider}_${window.label}"),
                )
            }
        }
        if (reset != null || amount != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = reset.orEmpty(),
                    style = ScoutrType.monoMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = amount.orEmpty(),
                    style = ScoutrType.monoMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BalanceRow(window: UsageWindow) {
    val negative = window.amount?.let { it < 0.0 } == true
    val amount = formatAmount(window.amount, window.currency)
    val currency = window.currency ?: window.label
    val description = if (negative) {
        "Balance below zero, $currency, negative ${formatNegativeAmount(window.amount, window.currency)}"
    } else {
        "$currency balance, $amount"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("usage_balance_${window.label}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    // A balance titles itself the same way a meter does, so it
                    // takes the same mono-caps header slot.
                    text = (if (negative) "Balance below zero" else "Available balance").uppercase(Locale.US),
                    style = ScoutrType.monoSection,
                    color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    currency,
                    style = ScoutrType.monoMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = amount,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (negative) {
            Text(
                "Add credit before starting more work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun UsageLoading() {
    Text(
        "Loading usage…",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("usage_loading"),
    )
}

@Composable
private fun UsageError(message: String, onRefresh: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().testTag("usage_error"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh) { Text("Retry") }
        }
    }
}

@Composable
private fun UsageEmpty(onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp).testTag("usage_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("No usage data", style = MaterialTheme.typography.titleMedium)
        Text(
            "Connected providers have not reported any limits.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRefresh) { Text("Refresh") }
    }
}

internal fun windowTitle(label: String): String = when (label.lowercase()) {
    "5h" -> "5-hour limit"
    "7d" -> "7-day limit"
    "day" -> "Daily limit"
    "wk" -> "Weekly limit"
    "mo" -> "Monthly limit"
    "plan" -> "Plan limit"
    else -> label
}

/** Returns where the quota period currently is, or null when no time window is known. */
internal fun quotaTimeProgress(window: UsageWindow, nowSeconds: Long): Float? {
    val duration = window.windowSeconds?.takeIf { it > 0 } ?: return null
    val resetAt = window.resetAt ?: return null
    val periodStart = resetAt - duration
    return ((nowSeconds - periodStart).toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
}

/**
 * The countdown is mono metadata under the meter, and mono metadata is lowercase
 * across the system (`resets in 2d 6h`, §9c) — sentence case belongs to UI prose.
 */
internal fun resetLabel(resetAt: Long?, nowSeconds: Long): String? {
    if (resetAt == null) return null
    val remaining = (resetAt - nowSeconds).coerceAtLeast(0)
    if (remaining < 60) return "resets now"
    val minutes = remaining / 60
    if (minutes < 60) return "resets in ${minutes}m"
    val hours = minutes / 60
    val trailingMinutes = minutes % 60
    if (hours < 24) return "resets in ${hours}h" + if (trailingMinutes > 0) " ${trailingMinutes}m" else ""
    val days = hours / 24
    val trailingHours = hours % 24
    return "resets in ${days}d" + if (trailingHours > 0) " ${trailingHours}h" else ""
}

/**
 * DeepSeek bills by UTC clock: peak hours are 01:00–04:00 and 06:00–10:00 UTC,
 * all other hours off-peak at half price (api-docs.deepseek.com/quick_start/pricing,
 * effective 2026-08-16). The balance endpoint carries no time-of-day data, so the
 * tier is derived from the device clock and refreshes on the screen's minute tick.
 */
internal data class DeepseekPricing(val peak: Boolean, val label: String)

internal fun deepseekPricing(nowUtcSeconds: Long): DeepseekPricing {
    val hour = ((nowUtcSeconds % 86_400) / 3_600).toInt()
    val peak = hour in 1 until 4 || hour in 6 until 10
    val nextTransition = when {
        hour < 1 -> 1
        hour < 4 -> 4
        hour < 6 -> 6
        hour < 10 -> 10
        else -> 1
    }
    val nextHour = nextTransition.toString().padStart(2, '0')
    val transition = if (peak) "off-peak at $nextHour:00 UTC" else "peak at $nextHour:00 UTC"
    return DeepseekPricing(peak, (if (peak) "peak pricing" else "off-peak pricing") + " · $transition")
}

private fun updatedLabel(updatedAt: Long, nowMillis: Long): String? {
    if (updatedAt <= 0) return null
    val minutes = ((nowMillis - updatedAt).coerceAtLeast(0) / 60_000)
    return when {
        minutes == 0L -> "updated now"
        minutes < 60 -> "updated ${minutes}m ago"
        else -> "updated ${minutes / 60}h ago"
    }
}

private fun UsageWindow.isBalance(): Boolean =
    amount != null && limitAmount == null && windowSeconds == null && resetAt == null

private fun amountLabel(window: UsageWindow): String? {
    val amount = window.amount
    val limit = window.limitAmount
    return when {
        amount != null && limit != null -> "${formatAmount(amount, window.currency)} of ${formatAmount(limit, window.currency)}"
        amount != null -> formatAmount(amount, window.currency)
        limit != null -> "Limit ${formatAmount(limit, window.currency)}"
        else -> null
    }
}

internal fun formatNegativeAmount(amount: Double?, currencyCode: String?): String =
    formatAmount(amount?.let { -kotlin.math.abs(it) }, currencyCode).removePrefix("-").trim()

internal fun formatAmount(amount: Double?, currencyCode: String?, locale: Locale = Locale.getDefault()): String {
    if (amount == null) return "Unknown"
    val number = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(amount)
    if (currencyCode.isNullOrBlank()) return number
    return runCatching {
        NumberFormat.getCurrencyInstance(locale).apply { currency = Currency.getInstance(currencyCode) }.format(amount)
    }.getOrElse { "$number $currencyCode" }
}
