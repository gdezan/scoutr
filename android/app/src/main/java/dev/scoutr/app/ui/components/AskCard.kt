package dev.scoutr.app.ui.components

import dev.scoutr.app.ui.theme.ScoutrRadii
import dev.scoutr.app.ui.theme.ScoutrBorder
import dev.scoutr.app.ui.theme.ScoutrSpace
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.scoutr.app.data.QuestionEntry
import dev.scoutr.app.state.AskDraft
import dev.scoutr.app.state.DraftAnswer

/**
 * One ask_user_question round, as a single card.
 *
 * A round can hold up to four questions, and the agents' TUIs keep them all on
 * one screen until a final submit — so the card does too. The user moves
 * between questions with the footer (or the chip row) and every answer stays
 * editable until Submit sends the whole round at once. Nothing reaches the
 * agent before that, which is what makes going back and changing an answer
 * possible at all.
 *
 * A lone question drops the chrome entirely: no chips, no footer, no Submit —
 * picking an option answers it, exactly as before.
 */
@Composable
fun AskCard(
    group: List<QuestionEntry>,
    draft: AskDraft,
    submitting: Boolean,
    submitIsSlow: Boolean,
    error: String?,
    onAnswer: (questionId: String, answer: DraftAnswer) -> Unit,
    onPage: (page: Int) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (group.isEmpty()) return
    val accent = MaterialTheme.colorScheme.error
    val page = draft.page.coerceIn(0, group.lastIndex)
    val question = group[page]
    val single = group.size == 1
    val complete = draft.isComplete(group)
    val callId = group.first().callId

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(ScoutrRadii.md),
        border = BorderStroke(ScoutrBorder.hairline, accent.copy(alpha = 0.6f)),
        modifier = modifier.fillMaxWidth().testTag("ask_card_$callId"),
    ) {
        Row(Modifier.padding(14.dp)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accent, RoundedCornerShape(2.dp)),
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                if (!single) {
                    QuestionChips(group, draft, page, enabled = !submitting, onPage = onPage)
                    Spacer(Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = question.header.ifBlank { "Question" }.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (submitting) {
                        Text(
                            if (submitIsSlow) "No response yet" else "Sending…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // Dismiss is hidden only while the keystrokes are actually
                    // in flight. Once the round has gone quiet it comes back:
                    // a card that never resolves would otherwise hold the
                    // composer shut for the rest of the session.
                    if (!submitting || submitIsSlow) {
                        if (submitIsSlow) Spacer(Modifier.width(ScoutrSpace.sm))
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("ask_dismiss_$callId"),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Dismiss")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = question.question,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))

                QuestionBody(
                    question = question,
                    answer = draft.answerFor(question.id),
                    enabled = !submitting,
                    // A lone single-select still answers in one tap: the round
                    // is complete the moment the option is picked, so there is
                    // nothing left for a Submit button to do.
                    submitOnPick = single && !question.multiSelect,
                    onAnswer = { onAnswer(question.id, it) },
                    onSubmit = onSubmit,
                )

                if (!single) {
                    Spacer(Modifier.height(10.dp))
                    AskFooter(
                        page = page,
                        count = group.size,
                        enabled = !submitting,
                        complete = complete,
                        callId = callId,
                        onPage = onPage,
                        onSubmit = onSubmit,
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(ScoutrSpace.sm))
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("ask_error_$callId"),
                    )
                }
            }
        }
    }
}

/**
 * The status row: which questions are answered, and which one is showing.
 * Tapping a chip jumps to it — the round has at most four questions, so the
 * row never needs to overflow, though it scrolls if the headers are long.
 */
@Composable
private fun QuestionChips(
    group: List<QuestionEntry>,
    draft: AskDraft,
    page: Int,
    enabled: Boolean,
    onPage: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        group.forEachIndexed { index, question ->
            val answered = draft.answerFor(question.id).isAnswered
            val current = index == page
            val background = when {
                current -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(ScoutrRadii.lg))
                    .background(background)
                    .clickable(enabled = enabled) { onPage(index) }
                    .padding(horizontal = ScoutrSpace.sm, vertical = 4.dp)
                    .testTag("ask_chip_${question.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (answered) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(ScoutrSpace.md),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    question.header.ifBlank { "Q${index + 1}" },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (current) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * Back / progress / Next, with Next becoming Submit on the last question —
 * one button slot, mirroring the TUI's trailing submit tab. Submit stays
 * disabled until every question has an answer, because the review tab will not
 * accept an incomplete round.
 */
@Composable
private fun AskFooter(
    page: Int,
    count: Int,
    enabled: Boolean,
    complete: Boolean,
    callId: String,
    onPage: (Int) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { onPage(page - 1) },
            enabled = enabled && page > 0,
            modifier = Modifier.testTag("ask_back_$callId"),
        ) {
            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.size(ScoutrSpace.lg))
            Text("Back")
        }
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(count) { index ->
                Box(
                    Modifier
                        .size(6.dp)
                        .background(
                            if (index == page) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            RoundedCornerShape(3.dp),
                        ),
                )
            }
        }
        if (page == count - 1) {
            OutlinedButton(
                shape = MaterialTheme.shapes.small,
                onClick = onSubmit,
                enabled = enabled && complete,
                modifier = Modifier.testTag("ask_submit_$callId"),
            ) {
                Text("Submit")
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(ScoutrSpace.lg))
            }
        } else {
            TextButton(
                onClick = { onPage(page + 1) },
                enabled = enabled,
                modifier = Modifier.testTag("ask_next_$callId"),
            ) {
                Text("Next")
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(ScoutrSpace.lg))
            }
        }
    }
}

