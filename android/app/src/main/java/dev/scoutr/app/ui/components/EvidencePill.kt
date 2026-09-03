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

/**
 * Shared evidence aggregation for C1. Pill is the only per-assistant affordance;
 * sheet owns the file + bash + collapsed reads detail.
 *
 * Invariants:
 * - Pill visible iff [calls] > 0 (Q7). Stat sums whole edit even when diff capped.
 * - Radii: pill 6dp rsm ([ScoutrRadii.sm]), sheet 12dp rlg ([ScoutrRadii.lg]),
 *   diff inner 4dp [MaterialTheme.shapes.extraSmall] with 8dp [ScoutrSpace.sm]
 *   outer margin → 12 = 4 + 8 concentric.
 * - Mono is [ScoutrType.monoTool] 10sp (repo's monoTool is 10sp, not 11sp).
 */
data class EvidenceSummary(
    val fileCount: Int,
    val added: Int,
    val removed: Int,
    val edits: List<ContentBlock>,
    val bash: List<SessionEntry>,
    val readCount: Int,
    val calls: Int,
    val durationMs: Long? = null,
) {
    /** Pill visibility invariant: hide when no evidence. */
    val hasEvidence: Boolean get() = calls > 0
}

/**
 * Pure aggregation helper. Callers (ChatScreen) may compute live while
 * `working`; stat never understates because [DiffStatBadge] counts whole edit.
 *
 * - file edits: any toolResult [ContentBlock] with type fileEdit + non-blank path
 * - bash: toolResult where [SessionEntry.toolName] == "bash" (case-insensitive)
 * - reads: toolResult where toolName contains "read" (case-insensitive) and not already counted as an edit
 */
fun evidenceSummaryForEntries(
    entries: List<SessionEntry>,
    durationMs: Long? = null,
): EvidenceSummary {
    val edits = entries
        .filter { it.role == "toolResult" }
        .mapNotNull { entry -> entry.content.firstOrNull { it.type == "fileEdit" && !it.path.isNullOrBlank() } }

    val added = edits.sumOf { it.added }
    val removed = edits.sumOf { it.removed }
    // Distinct files when paths differ; fallback to edit count.
    val distinctPaths = edits.mapNotNull { it.path?.takeIf { p -> p.isNotBlank() } }.distinct()
    val fileCount = if (distinctPaths.isNotEmpty()) distinctPaths.size else edits.size

    val bash = entries.filter { it.role == "toolResult" && it.toolName?.equals("bash", ignoreCase = true) == true }
    // Reads collapsed into one call; don't double-count edit entries.
    val readEntries = entries.filter { entry ->
        entry.role == "toolResult" &&
            entry.toolName?.contains("read", ignoreCase = true) == true &&
            entry.content.none { it.type == "fileEdit" && !it.path.isNullOrBlank() }
    }
    val readCount = readEntries.size
    val calls = edits.size + bash.size + if (readCount > 0) 1 else 0

    return EvidenceSummary(
        fileCount = fileCount,
        added = added,
        removed = removed,
        edits = edits,
        bash = bash,
        readCount = readCount,
        calls = calls,
        durationMs = durationMs,
    )
}

/**
 * One pill per assistant entry. Label: `Evidence · N files · +A −R`
 * where +A in [DiffPalette.Added] and −R in [DiffPalette.Deleted],
 * rest in onSurfaceVariant. 6dp rsm, surfaceContainer, 1dp hairline
 * outline, monoTool, min 44dp touch target.
 */
@Composable
fun EvidencePill(
    fileCount: Int,
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
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        append("Evidence · $fileCount files · ")
                    }
                    withStyle(SpanStyle(color = DiffPalette.Added)) { append("+$added") }
                    append(" ")
                    withStyle(SpanStyle(color = DiffPalette.Deleted)) { append("−$removed") }
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
 * - Header "Evidence" + "N calls · Xs".
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
                val headerMeta = if (durationLabel != null) "${summary.calls} calls · $durationLabel" else "${summary.calls} calls"
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

            // Bash rows
            summary.bash.forEachIndexed { idx, entry ->
                val isError = entry.isError == true
                // Never auto-expanded: bash rows are not expandable, and error never expands.
                val hasDivider = summary.edits.isNotEmpty() || idx > 0
                if (hasDivider) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = ScoutrBorder.hairline)
                }
                EvidenceRow(
                    onClick = {},
                    isError = isError,
                    modifier = Modifier.testTag("evidence_row"),
                ) {
                    if (isError) {
                        Text(
                            "▸ bash (error)",
                            style = ScoutrType.monoTool,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            "bash",
                            style = ScoutrType.monoTool,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        // In live, timing would be shown; we show check when not error.
                        Text(
                            "✓",
                            style = ScoutrType.monoTool,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        // Duration per bash not available in SessionEntry; aggregate
                        // duration shown in header. Keep row quiet.
                    }
                }
                // For error entries, optionally show truncated output capped.
                if (isError) {
                    val output = entry.content.firstOrNull { it.text != null }?.text?.trim().orEmpty()
                    if (output.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ScoutrSpace.sm, vertical = ScoutrSpace.xs)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(ScoutrSpace.sm),
                        ) {
                            Text(
                                output.take(200),
                                style = ScoutrType.monoCode(toolOutputFontSizeSp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // Collapsed reads row
            if (summary.readCount > 0) {
                val needDivider = summary.edits.isNotEmpty() || summary.bash.isNotEmpty()
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

            // Empty invariant: pill never shown when calls==0, but sheet could be
            // opened programmatically with empty summary — show nothing but header.
            Spacer(Modifier.height(ScoutrSpace.lg))
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
