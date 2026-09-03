package dev.scoutr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.scoutr.app.data.ContentBlock
import dev.scoutr.app.data.SessionEntry
import dev.scoutr.app.ui.theme.DiffPalette
import dev.scoutr.app.ui.theme.ScoutrBorder
import dev.scoutr.app.ui.theme.ScoutrRadii
import dev.scoutr.app.ui.theme.ScoutrSpace
import dev.scoutr.app.ui.theme.ScoutrType
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared evidence aggregation. Pill is the one affordance per assistant run;
 * sheet owns the file + command + collapsed reads detail.
 *
 * One assistant run = the maximal run of assistant + toolResult entries between
 * user entries, so every command between thinking blocks lands in the same block.
 *
 * Invariants:
 * - Pill visible iff fileCount>0 || commands>0; each zero part hidden, so a
 *   commands-only turn reads `2 commands` with no file stat.
 * - Stat sums whole edit even when diff capped.
 * - Radii: pill 6dp rsm ([ScoutrRadii.sm]), sheet 12dp rlg ([ScoutrRadii.lg]),
 *   diff inner 4dp [MaterialTheme.shapes.extraSmall] with 8dp [ScoutrSpace.sm]
 *   outer margin → 12 = 4 + 8 concentric.
 * - Mono is [ScoutrType.monoTool] 10sp (repo's monoTool is 10sp, not 11sp).
 */
/** One bash execution: the call carrying the command plus its result carrying output. */
data class BashRun(
    val call: ContentBlock?,
    val result: SessionEntry,
)

data class EvidenceSummary(
    val fileCount: Int,
    val added: Int,
    val removed: Int,
    val edits: List<ContentBlock>,
    val bashRuns: List<BashRun>,
    val readCount: Int,
    val pendingCalls: List<ContentBlock> = emptyList(),
    val durationMs: Long? = null,
) {
    /** Commands counter: one per bash execution, plus streaming calls awaiting results. */
    val commands: Int get() = bashRuns.size + pendingCalls.size
    /** Pill visibility invariant: hide when no file edits or commands. */
    val hasEvidence: Boolean get() = fileCount > 0 || commands > 0
}

/** Pair each bash result with its call so the sheet can show what was run.
 *
 * Explicit [SessionEntry.toolCallId] wins; leftovers pair in transcript order.
 * Calls without a result yet stay in [EvidenceSummary.pendingCalls], never here.
 */
fun pairBashRuns(calls: List<ContentBlock>, results: List<SessionEntry>): List<BashRun> {
    val unmatched = calls.toMutableList()
    val paired = arrayOfNulls<ContentBlock>(results.size)
    // Pass 1: explicit toolCallId matches win over transcript order.
    results.forEachIndexed { index, result ->
        val explicitId = result.toolCallId?.takeUnless { it.isBlank() } ?: return@forEachIndexed
        val match = unmatched.indexOfFirst { it.id == explicitId }
        if (match >= 0) paired[index] = unmatched.removeAt(match)
    }
    // Pass 2: leftovers pair in transcript order.
    results.forEachIndexed { index, result ->
        if (paired[index] == null && unmatched.isNotEmpty()) paired[index] = unmatched.removeAt(0)
    }
    return results.mapIndexed { index, result -> BashRun(call = paired[index], result = result) }
}

/** Short command summary for a bash call: the `command` argument, else the tool name. */
internal fun bashCommandSummary(call: ContentBlock?): String {
    val args = call?.arguments
    val command = args?.get("command")
    if (command is JsonPrimitive && command.isString && command.content.isNotBlank()) return command.content
    val filePath = args?.get("file_path")
    if (filePath is JsonPrimitive && filePath.isString && filePath.content.isNotBlank()) return filePath.content
    return call?.name ?: "bash"
}

/** Full output of a bash result across every text block it carries. */
internal fun bashOutputText(entry: SessionEntry): String =
    entry.content.mapNotNull { it.text?.takeIf(String::isNotBlank) }.joinToString("\n").trim()

internal fun filesLabel(fileCount: Int): String =
    if (fileCount == 1) "1 file" else "$fileCount files"

internal fun commandsLabel(commands: Int): String =
    if (commands == 1) "1 command" else "$commands commands"

/**
 * One pill per assistant run. Label hides zero parts, no `Evidence` prefix:
 * `2 files · 3 commands · +A −R`, `2 files · +A −R`, or `3 commands`.
 * where +A in [DiffPalette.Added] and −R in [DiffPalette.Deleted],
 * rest in onSurfaceVariant. 6dp rsm, surfaceContainer, 1dp hairline
 * outline, monoTool, min 44dp touch target.
 */
@Composable
fun EvidencePill(
    fileCount: Int,
    commands: Int,
    added: Int,
    removed: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(ScoutrRadii.sm)
    // Spec: surfaceContainer, 1dp hairline border outline, monoTool.
    // Min 44dp height for accessibility (testTag evidence_pill).
    // Use PressTintSurface so press is 90ms tint via ScoutrMotion, not ripple.
    PressTintSurface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        pressedColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .heightIn(min = 44.dp)
            .testTag("evidence_pill")
            .border(ScoutrBorder.hairline, MaterialTheme.colorScheme.outline, shape),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = ScoutrSpace.md, vertical = ScoutrSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                buildAnnotatedString {
                    val parts = buildList {
                        if (fileCount > 0) add(filesLabel(fileCount))
                        if (commands > 0) add(commandsLabel(commands))
                    }
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        append(parts.joinToString(" · "))
                    }
                    if (fileCount > 0) {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" · ") }
                        withStyle(SpanStyle(color = DiffPalette.Added)) { append("+$added") }
                        append(" ")
                        withStyle(SpanStyle(color = DiffPalette.Deleted)) { append("−$removed") }
                    }
                },
                style = ScoutrType.monoTool,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Modal sheet owning file edit rows, bash rows, and collapsed reads.
 *
 * - ModalBottomSheet with skipPartiallyExpanded=true, handle 32×4 outlineVariant,
 *   outer 12dp rlg, surface.
 * - Header "Evidence" + "2 files · 3 commands · Xs" (zero parts hidden).
 * - Command rows expand to the full command plus capped output; error rows stay red.
 * - Rows 44dp, PressTintSurface, border-top hairline dividers, pressed surfaceContainerHigh.
 * - Motion via ScoutrMotion.sheetSpec spring 0.78/380, fade 140ms, respects LocalReduceMotion.
 * - Diff tile under expanded edit: fileEditDisplayPath + "+A −R · wrap off",
 *   capped ≤6 lines + "⋯ +N more", horizontal no-wrap, truncated notice,
 *   deep-link via onOpenReview(cwd) when cwd non-blank.
 * - Empty/error: edit.path blank fallback, added/removed null→0 (ContentBlock defaults 0),
 *   cwd blank hides deep-link, isError uses errorContainer red, never auto-expanded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvidenceSheet(
    summary: EvidenceSummary,
    toolOutputFontSizeSp: Float,
    onDismiss: () -> Unit,
    onOpenReview: ((String) -> Unit)?,
    cwd: String?,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(ScoutrRadii.lg),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
            )
        },
        modifier = Modifier.testTag("evidence_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = ScoutrSpace.lg),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScoutrSpace.lg, vertical = ScoutrSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Evidence",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                val durationLabel = summary.durationMs?.let { ms ->
                    val secs = ms / 1000.0
                    if (secs < 10) String.format("%.1fs", secs) else String.format("%.0fs", secs)
                }
                val headerMeta = buildList {
                    if (summary.fileCount > 0) add(filesLabel(summary.fileCount))
                    if (summary.commands > 0) add(commandsLabel(summary.commands))
                    if (durationLabel != null) add(durationLabel)
                }.joinToString(" · ")
                Text(
                    headerMeta,
                    style = ScoutrType.monoMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Track expanded edits by index/path. Error rows never auto-expanded
            // and user toggle is blocked for isError (we don't have per-edit isError
            // from ContentBlock alone, so gate on caller-supplied error via summary
            // is approximated: edits themselves are not errors; bash error path is separate).
            var expandedEdits by remember { mutableStateOf(setOf<Int>()) }
            var expandedRuns by remember { mutableStateOf(setOf<Int>()) }
            var readsExpanded by rememberSaveable { mutableStateOf(false) }

            // Edit rows
            summary.edits.forEachIndexed { index, edit ->
                val isExpanded = index in expandedEdits
                // Duplicate 2-segment logic: last two path segments with ellipsis.
                val fileName = evidenceFileName(edit)
                val displayPath = evidenceDisplayPath(edit)
                val canOpenReview = !cwd.isNullOrBlank() && onOpenReview != null
                // Divider before each row (hairline) except first header already did.
                if (index > 0 || summary.edits.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = ScoutrBorder.hairline)
                }
                EvidenceRow(
                    onClick = {
                        expandedEdits = if (isExpanded) expandedEdits - index else expandedEdits + index
                    },
                    isError = false,
                    modifier = Modifier.testTag("evidence_row"),
                ) {
                    Text(
                        fileName.ifEmpty { "untitled" },
                        style = ScoutrType.monoTool,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(ScoutrSpace.sm))
                    EvidenceDiffStatBadge(edit)
                    Spacer(Modifier.width(ScoutrSpace.sm))
                    androidx.compose.material3.Icon(
                        imageVector = if (isExpanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse $fileName" else "Expand $fileName",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (isExpanded) {
                    // Diff tile: surfaceVariant 4dp (extraSmall) with 8dp md outer margin concentric (12=4+8)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ScoutrSpace.sm, vertical = ScoutrSpace.xs)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(ScoutrBorder.hairline, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
                            .padding(ScoutrSpace.sm)
                            .testTag("file_edit_diff"),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                displayPath.ifEmpty { fileName.ifEmpty { "untitled" } },
                                style = ScoutrType.monoCaption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(ScoutrSpace.sm))
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = DiffPalette.Added)) { append("+${edit.added}") }
                                    append(" ")
                                    withStyle(SpanStyle(color = DiffPalette.Deleted)) { append("−${edit.removed}") }
                                    append(" · wrap off")
                                },
                                style = ScoutrType.monoCaption,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        // Build lines from hunks
                        val allLines = buildList<String> {
                            edit.hunks.forEachIndexed { hunkIndex, hunk ->
                                if (hunkIndex > 0) add("⋯")
                                hunk.header?.let(::add)
                                addAll(hunk.lines)
                            }
                        }
                        val capped = if (allLines.size > 6) allLines.take(6) else allLines
                        val remaining = allLines.size - capped.size
                        if (capped.isNotEmpty()) {
                            // Horizontal no-wrap: DiffLines wraps=false would scroll.
                            // To keep component self-contained without TextMate, render
                            // with horizontalScroll and monoCode; syntax highlight is
                            // optional — the contract is 6-line cap + no-wrap.
                            // Try to use DiffLines if available; fallback to plain.
                            EvidenceDiffLines(
                                lines = capped,
                                toolOutputFontSizeSp = toolOutputFontSizeSp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (remaining > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "⋯ +$remaining more",
                                style = ScoutrType.monoCaption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (edit.truncated) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "⋯ diff truncated",
                                style = ScoutrType.monoCaption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (canOpenReview) {
                            Spacer(Modifier.height(6.dp))
                            androidx.compose.material3.TextButton(
                                onClick = { onOpenReview?.invoke(cwd!!) },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text("Open in Review", style = ScoutrType.monoTool)
                            }
                        }
                    }
                }
            }

            // Command rows: one per bash execution, tap to inspect what was run.
            summary.bashRuns.forEachIndexed { idx, run ->
                val entry = run.result
                val isError = entry.isError == true
                val isExpanded = idx in expandedRuns
                val command = bashCommandSummary(run.call)
                val output = bashOutputText(entry)
                val hasDivider = summary.edits.isNotEmpty() || idx > 0
                if (hasDivider) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = ScoutrBorder.hairline)
                }
                EvidenceRow(
                    onClick = { expandedRuns = if (isExpanded) expandedRuns - idx else expandedRuns + idx },
                    isError = isError,
                    modifier = Modifier.testTag("evidence_row"),
                ) {
                    Text(
                        if (isError) "▸ $command (error)" else command,
                        style = ScoutrType.monoTool,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!isError) {
                        Text(
                            "✓",
                            style = ScoutrType.monoTool,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(ScoutrSpace.sm))
                    androidx.compose.material3.Icon(
                        imageVector = if (isExpanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse command" else "Expand command",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                // Errors surface their output unasked; clean runs show it expanded.
                if (isExpanded || isError) {
                    CommandDetail(
                        command = command,
                        output = output,
                        toolOutputFontSizeSp = toolOutputFontSizeSp,
                    )
                }
            }
            // Streaming calls: the command is known, its result has not arrived yet.
            summary.pendingCalls.forEachIndexed { pendingIdx, call ->
                val key = summary.bashRuns.size + pendingIdx
                val isExpanded = key in expandedRuns
                val command = bashCommandSummary(call)
                val hasDivider = summary.edits.isNotEmpty() || summary.bashRuns.isNotEmpty() || pendingIdx > 0
                if (hasDivider) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = ScoutrBorder.hairline)
                }
                EvidenceRow(
                    onClick = { expandedRuns = if (isExpanded) expandedRuns - key else expandedRuns + key },
                    isError = false,
                    modifier = Modifier.testTag("evidence_row"),
                ) {
                    Text(
                        command,
                        style = ScoutrType.monoTool,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "···",
                        style = ScoutrType.monoTool,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(ScoutrSpace.sm))
                    androidx.compose.material3.Icon(
                        imageVector = if (isExpanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse command" else "Expand command",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (isExpanded) {
                    CommandDetail(
                        command = command,
                        output = "",
                        toolOutputFontSizeSp = toolOutputFontSizeSp,
                        showRunning = true,
                    )
                }
            }

            // Collapsed reads row
            if (summary.readCount > 0) {
                val needDivider = summary.edits.isNotEmpty() || summary.bashRuns.isNotEmpty() || summary.pendingCalls.isNotEmpty()
                if (needDivider) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = ScoutrBorder.hairline)
                }
                EvidenceRow(
                    onClick = { readsExpanded = !readsExpanded },
                    isError = false,
                    modifier = Modifier.testTag("evidence_row"),
                ) {
                    val label = if (summary.readCount == 1) "1 read · tap" else "${summary.readCount} reads · tap"
                    Text(
                        if (readsExpanded) "${summary.readCount} reads" else label,
                        style = ScoutrType.monoTool,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Icon(
                        imageVector = if (readsExpanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (readsExpanded) "Collapse reads" else "Expand reads",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (readsExpanded) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ScoutrSpace.sm, vertical = ScoutrSpace.xs)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(ScoutrSpace.sm)
                            .testTag("file_edit_diff"),
                    ) {
                        Text(
                            "${summary.readCount} file reads collapsed",
                            style = ScoutrType.monoCaption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Empty invariant: pill never shown when files==0 && commands==0, but the sheet could be
            // opened programmatically with empty summary — show nothing but header.
            Spacer(Modifier.height(ScoutrSpace.lg))
        }
    }
}

/**
 * Expanded command detail: the full command (no-wrap scroll, selectable) plus
 * capped output. Same 4dp tile + 8dp margin concentric contract as the diff tile.
 */
@Composable
private fun CommandDetail(
    command: String,
    output: String,
    toolOutputFontSizeSp: Float,
    showRunning: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val outputLines = output.lines()
    val capped = if (outputLines.size > 6) outputLines.take(6) else outputLines
    val remaining = outputLines.size - capped.size
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScoutrSpace.sm, vertical = ScoutrSpace.xs)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(ScoutrBorder.hairline, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
            .padding(ScoutrSpace.sm)
            .testTag("command_detail"),
    ) {
        SelectionContainer(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            Text(
                command.ifBlank { "bash" },
                style = ScoutrType.monoCode(toolOutputFontSizeSp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (output.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                capped.forEach { line ->
                    Text(
                        line,
                        style = ScoutrType.monoCode(toolOutputFontSizeSp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            if (remaining > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "⋯ +$remaining more",
                    style = ScoutrType.monoCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (showRunning) {
            Spacer(Modifier.height(4.dp))
            Text(
                "running…",
                style = ScoutrType.monoCaption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EvidenceRow(
    onClick: () -> Unit,
    isError: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val bg = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
    val pressed = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    PressTintSurface(
        onClick = onClick,
        shape = RectangleShape,
        color = bg,
        pressedColor = pressed,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = ScoutrSpace.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * +A −R badge for edit stat. Counts whole edit even when diff capped.
 */
@Composable
private fun EvidenceDiffStatBadge(edit: ContentBlock, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = DiffPalette.Added)) { append("+${edit.added}") }
            append(" ")
            withStyle(SpanStyle(color = DiffPalette.Deleted)) { append("−${edit.removed}") }
        },
        style = ScoutrType.monoTool,
        maxLines = 1,
        modifier = modifier.testTag("diff_stat_badge"),
    )
}

/** Duplicate of ChatScreen's fileEditFileName (2-segment fallback lives in display). */
private fun evidenceFileName(block: ContentBlock): String =
    block.path.orEmpty().substringAfterLast('/').ifEmpty { block.path.orEmpty() }

/** Duplicate of ChatScreen's 2-segment displayPath logic. */
private fun evidenceDisplayPath(block: ContentBlock): String {
    val path = block.path.orEmpty()
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.size <= 2) return path
    return "…/${segments.takeLast(2).joinToString("/")}"
}

/**
 * Capped diff lines with horizontal no-wrap. This is a lightweight
 * replacement for [dev.scoutr.app.ui.screens.DiffLines] that keeps the
 * file self-contained (no TextMate dependency) while preserving the
 * contract: monoCode, no wrap (horizontalScroll), 6-line cap handled
 * by caller, truncated notice outside.
 */
@Composable
private fun EvidenceDiffLines(
    lines: List<String>,
    toolOutputFontSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier.horizontalScroll(scroll),
    ) {
        lines.forEach { raw ->
            val isAdded = raw.startsWith("+") && !raw.startsWith("+++")
            val isRemoved = raw.startsWith("-") && !raw.startsWith("---")
            val isHunk = raw.startsWith("@@")
            val color = when {
                isAdded -> DiffPalette.Added
                isRemoved -> DiffPalette.Deleted
                isHunk -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            }
            val bg = when {
                isAdded -> DiffPalette.AddedBackground
                isRemoved -> DiffPalette.DeletedBackground
                else -> androidx.compose.ui.graphics.Color.Transparent
            }
            Row(
                modifier = Modifier
                    .background(bg)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    raw,
                    style = ScoutrType.monoCode(toolOutputFontSizeSp),
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