/**
 * One question's controls. Four shapes, all derived from the ask itself:
 * multi-select toggles, a two-option confirmation, a free-text field, or a
 * single-choice list. Each also offers a typed answer — and picking an option
 * clears the text (and vice versa), because "Type something" is an entry in
 * the option list, not a field beside it.
 */
@Composable
private fun QuestionBody(
    question: QuestionEntry,
    answer: DraftAnswer,
    enabled: Boolean,
    submitOnPick: Boolean,
    onAnswer: (DraftAnswer) -> Unit,
    onSubmit: () -> Unit,
) {
    var typing by rememberSaveable(question.id) { mutableStateOf(false) }
    val showTyping = typing || (answer.text.isNotEmpty() && answer.labels.isEmpty())

    fun pick(labels: List<String>) {
        typing = false
        onAnswer(DraftAnswer(labels = labels))
        if (submitOnPick) onSubmit()
    }

    when {
        question.multiSelect -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            question.options.forEach { option ->
                val checked = option.label in answer.labels
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ScoutrRadii.md))
                        .clickable(enabled = enabled) {
                            pick(if (checked) answer.labels - option.label else answer.labels + option.label)
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("ask_option_${question.id}_${option.label}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                    Text(option.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            TypeSomething(enabled) { typing = true }
        }

        question.options.isEmpty() -> FreeTextAnswer(question.id, answer, enabled, onAnswer)

        question.options.size == 2 -> Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ScoutrSpace.sm)) {
                question.options.forEach { option ->
                    val chosen = option.label in answer.labels
                    OutlinedButton(
                        shape = MaterialTheme.shapes.small,
                        onClick = { pick(listOf(option.label)) },
                        enabled = enabled,
                        border = if (chosen) {
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ask_option_${question.id}_${option.label}"),
                    ) { Text(option.label) }
                }
            }
            TypeSomething(enabled) { typing = true }
        }

        else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            question.options.forEach { option ->
                val chosen = option.label in answer.labels
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ScoutrRadii.md))
                        .clickable(enabled = enabled) { pick(listOf(option.label)) }
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                        .testTag("ask_option_${question.id}_${option.label}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .background(
                                if (chosen) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                RoundedCornerShape(7.dp),
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        option.description.takeIf { it.isNotBlank() }?.let { description ->
                            Text(
                                description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            TypeSomething(enabled) { typing = true }
        }
    }

    if (showTyping && question.options.isNotEmpty()) {
        Spacer(Modifier.height(ScoutrSpace.sm))
        FreeTextAnswer(question.id, answer, enabled, onAnswer)
    }
}

@Composable
private fun TypeSomething(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("Type something")
    }
}

/**
 * A typed answer. The text lives in the draft, not in the composable, so it
 * survives paging away to another question and back.
 */
@Composable
private fun FreeTextAnswer(
    questionId: String,
    answer: DraftAnswer,
    enabled: Boolean,
    onAnswer: (DraftAnswer) -> Unit,
) {
    OutlinedTextField(
        value = answer.text,
        // Typing clears any pick: the questionnaire cannot carry both.
        onValueChange = { onAnswer(DraftAnswer(text = it)) },
        enabled = enabled,
        placeholder = { Text("Type your answer") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("ask_input_$questionId"),
    )
}

/**
 * The round's answers once its toolResult lands: one bubble for one ask, since
 * one ask was one interaction. Derived from the transcript on every poll, so
 * it survives reloads.
 */
@Composable
fun AskAnswerBubble(
    group: List<QuestionEntry>,
    modifier: Modifier = Modifier,
) {
    val answered = remember(group) { group.filter { it.answered } }
    if (answered.isEmpty()) return
    val single = answered.size == 1
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier
                .padding(end = 4.dp)
                .widthIn(max = 288.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(ScoutrRadii.md))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("ask_answer_${group.first().callId}"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            answered.forEach { question ->
                val summary = when {
                    question.selected.isNotEmpty() -> question.selected.joinToString(", ")
                    !question.answerText.isNullOrBlank() -> question.answerText!!
                    else -> return@forEach
                }
                if (single) {
                    Text(summary, color = MaterialTheme.colorScheme.onSurface)
                } else {
                    Row {
                        Text(
                            question.header.ifBlank { question.question }.plus("  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
