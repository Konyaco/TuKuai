package io.github.konyaco.tukuai.engine.doudizhu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PatternsTest {
    private val rules = DdzRules.CLASSIC

    private fun first(text: String): Pattern? = Patterns.classify(cards(text), rules).firstOrNull()

    @Test
    fun `basic shapes are recognised`() {
        assertEquals(Pattern(PatternType.SINGLE, 3), first("3"))
        assertEquals(Pattern(PatternType.PAIR, 14), first("A A"))
        assertEquals(Pattern(PatternType.TRIO, 15), first("2 2 2"))
        assertEquals(Pattern(PatternType.TRIO_SINGLE, 7), first("7 7 7 3"))
        assertEquals(Pattern(PatternType.TRIO_PAIR, 7), first("7 7 7 K K"))
        assertEquals(Pattern(PatternType.BOMB, 9, 4), first("9 9 9 9"))
        assertEquals(Pattern(PatternType.ROCKET, DdzValue.RED_JOKER, 2), first("BJ RJ"))
        assertEquals(Pattern(PatternType.FOUR_TWO_SINGLES, 5), first("5 5 5 5 8 9"))
        assertEquals(Pattern(PatternType.FOUR_TWO_PAIRS, 5), first("5 5 5 5 8 8 9 9"))
    }

    @Test
    fun `sequences need enough steps and stop at A`() {
        assertEquals(Pattern(PatternType.STRAIGHT, 3, 5), first("3 4 5 6 7"))
        assertEquals(Pattern(PatternType.STRAIGHT, 10, 5), first("10 J Q K A"))
        assertEquals(null, first("J Q K A 2"))
        assertEquals(null, first("3 4 5 6"))
        assertEquals(Pattern(PatternType.PAIR_STRAIGHT, 3, 3), first("3 3 4 4 5 5"))
        assertEquals(null, first("3 3 4 4"))
        assertEquals(Pattern(PatternType.PLANE, 3, 2), first("3 3 3 4 4 4"))
        assertEquals(Pattern(PatternType.PLANE_SINGLES, 3, 2), first("3 3 3 4 4 4 9 J"))
        assertEquals(Pattern(PatternType.PLANE_PAIRS, 3, 2), first("3 3 3 4 4 4 9 9 J J"))
        assertEquals(null, first("K K K A A A 2 2 2"))
    }

    @Test
    fun `nonsense is rejected`() {
        assertEquals(null, first("3 5"))
        assertEquals(null, first("3 3 4 4 5"))
        assertEquals(null, first("7 7 7 7 3"))
    }

    @Test
    fun `four with two can be switched off`() {
        val strict = rules.copy(allowFourWithTwo = false)
        assertTrue(Patterns.classify(cards("5 5 5 5 8 9"), strict).isEmpty())
    }

    @Test
    fun `ambiguous plays keep every reading`() {
        val readings = Patterns.classify(cards("3 3 3 4 4 4 5 5 5 6 6 6"), rules)
        assertTrue(Pattern(PatternType.PLANE, 3, 4) in readings)
        assertTrue(Pattern(PatternType.PLANE_SINGLES, 4, 3) in readings)
        // Following a 3-step plane with singles, the higher reading is the one used.
        val reading = Patterns.readingFor(cards("3 3 3 4 4 4 5 5 5 6 6 6"), Pattern(PatternType.PLANE_SINGLES, 3, 3), rules)
        assertEquals(Pattern(PatternType.PLANE_SINGLES, 4, 3), reading)
    }

    @Test
    fun `beating follows type, length and bombs`() {
        val single3 = Pattern(PatternType.SINGLE, 3)
        assertTrue(Pattern(PatternType.SINGLE, 4).beats(single3))
        assertFalse(Pattern(PatternType.PAIR, 4).beats(single3))
        assertFalse(Pattern(PatternType.STRAIGHT, 4, 6).beats(Pattern(PatternType.STRAIGHT, 3, 5)))
        assertTrue(Pattern(PatternType.STRAIGHT, 4, 5).beats(Pattern(PatternType.STRAIGHT, 3, 5)))
        assertTrue(Pattern(PatternType.BOMB, 3, 4).beats(Pattern(PatternType.STRAIGHT, 10, 5)))
        assertTrue(Pattern(PatternType.BOMB, 4, 4).beats(Pattern(PatternType.BOMB, 3, 4)))
        assertTrue(Pattern(PatternType.ROCKET, 17, 2).beats(Pattern(PatternType.BOMB, 15, 4)))
        assertFalse(Pattern(PatternType.BOMB, 15, 4).beats(Pattern(PatternType.ROCKET, 17, 2)))
    }

    @Test
    fun `every option is a legal play`() {
        val hand = cards("3 3 3 4 4 4 5 5 6 7 8 9 9 9 9 J Q 2 BJ RJ")
        val leads = Patterns.options(hand, null, rules)
        assertTrue(leads.size > 20)
        for (play in leads) {
            assertTrue(play.pattern in Patterns.classify(play.cards, rules), "lead ${DdzCards.describe(play.cards)}")
            assertTrue(hand.containsAll(play.cards))
        }
        val toBeat = Pattern(PatternType.TRIO_SINGLE, 3)
        val follows = Patterns.options(hand, toBeat, rules)
        assertTrue(follows.isNotEmpty())
        for (play in follows) assertNotNull(Patterns.readingFor(play.cards, toBeat, rules), DdzCards.describe(play.cards))
        assertTrue(follows.any { it.pattern.type == PatternType.BOMB })
        assertTrue(follows.any { it.pattern.type == PatternType.ROCKET })
    }

    // ---- Four players, two decks ----------------------------------------------------------------

    private val four = DdzRules.FOUR

    private fun firstOfFour(text: String): Pattern? = Patterns.classify(cards2(text), four).firstOrNull()

    @Test
    fun `two decks make bombs of up to eight cards, and more cards beat fewer`() {
        assertEquals(Pattern(PatternType.BOMB, 3, 8), firstOfFour("3 3 3 3 3 3 3 3"))
        assertEquals(Pattern(PatternType.BOMB, 9, 5), firstOfFour("9 9 9 9 9"))
        val five3 = Pattern(PatternType.BOMB, 3, 5)
        assertTrue(five3.beats(Pattern(PatternType.BOMB, 2 + 13, 4)))
        assertFalse(Pattern(PatternType.BOMB, 15, 4).beats(five3))
        assertTrue(Pattern(PatternType.BOMB, 4, 5).beats(five3))
        assertTrue(Pattern(PatternType.BOMB, 3, 6).beats(Pattern(PatternType.BOMB, 15, 5)))
    }

    @Test
    fun `with two decks only all four jokers are the rocket, and two of a joker are a pair`() {
        assertEquals(Pattern(PatternType.ROCKET, DdzValue.RED_JOKER, 4), firstOfFour("BJ BJ RJ RJ"))
        assertTrue(Pattern(PatternType.ROCKET, DdzValue.RED_JOKER, 4).beats(Pattern(PatternType.BOMB, 15, 8)))
        assertEquals(null, firstOfFour("BJ RJ"))
        assertEquals(Pattern(PatternType.PAIR, DdzValue.BLACK_JOKER), firstOfFour("BJ BJ"))
        assertTrue(Pattern(PatternType.PAIR, DdzValue.RED_JOKER).beats(Pattern(PatternType.PAIR, DdzValue.BLACK_JOKER)))
    }

    @Test
    fun `in the four-player game trios and planes carry only pairs`() {
        assertEquals(null, firstOfFour("7 7 7 3"))
        assertEquals(Pattern(PatternType.TRIO_PAIR, 7), firstOfFour("7 7 7 K K"))
        assertEquals(null, firstOfFour("3 3 3 4 4 4 9 J"))
        assertEquals(Pattern(PatternType.PLANE_PAIRS, 3, 2), firstOfFour("3 3 3 4 4 4 9 9 J J"))
        assertEquals(null, firstOfFour("5 5 5 5 8 9"))
    }

    @Test
    fun `four-player options are legal plays and use each copy once`() {
        val hand = cards2("3 3 3 3 3 4 4 4 5 5 6 7 8 9 9 9 J Q 2 2 BJ BJ RJ RJ 10")
        for (toBeat in listOf(null, Pattern(PatternType.PAIR, 3), Pattern(PatternType.BOMB, 15, 4))) {
            val plays = Patterns.options(hand, toBeat, four)
            assertTrue(plays.isNotEmpty())
            for (play in plays) {
                assertNotNull(Patterns.readingFor(play.cards, toBeat, four), DdzCards.describe(play.cards))
                assertNotNull(DdzCards.minus(hand, play.cards), DdzCards.describe(play.cards))
            }
        }
        val leads = Patterns.options(hand, null, four)
        assertTrue(leads.any { it.pattern == Pattern(PatternType.BOMB, 3, 5) })
        assertTrue(leads.any { it.pattern.type == PatternType.ROCKET })
        assertTrue(leads.none { it.pattern.type == PatternType.TRIO_SINGLE })
    }
}
