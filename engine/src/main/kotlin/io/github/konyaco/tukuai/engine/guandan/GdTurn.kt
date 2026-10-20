package io.github.konyaco.tukuai.engine.guandan

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank

/**
 * What the seat to act may play, worked out the same way for the game, the client and
 * the computers.
 *
 * @param toBeat the play to beat, or null when leading
 */
class GdTurn(val hand: List<PlayingCard>, val toBeat: GdPattern?, val level: Rank) {
    /** Every legal play, one per reading; see [GdPatterns.options]. */
    val plays: List<GdPlay> by lazy { GdPatterns.options(hand, toBeat, level) }

    /** Anyone may pass when following; the leader has to play. */
    val canPass: Boolean get() = toBeat != null

    /** The reading for [cards], or null when they may not be played now. */
    fun reading(cards: Collection<PlayingCard>): GdPattern? = check(cards).first

    /** Why [cards] may not be played now, or null when they may. */
    fun problem(cards: Collection<PlayingCard>): String? = check(cards).second

    private fun check(cards: Collection<PlayingCard>): Pair<GdPattern?, String?> {
        if (cards.isEmpty()) return null to "Play at least one card"
        if (!GdCards.contains(hand, cards)) return null to "Those cards are not all in hand"
        val pattern = GdPatterns.readingFor(cards, toBeat, level)
            ?: return null to "${GdCards.describe(cards, level)} does not make a play that is allowed here"
        return pattern to null
    }
}
