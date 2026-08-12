package dev.cockpit.app.data

/**
 * The session-catalog verb set (bridge-side: `sessions.ts` catalog actions).
 * Typed so a typo is a compile error instead of a runtime 400.
 */
enum class CatalogAction(val wire: String) {
    Resume("resume"),
    Fork("fork"),
    Rename("rename"),
    Delete("delete");

    companion object {
        fun fromWire(value: String): CatalogAction? = entries.find { it.wire == value }
    }
}
