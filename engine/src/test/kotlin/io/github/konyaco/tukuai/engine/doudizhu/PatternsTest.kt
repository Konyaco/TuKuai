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
        assertEquals(Pattern(PatternType.BOMB, 9), first("9 9 9 9"))
        assertEquals(Pattern(PatternType.ROCKET, DdzValue.RED_JOKER), first("BJ RJ"))
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
        assertTrue(Pattern(PatternType.BOMB, 3).beats(Pattern(PatternType.STRAIGHT, 10, 5)))
        assertTrue(Pattern(PatternType.BOMB, 4).beats(Pattern(PatternType.BOMB, 3)))
        assertTrue(Pattern(PatternType.ROCKET, 17).beats(Pattern(PatternType.BOMB, 15)))
        assertFalse(Pattern(PatternType.BOMB, 15).beats(Pattern(PatternType.ROCKET, 17)))
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
}
