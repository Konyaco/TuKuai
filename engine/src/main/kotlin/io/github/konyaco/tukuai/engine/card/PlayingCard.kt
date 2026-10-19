package io.github.konyaco.tukuai.engine.card

/** The four suits of a standard deck. */
enum class Suit { SPADES, HEARTS, CLUBS, DIAMONDS }

/**
 * The thirteen ranks of a standard deck, in their usual printed order.
 * Games that rank cards differently, such as Dou Dizhu where 2 beats A, define
 * their own ordering instead of relying on [ordinal].
 */
enum class Rank { ACE, TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE, TEN, JACK, QUEEN, KING }

/** One card from a standard 54-card deck, jokers included. */
sealed interface PlayingCard {
    data class Regular(val rank: Rank, val suit: Suit) : PlayingCard

    data object BlackJoker : PlayingCard

    data object RedJoker : PlayingCard
}

object Decks {
    /** All 54 distinct cards: 52 regular cards plus the black and red jokers. */
    val STANDARD_54: List<PlayingCard> =
        Suit.entries.flatMap { suit -> Rank.entries.map { rank -> PlayingCard.Regular(rank, suit) } } +
            listOf(PlayingCard.BlackJoker, PlayingCard.RedJoker)
}
