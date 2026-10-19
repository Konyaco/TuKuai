package io.github.konyaco.tukuai.engine.card

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DecksTest {
    @Test
    fun `standard deck has 54 distinct cards with two jokers`() {
        val deck = Decks.STANDARD_54
        assertEquals(54, deck.size)
        assertEquals(54, deck.toSet().size)
        assertEquals(2, deck.count { it !is PlayingCard.Regular })
    }
}
