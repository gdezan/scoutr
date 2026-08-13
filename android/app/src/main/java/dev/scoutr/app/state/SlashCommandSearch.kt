package dev.scoutr.app.state

import dev.scoutr.app.data.SlashCommandInfo

/** The command-name fragment after a leading slash, or null once arguments begin. */
fun slashCommandQuery(input: String): String? {
    if (!input.startsWith('/')) return null
    val query = input.drop(1)
    return query.takeIf { it.none(Char::isWhitespace) }
}

fun matchSlashCommands(commands: List<SlashCommandInfo>, query: String): List<SlashCommandInfo> {
    val needle = query.lowercase()
    if (needle.isEmpty()) return commands
    return commands.mapNotNull { command ->
        val name = command.name.lowercase()
        val score = when {
            name == needle -> 0
            name.startsWith(needle) -> 1
            name.contains(needle) -> 2
            command.description.contains(needle, ignoreCase = true) -> 3
            else -> return@mapNotNull null
        }
        command to score
    }.sortedWith(compareBy<Pair<SlashCommandInfo, Int>> { it.second }.thenBy { it.first.name })
        .map { it.first }
}

fun fillSlashCommand(command: SlashCommandInfo): String =
    "/${command.name}" + if (command.argumentHint == null) "" else " "
