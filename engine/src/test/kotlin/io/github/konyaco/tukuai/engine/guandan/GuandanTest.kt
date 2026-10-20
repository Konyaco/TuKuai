package io.github.konyaco.tukuai.engine.guandan

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.guandan.GdPatternType.BOMB
import io.github.konyaco.tukuai.engine.guandan.GdPatternType.FULL_HOUSE
import io.github.konyaco.tukuai.engine.guandan.GdPatternType.JOKER_BOMB
import io.github.konyaco.tukuai.engine.guandan.GdPatternType.PAIR
import io.github.konyaco.tukuai.engine.guandan.GdPatternType.PAIR_RUN
import io.github.konyaco.tukuai.engine.guandan.GdPatternType.PLANE
import io.github.konyaco.tukuai.engine.guandan.GdPatternType.SINGLE
import io.github.konyaco.tukuai.engine.guandan.GdPatternType.STRAIGHT
import io.github.konyaco.tukuai.engine.guandan.GdPatternType.STRAIGHT_FLUSH
import io.github.konyaco.tukuai.engine.guandan.GdPatternType.TRIPLE
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GuandanTest {
    private fun read(text: String, level: Rank = Rank.TWO): GdPattern? = GdPatterns.classify(GdCards.parse(text, level), level).firstOrNull()

    private fun type(text: String, level: Rank = Rank.TWO) = read(text, level)?.type

    private fun beats(first: String, then: String, level: Rank = Rank.TWO): Boolean {
        val lead = read(first, level)!!
        return GdPatterns.readingFor(GdCards.parse(then, level), lead, level) != null
    }

    // ---- Patterns ------------------------------------------------------------------------------

    @Test
    fun `plain patterns`() {
        assertEquals(SINGLE, type("K"))
        assertEquals(PAIR, type("9 9"))
        assertEquals(TRIPLE, type("6 6 6"))
        assertEquals(FULL_HOUSE, type("J J J 4 4"))
        assertEquals(STRAIGHT, type("10s Jc Qd Ks Ah"))
        assertEquals(PAIR_RUN, type("5 5 6 6 7 7"))
        assertEquals(PLANE, type("8 8 8 9 9 9"))
        assertEquals(BOMB, type("4 4 4 4"))
        assertEquals(STRAIGHT_FLUSH, type("6h 7h 8h 9h 10h"))
        assertEquals(JOKER_BOMB, type("SJ SJ BJ BJ"))
        assertEquals(PAIR, type("SJ SJ"))
    }

    @Test
    fun `shapes that are not plays`() {
        assertNull(read("3 4"))
        assertNull(read("SJ BJ"), "a small and a big joker are not a pair")
        assertNull(read("3s 4c 5d 6s"), "a straight is exactly five")
        assertNull(read("3s 4c 5d 6s 7c 8d"), "and not six")
        assertNull(read("5 5 6 6"), "pairs in a row are exactly three")
        assertNull(read("5 5 6 6 7 7 8 8"))
        assertNull(read("8 8 8 9 9 9 10 10 10"), "a plane is exactly two threes")
        assertNull(read("Qs Kc Ad 2s 3c"), "a straight does not wrap around")
        assertNull(read("J J J SJ"))
    }

    @Test
    fun `aces sit low or high in runs`() {
        val low = read("As 2c 3d 4s 5c")!!
        val high = read("10s Jc Qd Ks Ac")!!
        assertEquals(STRAIGHT, low.type)
        assertEquals(5, low.main)
        assertEquals(14, high.main)
        assertTrue(high.beats(low))
        assertEquals(PAIR_RUN, type("A A 2 2 3 3"))
        assertEquals(PAIR_RUN, type("Q Q K K A A"))
        assertEquals(PLANE, type("A A A 2 2 2"))
        assertEquals(PLANE, type("K K K A A A"))
        assertTrue(beats("A A 2 2 3 3", "2 2 3 3 4 4"))
    }

    @Test
    fun `the level rank stands above the ace and below the jokers`() {
        assertTrue(beats("A", "7", Rank.SEVEN))
        assertTrue(beats("7", "SJ", Rank.SEVEN))
        assertTrue(beats("SJ", "BJ", Rank.SEVEN))
        assertFalse(beats("7 7", "A A", Rank.SEVEN))
        // In a straight the level card keeps its own place.
        assertEquals(7, read("3s 4c 5d 6s 7c", Rank.SEVEN)!!.main)
    }

    @Test
    fun `wild hearts fill in for any card but a joker`() {
        val level = Rank.FIVE
        assertEquals(TRIPLE, type("8 8 W", level))
        assertEquals(STRAIGHT, type("9s 10c W Qd Ks", level))
        assertEquals(FULL_HOUSE, type("J J W 4 4", level))
        assertEquals(PAIR_RUN, type("6 6 7 W 8 8", level))
        assertEquals(PLANE, type("9 9 W 10 10 10", level))
        assertEquals(STRAIGHT_FLUSH, type("6d 7d W 9d 10d", level))
        assertEquals(GdPattern(BOMB, 12, 5), read("Q Q Q Q W", level))
        assertEquals(GdPattern(BOMB, 3, 6), read("3 3 3 3 W W", level))
        assertNull(read("SJ W", level), "a wild card can't be a joker")
        assertNull(read("BJ BJ W", level))
        // On their own, they are cards of the level.
        assertEquals(GdPattern(PAIR, GdValue.LEVEL, 2), read("W W", level))
        assertEquals(GdPattern(PAIR, GdValue.LEVEL, 2), read("5s W", level))
        assertEquals(GdPattern(SINGLE, GdValue.LEVEL, 1), read("W", level))
        // The five of hearts is only wild when fives are the level.
        assertEquals(null, read("8 8 5h", Rank.SIX))
    }

    @Test
    fun `bombs rank by size, a straight flush between five and six, four jokers on top`() {
        val ladder = listOf("7 7 7 7", "K K K K", "3 3 3 3 3", "A A A A A", "2d 3d 4d 5d 6d", "9h 10h Jh Qh Kh", "4 4 4 4 4 4", "9 9 9 9 9 9 9", "6 6 6 6 6 6 6 6 W W", "SJ SJ BJ BJ")
        val level = Rank.FOUR
        for (i in ladder.indices) {
            for (j in ladder.indices) {
                assertEquals(j > i, read(ladder[j], level)!!.beats(read(ladder[i], level)!!), "${ladder[j]} on ${ladder[i]}")
            }
        }
        assertTrue(beats("Q Q Q 3 3", "3 3 3 3"), "any bomb beats any other play")
        assertTrue(beats("9s 10c Jd Qs Kc", "2d 3d 4d 5d 6d"))
        assertFalse(beats("3 3 3 3", "J J J 4 4"))
        // A straight flush still counts as a straight when that is what it needs to beat.
        assertEquals(STRAIGHT_FLUSH, GdPatterns.readingFor(GdCards.parse("4d 5d 6d 7d 8d"), read("3s 4c 5d 6s 7c"), Rank.TWO)!!.type)
    }

    @Test
    fun `plays compare only with the same pattern`() {
        assertTrue(beats("4 4", "9 9"))
        assertFalse(beats("9 9", "4 4"))
        assertFalse(beats("9 9", "J J J 4 4"))
        assertFalse(beats("5 5 6 6 7 7", "8 8 8 9 9 9"))
        assertTrue(beats("J J J 4 4", "Q Q Q 3 3"))
    }

    @Test
    fun `every option is a real reading that beats the table`() {
        val random = Random(4)
        repeat(300) {
            val level = GdValue.LADDER[random.nextInt(GdValue.LADDER.size)]
            val deck = GdCards.DECK.shuffled(random)
            val hand = deck.take(random.nextInt(5, 28))
            val leadPlays = GdPatterns.options(deck.drop(40).take(27), null, level)
            val toBeat = if (random.nextBoolean()) null else leadPlays[random.nextInt(leadPlays.size)].pattern
            for (play in GdPatterns.options(hand, toBeat, level)) {
                assertTrue(GdCards.contains(hand, play.cards))
                val reading = GdPatterns.readingFor(play.cards, toBeat, level)
                assertNotNull(reading, "${GdCards.describe(play.cards, level)} at $level on $toBeat")
                assertTrue(play.pattern in GdPatterns.classify(play.cards, level))
            }
        }
    }

    @Test
    fun `options miss nothing that could be played`() {
        val random = Random(9)
        repeat(300) {
            val level = GdValue.LADDER[random.nextInt(GdValue.LADDER.size)]
            val hand = GdCards.DECK.shuffled(random).take(12)
            val toBeat = GdPatterns.options(GdCards.DECK.shuffled(random).take(27), null, level).random(random).pattern
            val options = GdPatterns.options(hand, toBeat, level)
            // Try some subsets: when one can be played, options must have something too.
            repeat(200) {
                val subset = hand.filter { random.nextInt(3) == 0 }
                if (subset.isNotEmpty() && GdPatterns.readingFor(subset, toBeat, level) != null) {
                    assertTrue(options.isNotEmpty(), "${GdCards.describe(subset, level)} beats $toBeat but no option was found")
                }
            }
        }
    }

    // ---- Playing a round -----------------------------------------------------------------------

    private fun state(hands: List<String>, turn: Int, rules: GdRules = GdRules.ONE_ROUND, level: Int = 0, levels: List<Int> = listOf(0, 0), levelTeam: Int = -1): GdState {
        val rank = GdValue.LADDER[level]
        return GdState(
            rules = rules, seed = 7, round = 0, levels = levels, levelTeam = levelTeam, level = level, phase = GdPhase.PLAYING,
            hands = hands.map { GdCards.parse(it, rank) }, turn = turn, lead = null, passes = 0, order = emptyList(),
            tributes = emptyList(), leader = turn, log = emptyList(), rounds = emptyList(), scores = List(4) { 0 }, winners = null,
        )
    }

    private fun GdState.play(text: String): GdState = Guandan.apply(this, turn, GdAction.PlayCards(GdCards.parse(text, levelRank)))

    private fun GdState.pass(): GdState = Guandan.apply(this, turn, GdAction.Pass)

    @Test
    fun `everyone passing hands the lead back to the last to play`() {
        var s = state(listOf("3 4 5", "6 7", "8 9", "10 J"), 0)
        s = s.play("3").pass().pass().pass()
        assertEquals(0, s.turn)
        assertNull(s.lead)
        assertThrows<IllegalActionException> { s.pass() }
    }

    @Test
    fun `when a player goes out and everyone passes, the partner leads`() {
        var s = state(listOf("9", "3 4", "5 6", "7 8"), 0)
        s = s.play("9")
        assertEquals(listOf(0), s.order)
        assertEquals(1, s.turn)
        s = s.pass().pass().pass()
        assertEquals(2, s.turn, "the partner leads")
        assertNull(s.lead)
        assertEquals(GdEventKind.PARTNER_LEADS, s.log.last().kind)
    }

    @Test
    fun `beating the last play of a player who is out keeps the turn going`() {
        var s = state(listOf("9", "10 4", "5 6", "7 8"), 0)
        s = s.play("9").play("10")
        assertEquals(2, s.turn)
        s = s.pass().pass()
        assertEquals(1, s.turn, "seat 0 is out, so after seats 2 and 3 pass it comes back to 1")
    }

    @Test
    fun `winners go up by where the partner finished`() {
        // Seat 0 then seat 2 out: both of a team, so the round ends at once, up 3.
        var s = state(listOf("9", "3 4", "10", "5 6"), 0)
        s = s.play("9").pass().play("10")
        assertEquals(GdPhase.FINISHED, s.phase)
        assertEquals(3, s.rounds.single().gain)
        assertEquals(listOf(3, 0), s.levels)
        assertEquals(listOf(3, -3, 3, -3), s.scores)
        assertEquals(setOf(0, 2), s.winners)
        assertEquals(listOf(0, 2), s.rounds.single().order.take(2))

        // Partner third: up 2. Seat 1's partner leads once everyone passes on its last card.
        s = state(listOf("9", "J", "10 K", "5 6 7"), 0)
        s = s.play("9").play("J").pass().pass()
        assertEquals(3, s.turn)
        s = s.play("5").play("10").pass()
        assertEquals(2, s.turn)
        s = s.play("K")
        assertEquals(listOf(0, 1, 2, 3), s.rounds.single().order)
        assertEquals(2, s.rounds.single().gain)
        assertEquals(listOf(2, -2, 2, -2), s.scores)

        // Partner last: up 1.
        s = state(listOf("9", "J", "3 4", "Q"), 0)
        s = s.play("9").play("J").pass().play("Q")
        assertEquals(listOf(0, 1, 3, 2), s.rounds.single().order)
        assertEquals(1, s.rounds.single().gain)
        assertEquals(0, s.scores.sum())
    }

    /** A long game at the moment seat 0 and seat 2 are about to go out first and second. */
    private fun almostOver(order: String) = when (order) {
        // 0, 1, 2 out in that order: seat 3 last, and pays.
        "0123" -> state(listOf("9", "J", "Q", "3 4"), 0, GdRules.TO_ACE).play("9").play("J").play("Q")
        // 0 then 2: seats 1 and 3 both pay.
        else -> state(listOf("9", "3 4", "10", "5 6"), 0, GdRules.TO_ACE).play("9").pass().play("10")
    }

    @Test
    fun `the last player pays the winner their best card and gets one back`() {
        val over = almostOver("0123")
        assertEquals(GdPhase.ROUND_OVER, over.phase)
        assertEquals(0, over.turn, "the winner deals")
        assertThrows<IllegalActionException> { Guandan.apply(over, 0, GdAction.Pass) }
        var s = Guandan.apply(over, 0, GdAction.Next)
        assertEquals(1, s.round)
        assertEquals(0, s.levelTeam)
        assertEquals(Rank.FOUR, s.levelRank, "team 0 went up 2, from 2 to 4")
        if (s.phase == GdPhase.PLAYING) return // Seat 3 happened to hold both big jokers.
        val tribute = s.tributes.single()
        assertEquals(3, tribute.from)
        assertEquals(0, tribute.to)
        assertEquals(27, s.hands[3].size + 1)
        val paid = s.hands[3] + tribute.card
        assertEquals(paid.filterNot { it.isWild(s.levelRank) }.maxOf { it.gdValue(s.levelRank) }, tribute.card.gdValue(s.levelRank))
        assertEquals(GdPhase.RETURN, s.phase)
        assertEquals(0, s.turn)
        val high = s.hands[0].firstOrNull { it is PlayingCard.Regular && GdValue.natural(it.rank) > 10 }
        if (high != null && Guandan.returnable(s.hands[0]).none { it == high }) {
            assertThrows<IllegalActionException> { Guandan.apply(s, 0, GdAction.Return(high)) }
        }
        val back = Guandan.returnable(s.hands[0]).first()
        s = Guandan.apply(s, 0, GdAction.Return(back))
        assertEquals(GdPhase.PLAYING, s.phase)
        assertEquals(3, s.turn, "whoever paid leads")
        assertEquals(27, s.hands[0].size)
        assertEquals(27, s.hands[3].size)
        assertTrue(back in s.hands[3])
    }

    @Test
    fun `when both losers come last, both pay and the bigger tribute goes to the winner`() {
        val over = almostOver("0213")
        assertEquals(listOf(0, 2), over.rounds.single().order.take(2))
        val s = Guandan.apply(over, 0, GdAction.Next)
        if (s.phase == GdPhase.PLAYING) return
        assertEquals(2, s.tributes.size)
        val (big, small) = s.tributes
        assertEquals(0, big.to)
        assertEquals(2, small.to)
        assertEquals(setOf(1, 3), setOf(big.from, small.from))
        assertTrue(big.card.gdValue(s.levelRank) >= small.card.gdValue(s.levelRank))
        assertEquals(big.from, s.leader)
        // Both winners give back, the first one first.
        var t = Guandan.apply(s, 0, GdAction.Return(Guandan.returnable(s.hands[0]).first()))
        assertEquals(2, t.turn)
        t = Guandan.apply(t, 2, GdAction.Return(Guandan.returnable(t.hands[2]).first()))
        assertEquals(GdPhase.PLAYING, t.phase)
        assertEquals(big.from, t.turn)
    }

    @Test
    fun `no tribute when the paying side holds both big jokers`() {
        var found = 0
        for (seed in 0L until 3000L) {
            val over = almostOver("0213").copy(seed = seed)
            val s = Guandan.apply(over, 0, GdAction.Next)
            val payersJokers = listOf(1, 3).sumOf { p -> s.hands[p].count { it == PlayingCard.RedJoker } }
            if (s.phase == GdPhase.PLAYING) {
                assertEquals(2, payersJokers, "seed $seed")
                assertEquals(GdEventKind.NO_TRIBUTE, s.log.single().kind)
                assertEquals(0, s.turn, "the winner leads")
                assertTrue(s.tributes.isEmpty())
                found++
            }
            if (found >= 3) return
        }
        assertTrue(found > 0, "no deal gave the payers both big jokers")
    }

    @Test
    fun `the long game ends when a team at A wins at its own level with the partner not last`() {
        val atAce = GdValue.LADDER.size - 1
        // Team 0 at A, playing its A: seats 0 and 2 go out first and second.
        var s = state(listOf("9", "3 4", "10", "5 6"), 0, GdRules.TO_ACE, level = atAce, levels = listOf(atAce, 4), levelTeam = 0)
        s = s.play("9").pass().play("10")
        assertEquals(GdPhase.FINISHED, s.phase)
        assertEquals(setOf(0, 2), s.winners)

        // The same with the partner last: no win yet, and team 0 stays at A.
        s = state(listOf("9", "J", "3 4", "Q"), 0, GdRules.TO_ACE, level = atAce, levels = listOf(atAce, 4), levelTeam = 0)
        s = s.play("9").play("J").pass().play("Q")
        assertEquals(GdPhase.ROUND_OVER, s.phase)
        assertEquals(listOf(atAce, 4), s.levels)

        // Winning at A when it is the other team's level does not end it either.
        s = state(listOf("9", "3 4", "10", "5 6"), 0, GdRules.TO_ACE, level = 4, levels = listOf(atAce, 4), levelTeam = 1)
        s = s.play("9").pass().play("10")
        assertEquals(GdPhase.ROUND_OVER, s.phase)
    }

    @Test
    fun `views hide other hands until the round is over`() {
        val s = Guandan.start(GdRules.ONE_ROUND, 3)
        val view = Guandan.view(s, 1)
        assertEquals(s.hands[1], view.hand)
        assertTrue(view.revealedHands.isEmpty())
        assertTrue(Guandan.view(s, null).hand.isEmpty())
        assertEquals(List(4) { 27 }, view.handCounts)
        assertEquals(view, GdCodecs.VIEW.decode(GdCodecs.VIEW.encode(view)))
        assertEquals(108, s.hands.flatten().size)
        for (card in GdCards.DECK.distinct()) assertEquals(2, s.hands.flatten().count { it == card })
    }

    // ---- The rule book -------------------------------------------------------------------------

    @Test
    fun `the rule book's examples are what they say`() {
        val level = GdGuide.LEVEL
        for ((type, cards) in GdGuide.PATTERNS) assertEquals(type, GdPatterns.classify(cards, level).first().type, "$type")
        for ((name, cards, type) in GdGuide.WILD) {
            assertTrue(cards.any { it.isWild(level) }, name)
            assertEquals(type, GdPatterns.classify(cards, level).first().type, name)
        }
        GdGuide.BOMBS.zipWithNext().forEach { (a, b) ->
            assertTrue(GdPatterns.classify(b, level).first().beats(GdPatterns.classify(a, level).first()))
        }
        for ((name, play) in GdGuide.BEATING) {
            val lead = GdPatterns.classify(play.first, level).first()
            assertEquals(play.third, GdPatterns.readingFor(play.second, lead, level) != null, name)
        }
    }
}
