package io.github.konyaco.tukuai.engine.doudizhu

import io.github.konyaco.tukuai.engine.card.Decks
import io.github.konyaco.tukuai.engine.card.PlayingCard

/**
 * Builds cards from text like "3 3 4 BJ" for tests. Each label takes the next
 * unused suit of that value, so "3 3" gives two different threes.
 */
fun cards(text: String): List<PlayingCard> = cardsFrom(Decks.STANDARD_54, text)

/** The same from two decks, so a value may appear up to eight times. */
fun cards2(text: String): List<PlayingCard> = cardsFrom(DdzCards.deck(2), text)

private fun cardsFrom(deck: List<PlayingCard>, text: String): List<PlayingCard> {
    val pool = deck.toMutableList()
    return text.split(' ').filter { it.isNotBlank() }.map { label ->
        val value = DdzValue.ALL.first { DdzValue.label(it) == label }
        val card = pool.first { it.ddzValue == value }
        pool.remove(card)
        card
    }
}
