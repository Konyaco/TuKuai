package io.github.konyaco.tukuai.engine.paodekuai

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.card.Suit
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.core.presetIdOf
import io.github.konyaco.tukuai.engine.doudizhu.DdzValue
import io.github.konyaco.tukuai.engine.doudizhu.ddzValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class PaoDeKuaiTest {
    private val rules = PdkRules.SIXTEEN

    /** Cards from text like "3 3 4 K"; each label takes the next suit of that value still in the 16-card deck. */
    private fun cards(text: String, deck: List<PlayingCard> = PaoDeKuai.deck(rules)): List<PlayingCard> {
        val pool = deck.toMutableList()
        return text.split(' ').filter { it.isNotBlank() }.map { label ->
            val value = (DdzValue.THREE..DdzValue.TWO).first { DdzValue.label(it) == label }
            pool.first { it.ddzValue == value }.also { pool.remove(it) }
        }
    }

    /** Three hands dealt from one pool, so no card is in two of them. */
    private fun hands(vararg texts: String): List<List<PlayingCard>> {
        val pool = PaoDeKuai.deck(rules).toMutableList()
        return texts.map { text -> cards(text, pool).also { pool.removeAll(it.toSet()) } }
    }

    /** A game in the middle: [turn] to act, nobody's opening play still owed. */
    private fun state(hands: List<List<PlayingCard>>, turn: Seat, lead: PdkTablePlay? = null, r: PdkRules = rules): PdkState = PdkState(
        rules = r,
        seed = 0,
        hands = hands,
        starter = 0,
        turn = turn,
        lead = lead,
        playCounts = List(3) { 1 },
        bombs = List(3) { 0 },
        log = listOf(PdkEvent(0, PdkEventKind.PLAY, listOf(PaoDeKuai.STARTER), PdkPattern(PdkPatternType.SINGLE, 3))),
        settlement = null,
    )

    private fun lead(seat: Seat, text: String, lastCards: Boolean = false): PdkTablePlay {
        val c = cards(text)
        return PdkTablePlay(seat, c, PdkPatterns.readingFor(c, null, rules, lastCards)!!)
    }

    private fun PdkState.play(text: String) = PaoDeKuai.apply(this, turn, PdkAction.PlayCards(cardsOf(turn, text)))

    private fun PdkState.cardsOf(seat: Seat, text: String): List<PlayingCard> {
        val pool = hands[seat].toMutableList()
        return text.split(' ').map { label ->
            pool.first { DdzValue.label(it.ddzValue) == label }.also { pool.remove(it) }
        }
    }

    private fun PdkState.pass() = PaoDeKuai.apply(this, turn, PdkAction.Pass)

    private fun read(text: String, lastCards: Boolean = false, r: PdkRules = rules) = PdkPatterns.classify(cards(text), r, lastCards).firstOrNull()

    // ---- Dealing -------------------------------------------------------------------------------

    @Test
    fun `16 cards each from 48, and 15 each from 45, with the 3 of spades leading`() {
        for ((r, size) in listOf(PdkRules.SIXTEEN to 16, PdkRules.FIFTEEN to 15)) {
            val deck = PaoDeKuai.deck(r)
            assertEquals(size * 3, deck.size)
            assertEquals(1, deck.count { it is PlayingCard.Regular && it.rank == Rank.TWO })
            assertEquals(3, deck.count { it is PlayingCard.Regular && it.rank == Rank.ACE })
            assertEquals(if (size == 16) 4 else 1, deck.count { it is PlayingCard.Regular && it.rank == Rank.KING })
            for (seed in 0L until 20L) {
                val state = PaoDeKuai.start(r, seed)
                assertEquals(List(3) { size }, state.hands.map { it.size })
                assertEquals(deck.toSet(), state.hands.flatten().toSet())
                assertTrue(PaoDeKuai.STARTER in state.hands[state.starter])
                assertEquals(state.starter, state.turn)
            }
        }
        assertEquals(PaoDeKuai.start(rules, 5).hands, PaoDeKuai.start(rules, 5).hands)
    }

    @Test
    fun `presets are told apart by their rules`() {
        for (preset in PaoDeKuai.presets) assertEquals(preset.id, PaoDeKuai.presetIdOf(PdkRules.CODEC.encode(preset.rules)))
        assertEquals("sixteen", PaoDeKuai.presets.first().id)
    }

    @Test
    fun `the first play must hold the 3 of spades, and the leader may not pass`() {
        val start = PaoDeKuai.start(rules, 3)
        val hand = start.hands[start.turn]
        val other = hand.first { it != PaoDeKuai.STARTER }
        assertThrows<IllegalActionException> { PaoDeKuai.apply(start, start.turn, PdkAction.PlayCards(listOf(other))) }
        assertThrows<IllegalActionException> { PaoDeKuai.apply(start, start.turn, PdkAction.Pass) }
        val after = PaoDeKuai.apply(start, start.turn, PdkAction.PlayCards(listOf(PaoDeKuai.STARTER)))
        assertEquals(PaoDeKuai.next(start.turn), after.turn)
        // Every opening hint holds it too.
        assertTrue(PaoDeKuai.turnOf(start).plays.all { PaoDeKuai.STARTER in it.cards })
    }

    // ---- Patterns ------------------------------------------------------------------------------

    @Test
    fun `patterns read as Pao De Kuai has them`() {
        assertEquals(PdkPattern(PdkPatternType.PAIR_RUN, 5, 2), read("5 5 6 6"))
        assertEquals(PdkPattern(PdkPatternType.STRAIGHT, 10, 5), read("10 J Q K A"))
        assertNull(read("J Q K A 2"), "no 2 in a straight")
        assertNull(read("3 4 5 6"), "a straight needs five")
        assertEquals(PdkPattern(PdkPatternType.TRIO_TWO, 9), read("9 9 9 3 K"), "the two may be any cards")
        assertEquals(PdkPattern(PdkPatternType.TRIO_TWO, 9), read("9 9 9 4 4"))
        assertEquals(PdkPattern(PdkPatternType.PLANE, 7, 2), read("7 7 7 8 8 8 3 4 J Q"))
        assertNull(read("7 7 7 9 9 9 3 4 J Q"), "the threes must be in a row")
        assertEquals(PdkPattern(PdkPatternType.FOUR_THREE, 6), read("6 6 6 6 3 9 J"))
        assertNull(read("6 6 6 6 3 9 J", r = rules.copy(fourWithThree = false)))
        assertEquals(PdkPattern(PdkPatternType.BOMB, 10), read("10 10 10 10"))
        assertNull(read("6 6 6 6 3 9"), "no four with two")
    }

    @Test
    fun `a bomb beats anything but a bigger bomb`() {
        val bomb = read("5 5 5 5")!!
        for (text in listOf("2", "A A", "9 9 9 3 4", "3 4 5 6 7 8 9", "Q Q Q Q 3 4 5")) assertTrue(bomb.beats(read(text)!!), text)
        assertTrue(read("6 6 6 6")!!.beats(bomb))
        assertFalse(read("4 4 4 4")!!.beats(bomb))
        assertFalse(read("2")!!.beats(bomb))
    }

    @Test
    fun `a bare three, three with one, or a short plane only go out as the last cards`() {
        for (text in listOf("J J J", "J J J 5", "7 7 7 8 8 8", "7 7 7 8 8 8 4 9")) {
            assertNull(read(text), "$text in the middle of a hand")
            assertNotNull(read(text, lastCards = true), "$text as the last cards")
        }
        // As the last cards, a three with one may also beat a three with two.
        val s = state(hands("K", "J J J 5", "3 4 5 6"), turn = 1, lead = lead(0, "9 9 9 3 4"))
        val after = s.play("J J J 5")
        assertTrue(after.finished)
        assertEquals(1, after.settlement!!.winner)
        // Not when other cards are left.
        val s2 = state(hands("K", "J J J 5 8", "3 4 5 6"), turn = 1, lead = lead(0, "9 9 9 3 4"))
        assertThrows<IllegalActionException> { s2.play("J J J 5") }
    }

    // ---- Must beat -----------------------------------------------------------------------------

    @Test
    fun `with must beat, only someone who can't beat the table may pass`() {
        val s = state(hands("3 4", "8 Q", "5 6"), turn = 1, lead = lead(0, "9"))
        assertThrows<IllegalActionException> { s.pass() }
        val relaxed = s.copy(rules = PdkRules.RELAXED)
        assertEquals(2, relaxed.pass().turn)
        val nothing = state(hands("3 4", "5 6 7", "8 8"), turn = 1, lead = lead(0, "9"))
        assertEquals(2, nothing.pass().turn)
        // A bomb counts as something that beats.
        val bomb = state(hands("3 4", "5 5 5 5 7", "8 8"), turn = 1, lead = lead(0, "K K"))
        assertThrows<IllegalActionException> { bomb.pass() }
    }

    @Test
    fun `the hints never miss a play, so must beat is fair`() {
        // Every subset of random hands, checked against the hints for every kind of play to beat.
        val random = Random(4)
        val tables = listOf("9", "4 4", "5 5 6 6", "3 4 5 6 7", "6 6 6 3 4", "4 4 4 5 5 5 3 6 7 8", "5 5 5 5 3 4 6", "3 3 3 3")
            .map { PdkPatterns.readingFor(cards(it), null, rules, false)!! } + listOf(null)
        repeat(60) {
            val hand = PaoDeKuai.deck(rules).shuffled(random).take(random.nextInt(1, 11))
            for (toBeat in tables) {
                val any = (1 until (1 shl hand.size)).any { mask ->
                    val subset = hand.filterIndexed { i, _ -> mask shr i and 1 == 1 }
                    PdkPatterns.readingFor(subset, toBeat, rules, subset.size == hand.size) != null
                }
                val hinted = PdkPatterns.options(hand, toBeat, rules)
                assertEquals(any, hinted.isNotEmpty(), "$hand on $toBeat")
                for (play in hinted) assertNotNull(PdkPatterns.readingFor(play.cards, toBeat, rules, play.cards.size == hand.size), "$play")
            }
        }
    }

    // ---- One card left ------------------------------------------------------------------------

    @Test
    fun `when the next player has one card, a single must be your highest`() {
        val s = state(hands("3 4 5", "6 9 K", "8"), turn = 1, lead = lead(0, "5"))
        assertThrows<IllegalActionException> { s.play("6") }
        assertThrows<IllegalActionException> { s.play("9") }
        assertEquals(2, s.play("K").turn)
        assertEquals(listOf("K"), PaoDeKuai.turnOf(s).plays.map { it.cards.joinToString(" ") { c -> DdzValue.label(c.ddzValue) } })
        // Leading: other shapes are fine, a single only the highest.
        val leading = state(hands("3 4 5", "6 6 9 K", "8"), turn = 1)
        assertThrows<IllegalActionException> { leading.play("9") }
        leading.play("6 6")
        leading.play("K")
        // Not when it's the player after next who has one card.
        val far = state(hands("8", "6 9 K", "3 4 5"), turn = 1, lead = lead(2, "5"))
        far.play("6")
    }

    @Test
    fun `when the highest single can't beat the table, you may pass`() {
        val s = state(hands("3 4 5", "6 9 Q", "8"), turn = 1, lead = lead(0, "K"))
        assertTrue(PaoDeKuai.turnOf(s).canPass)
        val raised = state(hands("3 4 5", "6 A Q", "8"), turn = 1, lead = lead(0, "K"))
        assertFalse(PaoDeKuai.turnOf(raised).canPass)
        assertThrows<IllegalActionException> { raised.play("Q") }
    }

    // ---- Scoring ------------------------------------------------------------------------------

    @Test
    fun `losers pay a point a card, nothing for one card, double if they never played`() {
        val s = state(hands("3 4 5 6 7 8", "9", "J Q K"), turn = 1, lead = lead(0, "8"))
            .let { it.copy(playCounts = listOf(1, 1, 0)) }
        val done = s.play("9")
        val settlement = done.settlement!!
        assertEquals(1, settlement.winner)
        assertEquals(listOf(-6, 12, -6), settlement.cardScores)
        // One card left pays nothing.
        val t = state(hands("3", "9", "J Q K"), turn = 1, lead = lead(0, "8")).play("9").settlement!!
        assertEquals(listOf(0, 3, -3), t.scores)
        assertEquals(0, t.scores.sum())
        assertNull(done.let { PaoDeKuai.toAct(it) })
    }

    @Test
    fun `a bomb that wins its round collects from each other player`() {
        var s = state(hands("3 4", "6 6 6 6 9 J", "5 7"), turn = 1, lead = lead(0, "K K"))
        s = s.play("6 6 6 6")
        s = s.pass()
        assertEquals(listOf(0, 0, 0), s.bombs, "the round is not over yet")
        s = s.pass()
        assertEquals(listOf(0, 1, 0), s.bombs)
        assertEquals(1, s.turn)
        assertNull(PaoDeKuai.toBeat(s))
        // Nobody can beat the 9, and the J goes out.
        val end = s.play("9").pass().pass().play("J")
        val settlement = end.settlement!!
        assertEquals(listOf(-10, 20, -10), settlement.bombScores)
        assertEquals(listOf(-2, 4, -2), settlement.cardScores)
        assertEquals(0, settlement.scores.sum())
        // Without bomb points it counts for nothing.
        val plain = state(hands("3 4", "6 6 6 6", "5 7"), turn = 1, lead = lead(0, "K K"), r = rules.copy(bombPoints = 0))
        assertEquals(listOf(0, 0, 0), plain.play("6 6 6 6").settlement!!.bombScores)
        // Going out on a bomb wins its round at once.
        val out = state(hands("3 4", "6 6 6 6", "5 7"), turn = 1, lead = lead(0, "K K")).play("6 6 6 6").settlement!!
        assertEquals(listOf(-10, 20, -10), out.bombScores)
    }

    @Test
    fun `a beaten bomb collects nothing`() {
        var s = state(hands("3 4 5", "6 6 6 6 9", "8 8 8 8 7"), turn = 1, lead = lead(0, "K"))
        s = s.play("6 6 6 6").play("8 8 8 8")
        assertEquals(listOf(0, 0, 1), s.pass().pass().bombs)
    }

    // ---- Views and codecs ----------------------------------------------------------------------

    @Test
    fun `views hide other hands until the end, and survive the codec`() {
        val start = PaoDeKuai.start(rules, 11)
        val view = PaoDeKuai.view(start, 0)
        assertTrue(view.revealedHands.isEmpty())
        assertEquals(start.hands[0], view.hand)
        assertTrue(PaoDeKuai.view(start, null).hand.isEmpty())
        assertEquals(view, PdkCodecs.VIEW.decode(PdkCodecs.VIEW.encode(view)))
        val open = PaoDeKuai.openView(start, null)
        assertEquals(start.hands, open.revealedHands)
        for (action in listOf(PdkAction.Pass, PdkAction.PlayCards(cards("3 3 4")))) {
            assertEquals(action, PdkCodecs.ACTION.decode(PdkCodecs.ACTION.encode(action)))
        }
        assertEquals(PdkRules.FIFTEEN, PdkRules.CODEC.decode(PdkRules.CODEC.encode(PdkRules.FIFTEEN)))
        val ended = state(hands("3", "9", "J Q K"), turn = 1, lead = lead(0, "8")).play("9")
        val endView = PaoDeKuai.view(ended, 2)
        assertEquals(endView, PdkCodecs.VIEW.decode(PdkCodecs.VIEW.encode(endView)))
        assertEquals(3, endView.revealedHands.size)
    }

    @Test
    fun `the spade 2 is the only 2`() {
        assertTrue(PlayingCard.Regular(Rank.TWO, Suit.SPADES) in PaoDeKuai.deck(rules))
    }
}
