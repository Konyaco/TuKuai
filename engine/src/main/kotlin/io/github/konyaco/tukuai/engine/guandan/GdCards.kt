package io.github.konyaco.tukuai.engine.guandan

import io.github.konyaco.tukuai.engine.card.Decks
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.card.Suit
import io.github.konyaco.tukuai.engine.doudizhu.DdzCards

/**
 * Guandan card strength. Ranks run 2 lowest up to A, then the cards of the level being
 * played, then the small and the big joker. In straights and runs a card keeps its printed
 * place instead, and an ace may also sit below the 2.
 */
object GdValue {
    const val TWO = 2
    const val TEN = 10
    const val ACE = 14

    /** Any card of the rank being played, wild or not, when it is not part of a run. */
    const val LEVEL = 15
    const val SMALL_JOKER = 16
    const val BIG_JOKER = 17

    /** Values 0..17 fit in an array of this size, indexed by value. */
    const val SLOTS = 18

    /** Where an ace sits in a run that starts below the 2, such as A 2 3 4 5. */
    const val LOW_ACE = 1

    /** The levels a team climbs through, from where every team starts to the last. */
    val LADDER: List<Rank> = listOf(
        Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX, Rank.SEVEN, Rank.EIGHT,
        Rank.NINE, Rank.TEN, Rank.JACK, Rank.QUEEN, Rank.KING, Rank.ACE,
    )

    /** The printed place of a rank, 2..14 with the ace highest. */
    fun natural(rank: Rank): Int = if (rank == Rank.ACE) ACE else rank.ordinal + 1

    /** The rank printed at a place in a run, 1 or 14 both being the ace. */
    fun rankAt(place: Int): Rank = if (place == LOW_ACE || place == ACE) Rank.ACE else Rank.entries[place - 1]

    fun label(value: Int): String = when (value) {
        in 2..10 -> value.toString()
        LOW_ACE -> "A"
        11 -> "J"
        12 -> "Q"
        13 -> "K"
        ACE -> "A"
        LEVEL -> "L"
        SMALL_JOKER -> "SJ"
        BIG_JOKER -> "BJ"
        else -> "?"
    }
}

/** How strong a card is on its own when [level] is the rank being played. */
fun PlayingCard.gdValue(level: Rank): Int = when (this) {
    PlayingCard.BlackJoker -> GdValue.SMALL_JOKER
    PlayingCard.RedJoker -> GdValue.BIG_JOKER
    is PlayingCard.Regular -> if (rank == level) GdValue.LEVEL else GdValue.natural(rank)
}

/** The two hearts of the level rank stand in for any card but a joker. */
fun PlayingCard.isWild(level: Rank): Boolean = this is PlayingCard.Regular && rank == level && suit == Suit.HEARTS

val PlayingCard.isJoker: Boolean get() = this !is PlayingCard.Regular

object GdCards {
    /** Two full decks with their jokers: 108 cards, with every card twice. */
    val DECK: List<PlayingCard> = Decks.STANDARD_54 + Decks.STANDARD_54

    /** Strongest first for showing a hand: wild cards, jokers, the level, then A down to 2. */
    fun order(level: Rank): Comparator<PlayingCard> =
        compareByDescending<PlayingCard> { it.isWild(level) }
            .thenByDescending { it.gdValue(level) }
            .thenBy { DdzCards.index(it) }

    /** Weakest first, for the engine's own lists. */
    fun ascending(level: Rank): Comparator<PlayingCard> =
        compareBy<PlayingCard> { it.isWild(level) }.thenBy { it.gdValue(level) }.thenBy { DdzCards.index(it) }

    /** [hand] without [cards], one copy for each card; null when the hand doesn't hold them all. */
    fun minus(hand: List<PlayingCard>, cards: Collection<PlayingCard>): List<PlayingCard>? {
        val rest = hand.toMutableList()
        for (card in cards) if (!rest.remove(card)) return null
        return rest
    }

    fun contains(hand: List<PlayingCard>, cards: Collection<PlayingCard>): Boolean = minus(hand, cards) != null

    /** Short text such as `3 3 4 SJ`, wild cards as `W`, for logs and messages. */
    fun describe(cards: Collection<PlayingCard>, level: Rank): String =
        cards.sortedWith(ascending(level)).joinToString(" ") { card ->
            when {
                card.isWild(level) -> "W"
                card is PlayingCard.Regular -> GdValue.label(GdValue.natural(card.rank))
                else -> GdValue.label(card.gdValue(level))
            }
        }

    /**
     * Cards from text for tests and the rule book: ranks such as `3 3 4 10 A SJ BJ`, with an
     * optional suit letter after the rank (`5h`, `Ks`), and `W` for a wild card of [level].
     * Without a suit, each copy takes the next of spades, clubs, diamonds and then hearts, so
     * a level card is only wild when asked for.
     */
    fun parse(text: String, level: Rank = Rank.TWO): List<PlayingCard> {
        val pool = DECK.toMutableList()
        fun take(card: PlayingCard): PlayingCard {
            require(pool.remove(card)) { "No more $card in two decks" }
            return card
        }
        return text.split(' ').filter { it.isNotBlank() }.map { label ->
            when (label) {
                "W" -> take(PlayingCard.Regular(level, Suit.HEARTS))
                "SJ" -> take(PlayingCard.BlackJoker)
                "BJ" -> take(PlayingCard.RedJoker)
                else -> {
                    val suitLetter = label.last().takeIf { it in "shcd" }
                    val rankText = if (suitLetter != null) label.dropLast(1) else label
                    val rank = RANKS.getValue(rankText)
                    val suit = when (suitLetter) {
                        's' -> Suit.SPADES
                        'h' -> Suit.HEARTS
                        'c' -> Suit.CLUBS
                        'd' -> Suit.DIAMONDS
                        else -> listOf(Suit.SPADES, Suit.CLUBS, Suit.DIAMONDS, Suit.HEARTS).first { s ->
                            PlayingCard.Regular(rank, s) in pool && !(rank == level && s == Suit.HEARTS)
                        }
                    }
                    take(PlayingCard.Regular(rank, suit))
                }
            }
        }
    }

    private val RANKS: Map<String, Rank> = mapOf(
        "A" to Rank.ACE, "2" to Rank.TWO, "3" to Rank.THREE, "4" to Rank.FOUR, "5" to Rank.FIVE,
        "6" to Rank.SIX, "7" to Rank.SEVEN, "8" to Rank.EIGHT, "9" to Rank.NINE, "10" to Rank.TEN,
        "J" to Rank.JACK, "Q" to Rank.QUEEN, "K" to Rank.KING,
    )
}
