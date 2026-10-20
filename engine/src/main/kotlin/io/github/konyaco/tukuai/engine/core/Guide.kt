package io.github.konyaco.tukuai.engine.core

/**
 * One page of a game's rule book, shown to players who don't know the game yet. The text
 * is given as language keys for the client to look up, so the engine stays free of
 * Minecraft and of any one language. The page's title is `tukuai.guide.<game>.<id>`.
 */
data class GuideSection(val id: String, val blocks: List<GuideBlock>) {
    companion object {
        /** The section that explains one table's rules; it comes first when there is one. */
        const val TABLE = "table"
    }
}

sealed interface GuideBlock {
    /** A paragraph; the numbers in [args] fill in its `%s`. */
    data class Text(val key: String, val args: List<Int> = emptyList()) : GuideBlock

    /** A paragraph with a bullet in front, for lists. */
    data class Point(val key: String, val args: List<Int> = emptyList()) : GuideBlock

    /**
     * Example cards: a [name] in bold, an explanation, and the cards in groups side by side.
     * Each card is a game's own card type, such as a playing card or an UNO face, which
     * the client knows how to draw.
     *
     * When [follows] is set the groups are moves made one after another, and it says
     * whether each group may be played on the one before it.
     */
    data class Example(
        val name: String?,
        val text: String?,
        val groups: List<List<Any>>,
        val follows: Boolean? = null,
    ) : GuideBlock
}
