package dev.scoutr.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.scoutr.app.data.QuestionEntry

/**
 * Native structured question card. Four shapes, all derived from the session's
 * ask_user_question tool call — never from terminal text:
 *
 *  - single-choice: options.size > 2 (tap an option to answer)
 *  - confirmation: options.size == 2 (two prominent buttons)
 *  - multi-select: multiSelect (toggle rows, then Submit)
 *  - free-text: options.isEmpty() (typed answer)
 *
 * Every option-based card also offers a "type something" free-text path, and
 * the answered state recovers from the toolResult on the next poll.
 */
@Composable
fun QuestionCard(
    question: QuestionEntry,
    sending: Boolean,
    onAnswer: (text: String, selectedLabels: List<String>) -> Unit,
    modifier: Modifier = Modifier,
    /** When this question is part of a multi-question ask, its 1-based position. */
    position: Pair<Int, Int>? = null,
) {
    // Answered questions never render as a card — the answer shows as a
    // user bubble so the card is dismissed and the answer lands in the
    // transcript (recovered from the toolResult on the next poll).
    if (question.answered) {
        QuestionAnswerBubble(question, modifier)
        return
    }
    val accent = MaterialTheme.colorScheme.primary
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
        modifier = modifier.fillMaxWidth().testTag("question_card_${question.id}"),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (position != null) {
                    Text(
                        text = "Question ${position.first} of ${position.second}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = question.header.ifBlank { "Question" }.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(14.dp).height(14.dp),
                        strokeWidth = 2.dp,
                    )
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

            when {
                question.multiSelect -> MultiSelectBody(question, sending, onAnswer)
                question.options.size == 2 -> ConfirmBody(question, sending, onAnswer)
                question.options.isEmpty() -> FreeTextBody(question, sending, onAnswer)
                else -> SingleChoiceBody(question, sending, onAnswer)
            }
        }
    }
}


@Composable
private fun SingleChoiceBody(
    question: QuestionEntry,
    sending: Boolean,
    onAnswer: (text: String, selectedLabels: List<String>) -> Unit,
) {
    var typingSomething by rememberSaveable(question.id) { mutableStateOf(false) }
    OptionList(
        question = question,
        sending = sending,
        onPick = { onAnswer(it, listOf(it)) },
        onTypeSomething = { typingSomething = true },
    )
    if (typingSomething) {
        Spacer(Modifier.height(8.dp))
        FreeTextInput(question, sending, onAnswer)
    }
}

@Composable
private fun ConfirmBody(
    question: QuestionEntry,
    sending: Boolean,
    onAnswer: (text: String, selectedLabels: List<String>) -> Unit,
) {
    var typingSomething by rememberSaveable(question.id) { mutableStateOf(false) }
    val labels = question.options.map { it.label }
    val first = labels.first()
    val second = labels.getOrElse(1) { "No" }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onAnswer(first, listOf(first)) },
            enabled = !sending,
            modifier = Modifier.weight(1f).testTag("question_confirm_${question.id}"),
        ) { Text(first) }
        OutlinedButton(
            onClick = { onAnswer(second, listOf(second)) },
            enabled = !sending,
            modifier = Modifier.weight(1f),
        ) { Text(second) }
    }
    TextButton(
        onClick = { typingSomething = true },
        enabled = !sending,
        modifier = Modifier.testTag("question_type_something_${question.id}"),
    ) {
        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.width(14.dp).height(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("Type something")
    }
    if (typingSomething) {
        Spacer(Modifier.height(4.dp))
        FreeTextInput(question, sending, onAnswer)
    }
}

@Composable
private fun MultiSelectBody(
    question: QuestionEntry,
    sending: Boolean,
    onAnswer: (text: String, selectedLabels: List<String>) -> Unit,
) {
    var selected by rememberSaveable(question.id) { mutableStateOf(emptySet<String>()) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        question.options.forEach { option ->
            val checked = option.label in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !sending) {
                        selected = if (checked) selected - option.label else selected + option.label
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(
                    option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = { onAnswer(selected.toList().joinToString(", "), selected.toList()) },
            enabled = !sending && selected.isNotEmpty(),
            modifier = Modifier.testTag("question_submit_${question.id}"),
        ) {
            Text(if (sending) "Sending…" else "Submit")
        }
    }
}

@Composable
private fun FreeTextBody(
    question: QuestionEntry,
    sending: Boolean,
    onAnswer: (text: String, selectedLabels: List<String>) -> Unit,
) {
    FreeTextInput(question, sending, onAnswer)
}

@Composable

private fun FreeTextInput(
    question: QuestionEntry,
    sending: Boolean,
    onAnswer: (text: String, selectedLabels: List<String>) -> Unit,
) {
    var text by rememberSaveable(question.id, "input") { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = !sending,
            placeholder = { Text("Type your answer") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("question_input_${question.id}"),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { onAnswer(text, emptyList()) },
            enabled = !sending && text.isNotBlank(),
            modifier = Modifier.testTag("question_send_${question.id}"),
        ) {
            Icon(Icons.Default.Send, contentDescription = "Send answer")
        }
    }
}

@Composable
private fun OptionList(
    question: QuestionEntry,
    sending: Boolean,
    onPick: (String) -> Unit,
    onTypeSomething: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        question.options.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !sending) { onPick(option.label) }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(14.dp)
                        .height(14.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(50),
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
        TextButton(
            onClick = onTypeSomething,
            enabled = !sending,
        ) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.width(14.dp).height(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Type something")
        }
    }
}

/**
 * The answer to an ask_user_question, shown as a user bubble once the
 * toolResult lands. Dismisses the card and lands the answer in the transcript;
 * derived from the toolResult's details.answers on every poll, so it survives
 * reloads. Text is the option label, the typed free-text answer, or the
 * selected labels joined for multi-select.
 */
@Composable
fun QuestionAnswerBubble(
    question: QuestionEntry,
    modifier: Modifier = Modifier,
) {
    val summary = when {
        question.selected.isNotEmpty() -> question.selected.joinToString(", ")
        !question.answerText.isNullOrBlank() -> question.answerText!!
        else -> return
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .padding(end = 4.dp)
                .widthIn(max = 288.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("question_answer_${question.id}"),
        ) {
            Text(summary, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
