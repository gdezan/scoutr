package dev.scoutr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.scoutr.app.data.SkillInvocation
import dev.scoutr.app.ui.theme.ScoutrType

/**
 * Collapsed skill invocation: the slash command the user typed, in mono. The
 * injected body stays hidden until the chip is opened. The leftover prompt
 * lives in the user bubble, not here. Agents spell the command differently
 * (`/skill:name` on pi, `/name` on Claude Code), so the chip shows whichever
 * one re-invokes it.
 */
@Composable
fun SkillInvocationChip(
    skill: SkillInvocation,
    modifier: Modifier = Modifier,
) {
    val expandable = skill.body.isNotBlank()
    var expanded by rememberSaveable(skill.name, skill.body) { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.extraSmall
    val surface = modifier
        .widthIn(max = 288.dp)
        .testTag("skill_chip")

    val body: @Composable () -> Unit = {
        SkillInvocationChipBody(skill = skill, expandable = expandable, expanded = expanded)
    }

    if (expandable) {
        PressTintSurface(
            onClick = { expanded = !expanded },
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            pressedColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = surface,
        ) {
            body()
        }
    } else {
        Box(
            surface
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer, shape),
        ) {
            body()
        }
    }
}

@Composable
private fun SkillInvocationChipBody(
    skill: SkillInvocation,
    expandable: Boolean,
    expanded: Boolean,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                skill.command,
                style = ScoutrType.monoTool,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (expandable) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                    contentDescription = if (expanded) "Collapse ${skill.name}" else "Expand ${skill.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (expandable && expanded) {
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    skill.body,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("skill_chip_body"),
                )
            }
        }
    }
}
