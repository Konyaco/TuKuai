package io.github.konyaco.tukuai.engine.doudizhu

import io.github.konyaco.tukuai.engine.card.Decks
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

/**
 * Dou Dizhu card strength: 3 is lowest, then up to K, A, 2, black joker, red joker.
 * Values run 3..17 so that 3 reads as 3; A is 14 and 2 is 15.
 */
object DdzValue {
    const val THREE = 3
    const val ACE = 14
    const val TWO = 15
    const val BLACK_JOKER = 16
    const val RED_JOKER = 17

    /** Straights, pair straights and planes may not include 2 or a joker. */
    const val HIGHEST_IN_SEQUENCE = ACE

    /** Values 3..17 fit in an array of this size, indexed by value. */
    const val SLOTS = 18

    val ALL: IntRange = THREE..RED_JOKER

    fun label(value: Int): String = when (value) {
        in 3..10 -> value.toString()
        11 -> "J"
        12 -> "Q"
        13 -> "K"
        ACE -> "A"
        TWO -> "2"
        BLACK_JOKER -> "BJ"
        RED_JOKER -> "RJ"
        else -> "?"
    }
}

val PlayingCard.ddzValue: Int
    get() = when (this) {
        PlayingCard.BlackJoker -> DdzValue.BLACK_JOKER
        PlayingCard.RedJoker -> DdzValue.RED_JOKER
        is PlayingCard.Regular -> when (rank) {
            Rank.ACE -> DdzValue.ACE
            Rank.TWO -> DdzValue.TWO
            else -> rank.ordinal + 1
        }
    }

/** Low cards first; equal values keep a fixed suit order so hands always look the same. */
val DDZ_ORDER: Comparator<PlayingCard> =
    compareBy<PlayingCard> { it.ddzValue }.thenBy { DdzCards.index(it) }

object DdzCards {
    private val indexOf: Map<PlayingCard, Int> = Decks.STANDARD_54.withIndex().associate { it.value to it.index }

    fun index(card: PlayingCard): Int = indexOf.getValue(card)

    fun fromIndex(index: Int): PlayingCard =
        Decks.STANDARD_54.getOrNull(index) ?: throw IllegalArgumentException("No card $index")

    /** One deck for three players, or two with every card twice for four. */
    fun deck(decks: Int): List<PlayingCard> = List(decks) { Decks.STANDARD_54 }.flatten()

    /** [hand] without [cards], one copy for each card; null when the hand doesn't hold them all. */
    fun minus(hand: List<PlayingCard>, cards: Collection<PlayingCard>): List<PlayingCard>? {
        val rest = hand.toMutableList()
        for (card in cards) if (!rest.remove(card)) return null
        return rest
    }

    /** How many cards of each value, indexed by value. */
    fun counts(cards: Collection<PlayingCard>): IntArray {
        val counts = IntArray(DdzValue.SLOTS)
        cards.forEach { counts[it.ddzValue]++ }
        return counts
    }

    fun write(writer: WireWriter, cards: List<PlayingCard>) {
        writer.ints(cards.map(::index))
    }

    fun read(reader: WireReader): List<PlayingCard> = reader.ints().map(::fromIndex)

    /** Short text such as `3 3 4 BJ`, handy in logs and tests. */
    fun describe(cards: Collection<PlayingCard>): String =
        cards.sortedWith(DDZ_ORDER).joinToString(" ") { DdzValue.label(it.ddzValue) }
}
