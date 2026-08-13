package dev.cockpit.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cockpit.app.data.UsageSnapshot
import dev.cockpit.app.data.UsageWindow
import dev.cockpit.app.state.UsageUiState
import dev.cockpit.app.state.UsageViewModel
import dev.cockpit.app.state.Loadable
import dev.cockpit.app.ui.components.PullRefreshIndicator
import dev.cockpit.app.ui.components.pullRefreshSemantics
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
    val ui by viewModel.ui.collectAsState()
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("usage_${provider.provider}"),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = provider.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                updatedLabel(provider.updatedAt, nowMillis)?.let { updated ->
                    Text(
                        text = updated,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            provider.error?.let { ProviderError(it, provider.windows.isNotEmpty(), onRefresh) }

            if (provider.windows.isEmpty() && provider.error == null) {
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
        else -> MaterialTheme.colorScheme.primary
    }
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
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = windowTitle(window.label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${percent.roundToInt()}% used",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1,
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
            trackColor = color.copy(alpha = 0.16f),
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        if (reset != null || amount != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = reset.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = amount.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
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
                    if (negative) "Balance below zero" else "Available balance",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    currency,
                    style = MaterialTheme.typography.labelSmall,
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
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Loading usage" }
            .testTag("usage_loading"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(2) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.width(96.dp).height(18.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                repeat(2) {
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }
    }
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

internal fun resetLabel(resetAt: Long?, nowSeconds: Long): String? {
    if (resetAt == null) return null
    val remaining = (resetAt - nowSeconds).coerceAtLeast(0)
    if (remaining < 60) return "Resets now"
    val minutes = remaining / 60
    if (minutes < 60) return "Resets in ${minutes}m"
    val hours = minutes / 60
    val trailingMinutes = minutes % 60
    if (hours < 24) return "Resets in ${hours}h" + if (trailingMinutes > 0) " ${trailingMinutes}m" else ""
    val days = hours / 24
    val trailingHours = hours % 24
    return "Resets in ${days}d" + if (trailingHours > 0) " ${trailingHours}h" else ""
}

private fun updatedLabel(updatedAt: Long, nowMillis: Long): String? {
    if (updatedAt <= 0) return null
    val minutes = ((nowMillis - updatedAt).coerceAtLeast(0) / 60_000)
    return when {
        minutes == 0L -> "Updated now"
        minutes < 60 -> "Updated ${minutes}m ago"
        else -> "Updated ${minutes / 60}h ago"
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
