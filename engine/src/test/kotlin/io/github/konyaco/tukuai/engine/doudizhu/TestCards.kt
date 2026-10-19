package io.github.konyaco.tukuai.engine.doudizhu

import io.github.konyaco.tukuai.engine.card.Decks
import io.github.konyaco.tukuai.engine.card.PlayingCard

/**
 * Builds cards from text like "3 3 4 BJ" for tests. Each label takes the next
 * unused suit of that value, so "3 3" gives two different threes.
 */
fun cards(text: String): List<PlayingCard> {
    val pool = Decks.STANDARD_54.toMutableList()
    return text.split(' ').filter { it.isNotBlank() }.map { label ->
        val value = DdzValue.ALL.first { DdzValue.label(it) == label }
        val card = pool.first { it.ddzValue == value }
        pool.remove(card)
        card
    }
}
