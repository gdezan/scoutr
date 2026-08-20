package dev.scoutr.app.data

/**
 * A skill or slash command the bridge peeled off a user turn. Chat renders
 * this as a chip; the leftover prompt stays in the user bubble. Android does
 * not parse the harness markup — unpeeled turns stay text.
 */
data class SkillInvocation(
    val name: String,
    val body: String = "",
    /** What re-invokes it, as the agent spells it; the chip shows it verbatim. */
    val command: String = "/skill:$name",
)

/** First skill on a turn, plus leftover user prompt. */
data class UserPromptPresentation(
    val skill: SkillInvocation? = null,
    val text: String = "",
)

/** One-line preview of a named skill, used by [entryText] and board activity. */
fun skillInvocationPreview(name: String): String = "[skill: $name]"

private val SLASH_COMMAND_RE = Regex(
    """^/([A-Za-z0-9][\w:.-]*)(?:\s+(.*))?$""",
    setOf(RegexOption.DOT_MATCHES_ALL),
)
private val WHITESPACE_RE = Regex("""\s+""")

/**
 * Structured skill block plus leftover text. Only the first `skill` block
 * counts; later ones are ignored. Text blocks are leftover as-is, including
 * any unpeeled markup.
 */
fun userPromptPresentation(content: List<ContentBlock>): UserPromptPresentation {
    var skill: SkillInvocation? = null
    val texts = mutableListOf<String>()
    for (block in content) {
        when (block.type) {
            "skill" -> {
                val name = block.name?.trim().orEmpty()
                if (name.isNotEmpty() && skill == null) {
                    skill = SkillInvocation(
                        name = name,
                        body = block.text.orEmpty(),
                        command = block.command?.trim()?.takeIf { it.isNotEmpty() } ?: "/skill:$name",
                    )
                }
            }
            "text" -> {
                val text = block.text.orEmpty()
                if (text.isNotBlank()) texts += text
            }
        }
    }
    return UserPromptPresentation(skill = skill, text = texts.joinToString("\n").trim())
}

/**
 * The same split for a message still in the composer, so the pending bubble
 * looks like the transcript echo that replaces it. [agentKind] decides which
 * commands get a chip: pi expands only `/skill:name`, Claude Code expands
 * every slash command it knows.
 */
fun typedPromptPresentation(text: String, agentKind: String? = null): UserPromptPresentation {
    val match = SLASH_COMMAND_RE.matchEntire(text.trim())
        ?: return UserPromptPresentation(text = text)
    val command = match.groupValues[1]
    val args = match.groupValues[2].trim()
    val name = when {
        command.startsWith("skill:") -> command.removePrefix("skill:")
        agentKind == "claude" -> command
        else -> ""
    }
    if (name.isEmpty()) return UserPromptPresentation(text = text)
    return UserPromptPresentation(
        skill = SkillInvocation(name = name, command = "/$command"),
        text = args,
    )
}

/**
 * The slash command plus leftover prompt, so Retry types something the agent
 * re-expands. Plain turns stay [entryText].
 */
fun reconstructUserPrompt(content: List<ContentBlock>): String {
    val presentation = userPromptPresentation(content)
    val skill = presentation.skill ?: return entryText(content)
    return if (presentation.text.isBlank()) {
        skill.command
    } else {
        "${skill.command} ${presentation.text}"
    }
}

/**
 * Compare a composer send to the transcript echo. A peeled skill block is
 * rebuilt into the command that produced it, so both sides read the same.
 * Whitespace runs are collapsed so multi-space/newline messages reconcile.
 */
fun userMessageEchoKey(text: String): String = collapseWhitespace(text)

fun userMessageEchoKey(content: List<ContentBlock>): String =
    collapseWhitespace(reconstructUserPrompt(content))

private fun collapseWhitespace(text: String): String = text.replace(WHITESPACE_RE, " ").trim()
