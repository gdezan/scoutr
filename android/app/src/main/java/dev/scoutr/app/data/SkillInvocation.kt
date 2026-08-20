package dev.scoutr.app.data

/**
 * A skill the Pi adapter peeled off a user turn. Chat renders this as a chip;
 * the leftover prompt stays in the user bubble. Android does not parse the
 * XML dump — unpeeled turns stay text.
 */
data class SkillInvocation(
    val name: String,
    val body: String = "",
)

/** First skill on a turn, plus leftover user prompt. */
data class UserPromptPresentation(
    val skill: SkillInvocation? = null,
    val text: String = "",
)

/** One-line preview of a named skill, used by [entryText] and board activity. */
fun skillInvocationPreview(name: String): String = "[skill: $name]"

private val SLASH_SKILL_RE = Regex(
    """^/skill:(\S+)(?:\s+(.*))?$""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)
private val WHITESPACE_RE = Regex("""\s+""")

/**
 * Structured skill block plus leftover text. Only the first `skill` block
 * counts; later ones are ignored. Text blocks are leftover as-is, including
 * any unpeeled XML dump.
 */
fun userPromptPresentation(content: List<ContentBlock>): UserPromptPresentation {
    var skill: SkillInvocation? = null
    val texts = mutableListOf<String>()
    for (block in content) {
        when (block.type) {
            "skill" -> {
                val name = block.name?.trim().orEmpty()
                if (name.isNotEmpty() && skill == null) {
                    skill = SkillInvocation(name = name, body = block.text.orEmpty())
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
 * `/skill:name` plus leftover prompt, so Retry types a command pi will
 * re-expand. Plain turns stay [entryText].
 */
fun reconstructUserPrompt(content: List<ContentBlock>): String {
    val presentation = userPromptPresentation(content)
    val skill = presentation.skill ?: return entryText(content)
    return if (presentation.text.isBlank()) {
        "/skill:${skill.name}"
    } else {
        "/skill:${skill.name} ${presentation.text}"
    }
}

/** `/skill:name args` as the user typed it, before the harness rewrote the turn. */
fun parseSlashSkillCommand(text: String): Pair<String, String>? {
    val match = SLASH_SKILL_RE.matchEntire(text.trim()) ?: return null
    return match.groupValues[1] to match.groupValues[2].trim()
}

/**
 * Compare a composer send to the transcript echo. `/skill:name args` and a
 * peeled skill block plus leftover share one key so the pending row confirms.
 */
fun userMessageEchoKey(text: String): String {
    val slash = parseSlashSkillCommand(text)
    if (slash != null) return echoKey(slash.first, slash.second)
    return collapseWhitespace(text)
}

fun userMessageEchoKey(content: List<ContentBlock>): String {
    val presentation = userPromptPresentation(content)
    val skill = presentation.skill
    if (skill != null) return echoKey(skill.name, presentation.text)
    return collapseWhitespace(presentation.text)
}

private fun echoKey(name: String, text: String): String =
    ("skill:$name\n" + collapseWhitespace(text)).trim()

private fun collapseWhitespace(text: String): String = text.replace(WHITESPACE_RE, " ").trim()
