package io.github.konyaco.tukuai.engine.paodekuai

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.doudizhu.DdzCards
import io.github.konyaco.tukuai.engine.doudizhu.ddzValue

/**
 * What the seat to act may do, worked out the same way for the game, the client and the
 * computers.
 *
 * @param toBeat the play to beat, or null when leading
 * @param nextHasOne the next player has one card left, so a single must be the highest card
 * @param opening nobody has played yet, so the play must hold the 3 of spades
 */
class PdkTurn(
    val rules: PdkRules,
    val hand: List<PlayingCard>,
    val toBeat: PdkPattern?,
    val nextHasOne: Boolean,
    val opening: Boolean,
) {
    private val mustInclude: PlayingCard? get() = if (opening) PaoDeKuai.STARTER else null

    private val highest: Int by lazy { hand.maxOfOrNull { it.ddzValue } ?: 0 }

    /** Every legal play, one per reading; see [PdkPatterns.options]. */
    val plays: List<PdkPlay> by lazy {
        PdkPatterns.options(hand, toBeat, rules, mustInclude).filter { singleAllowed(it.pattern) }
    }

    /** Passing is allowed when following, and with "must beat" only when nothing beats the table. */
    val canPass: Boolean get() = toBeat != null && (!rules.mustBeat || plays.isEmpty())

    private fun singleAllowed(pattern: PdkPattern) = !nextHasOne || pattern.type != PdkPatternType.SINGLE || pattern.main == highest

    /** The reading for [cards], or null when they may not be played now. */
    fun reading(cards: Collection<PlayingCard>): PdkPattern? = check(cards).first

    /** Why [cards] may not be played now, or null when they may. */
    fun problem(cards: Collection<PlayingCard>): String? = check(cards).second

    private fun check(cards: Collection<PlayingCard>): Pair<PdkPattern?, String?> {
        if (cards.isEmpty()) return null to "Play at least one card"
        if (cards.toSet().size != cards.size || !hand.containsAll(cards)) return null to "Those cards are not all in hand"
        if (opening && PaoDeKuai.STARTER !in cards) return null to "The first play must hold the 3 of spades"
        val pattern = PdkPatterns.readingFor(cards, toBeat, rules, lastCards = cards.size == hand.size)
            ?: return null to "${DdzCards.describe(cards)} does not make a play that is allowed here"
        if (!singleAllowed(pattern)) return null to "The next player has one card left, so a single must be your highest card"
        return pattern to null
    }
}
