package io.github.konyaco.tukuai.engine.uno

import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.Match
import io.github.konyaco.tukuai.engine.core.Replay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UnoTest {
    /** The first unused physical card showing [face]. */
    private fun cards(vararg faces: String): List<UnoCard> {
        val used = HashSet<UnoCard>()
        return faces.map { text ->
            UnoDeck.ALL.first { it !in used && it.face.toString() == text }.also { used += it }
        }
    }

    /** A table set up by hand: [hands] for seats 0.., [top] on the discard pile, the rest of the deck to draw from. */
    private fun state(
        hands: List<List<String>>,
        top: String,
        rules: UnoRules = UnoRules(players = hands.size),
        color: UnoColor? = null,
        turn: Int = 0,
        pending: Int = 0,
        drawPile: List<String>? = null,
    ): UnoState {
        val all = cards(*(hands.flatten() + top + (drawPile ?: emptyList())).toTypedArray())
        var i = 0
        val handCards = hands.map { h -> List(h.size) { all[i++] } }
        val topCard = all[i++]
        val pile = if (drawPile != null) all.drop(i).reversed() else UnoDeck.ALL.filter { it !in all }
        return UnoState(
            rules = rules,
            seed = 1,
            drawPile = pile,
            discard = listOf(topCard),
            hands = handCards,
            color = color ?: topCard.face.color!!,
            turn = turn,
            direction = 1,
            pendingDraw = pending,
            drawn = null,
            reshuffles = 0,
            idleTurns = 0,
            log = emptyList(),
            settlement = null,
        )
    }

    private fun UnoState.card(seat: Int, face: String) = hands[seat].first { it.face.toString() == face }

    private fun UnoState.play(face: String, color: UnoColor? = null, uno: Boolean = false) =
        Uno.apply(this, turn, UnoAction.Play(card(turn, face), color, uno))

    private fun UnoState.totalCards() = drawPile.size + discard.size + hands.sumOf { it.size }

    @Test
    fun `the deck has 108 cards in 54 faces`() {
        assertEquals(108, UnoDeck.ALL.size)
        assertEquals(54, UnoDeck.FACES.size)
        for (color in UnoColor.entries) assertEquals(25, UnoDeck.ALL.count { it.face.color == color })
        assertEquals(4, UnoDeck.ALL.count { it.face.kind == UnoKind.WILD })
        assertEquals(4, UnoDeck.ALL.count { it.face.kind == UnoKind.WILD_DRAW_FOUR })
        assertEquals(1, UnoDeck.ALL.count { it.face.toString() == "R0" })
        assertEquals(2, UnoDeck.ALL.count { it.face.toString() == "B7" })
    }

    @Test
    fun `a new hand deals seven cards each and turns up a number`() {
        for (preset in Uno.presets) {
            for (seed in 0L until 50L) {
                val s = Uno.start(preset.rules, seed)
                assertTrue(s.hands.all { it.size == 7 })
                assertEquals(UnoKind.NUMBER, s.top.face.kind)
                assertEquals(108, s.totalCards())
                assertEquals(108, (s.drawPile + s.discard + s.hands.flatten()).toSet().size)
            }
        }
    }

    @Test
    fun `cards follow colour, number or symbol`() {
        val s = state(listOf(listOf("R3", "B5", "G9", "YS", "W", "G+2"), listOf("R1")), top = "B9")
        val playable = s.hands[0].filter { Uno.playable(s, it) }.map { it.face.toString() }
        assertEquals(listOf("B5", "G9", "W"), playable)
        val skip = state(listOf(listOf("GS", "B1"), listOf("R1")), top = "RS")
        assertTrue(Uno.playable(skip, skip.card(0, "GS")))
        assertFalse(Uno.playable(skip, skip.card(0, "B1")))
    }

    @Test
    fun `a wild draw four needs a hand with nothing of the colour`() {
        val s = state(listOf(listOf("W+4", "R3"), listOf("R1")), top = "R9")
        assertFalse(Uno.playable(s, s.card(0, "W+4")))
        val free = state(listOf(listOf("W+4", "B3"), listOf("R1")), top = "R9")
        assertTrue(Uno.playable(free, free.card(0, "W+4")))
        val loose = state(listOf(listOf("W+4", "R3"), listOf("R1")), top = "R9", rules = UnoRules(players = 2, strictWildFour = false))
        assertTrue(Uno.playable(loose, loose.card(0, "W+4")))
    }

    @Test
    fun `a wild card names the colour`() {
        val s = state(listOf(listOf("W", "R3"), listOf("B1", "B2"), listOf("G1")), top = "R9")
        assertThrows<IllegalActionException> { s.play("W") }
        val after = s.play("W", UnoColor.GREEN)
        assertEquals(UnoColor.GREEN, after.color)
        assertEquals(1, after.turn)
    }

    @Test
    fun `skip, reverse and draw two`() {
        val hands = listOf(listOf("RS", "RR", "R+2", "R1"), listOf("B1", "B2"), listOf("G1", "G2"), listOf("Y1", "Y2"))
        val skip = state(hands, top = "R9").play("RS")
        assertEquals(2, skip.turn)

        val reverse = state(hands, top = "R9").play("RR")
        assertEquals(-1, reverse.direction)
        assertEquals(3, reverse.turn)

        val drawTwo = state(hands, top = "R9").play("R+2")
        assertEquals(4, drawTwo.hands[1].size)
        assertEquals(2, drawTwo.turn)
    }

    @Test
    fun `between two players a reverse skips the other player`() {
        val s = state(listOf(listOf("RR", "R1"), listOf("B1", "B2")), top = "R9").play("RR")
        assertEquals(0, s.turn)
    }

    @Test
    fun `with stacking a draw two can be passed on`() {
        val rules = UnoRules(stacking = true, players = 3)
        val hands = listOf(listOf("R+2", "R1"), listOf("B+2", "B1"), listOf("G1", "G2"))
        var s = state(hands, top = "R9", rules = rules).play("R+2")
        assertEquals(2, s.pendingDraw)
        assertEquals(1, s.turn)
        assertFalse(Uno.playable(s, s.card(1, "B1")))
        s = s.play("B+2")
        assertEquals(4, s.pendingDraw)
        assertEquals(2, s.turn)
        s = Uno.apply(s, 2, UnoAction.Draw)
        assertEquals(6, s.hands[2].size)
        assertEquals(0, s.pendingDraw)
        assertEquals(0, s.turn)
    }

    @Test
    fun `a drawn card can be played at once or kept`() {
        val s = state(listOf(listOf("B1", "B2"), listOf("G1")), top = "R9", drawPile = listOf("R4", "Y4"))
        val drew = Uno.apply(s, 0, UnoAction.Draw)
        assertEquals("R4", drew.drawn.toString())
        assertEquals(0, drew.turn)
        assertThrows<IllegalActionException> { drew.play("B1") }
        val kept = Uno.apply(drew, 0, UnoAction.Pass)
        assertEquals(1, kept.turn)
        assertEquals(3, kept.hands[0].size)

        val played = drew.play("R4")
        assertEquals(2, played.hands[0].size)
        assertEquals(1, played.turn)
    }

    @Test
    fun `a drawn card that does not fit ends the turn`() {
        val s = state(listOf(listOf("B1", "B2"), listOf("G1")), top = "R9", drawPile = listOf("Y4"))
        val drew = Uno.apply(s, 0, UnoAction.Draw)
        assertNull(drew.drawn)
        assertEquals(1, drew.turn)
    }

    @Test
    fun `forgetting to call UNO costs two cards`() {
        val s = state(listOf(listOf("R1", "R2"), listOf("G1")), top = "R9")
        assertEquals(3, s.play("R1").hands[0].size)
        val called = s.play("R1", uno = true)
        assertEquals(1, called.hands[0].size)
        assertEquals(setOf(0), Uno.view(called, 1).unoCalled)
    }

    @Test
    fun `the winner takes what is left in the other hands`() {
        val s = state(listOf(listOf("R1"), listOf("G1", "GS"), listOf("W", "B9")), top = "R9")
        val end = s.play("R1")
        val settlement = end.settlement!!
        assertEquals(0, settlement.winner)
        assertEquals(listOf(0, 21, 59), settlement.handPoints)
        assertEquals(listOf(80, -21, -59), settlement.scores)
        assertNull(Uno.toAct(end))
    }

    @Test
    fun `going out on a draw two still makes the next player draw`() {
        val s = state(listOf(listOf("R+2"), listOf("G1")), top = "R9")
        val end = s.play("R+2")
        assertEquals(3, end.hands[1].size)
        val points = end.hands[1].sumOf { it.face.points }
        assertEquals(listOf(points, -points), end.settlement!!.scores)
    }

    @Test
    fun `the discard pile is shuffled back when the deck runs out`() {
        val s = state(listOf(listOf("B1"), listOf("G1")), top = "R9", drawPile = emptyList())
            .let { it.copy(discard = cards("Y1", "Y2", "Y3") + it.discard) }
        val drew = Uno.apply(s, 0, UnoAction.Draw)
        assertEquals(1, drew.discard.size)
        assertEquals(2, drew.hands[0].size)
        assertEquals(2, drew.drawPile.size)
        assertEquals(1, drew.reshuffles)
        assertEquals(s.totalCards(), drew.totalCards())
    }

    @Test
    fun `spectators and other seats never see a hand`() {
        val s = Uno.start(UnoRules.CLASSIC, 5)
        val spectator = Uno.view(s, null)
        assertTrue(spectator.hand.isEmpty())
        assertTrue(spectator.revealedHands.isEmpty())
        val drew = Uno.apply(s, s.turn, UnoAction.Draw)
        if (drew.drawn != null) assertNull(Uno.view(drew, (drew.turn + 1) % 4).drawn)
    }

    @Test
    fun `views and actions survive the wire`() {
        val match = playOut(UnoRules.STACKING, 3, List(4) { BotLevel.NORMAL }, stopAt = 20)
        for (seat in listOf(null, 0, 1, 2, 3)) {
            val view = match.view(seat)
            assertEquals(view, UnoCodecs.VIEW.decode(UnoCodecs.VIEW.encode(view)))
        }
        val actions = listOf(UnoAction.Play(UnoCard(104), UnoColor.BLUE, uno = true), UnoAction.Draw, UnoAction.Pass)
        for (action in actions) assertEquals(action, UnoCodecs.ACTION.decode(UnoCodecs.ACTION.encode(action)))
    }

    private fun playOut(rules: UnoRules, seed: Long, levels: List<BotLevel>, stopAt: Int = Int.MAX_VALUE): Match<UnoRules, UnoState, UnoAction, UnoView> {
        val match = Match(Uno, rules, seed)
        var guard = 0
        while (match.toAct != null && match.step < stopAt) {
            val seat = match.toAct!!
            match.playBot(seat, levels[seat])
            assertEquals(108, match.state.totalCards())
            check(++guard < 3000) { "Game $seed did not end" }
        }
        return match
    }

    @Test
    fun `bots finish every game under every rule set, and points add up to zero`() {
        for (preset in Uno.presets) {
            for (seed in 0L until 150L) {
                val levels = List(preset.rules.players) { BotLevel.entries[((seed + it) % 3).toInt()] }
                val result = playOut(preset.rules, seed, levels).result!!
                assertEquals(0, result.scores.sum(), "seed $seed")
                assertEquals(1, result.winners.size)
            }
        }
    }

    @Test
    fun `replays rebuild the same game`() {
        val match = playOut(UnoRules.CLASSIC, 77, List(4) { BotLevel.HARD })
        val replay = Replay.decode(match.replay(listOf("a", "b", "c", "d")).encode())
        assertEquals(match.state, replay.rebuild(Uno).state)
    }

    /** Points a [level] computer in rotating seats earns against [baseline] computers. */
    private fun gain(level: BotLevel, baseline: BotLevel, games: Int): Int {
        var gain = 0
        for (game in 0 until games) {
            val seat = game % 4
            gain += playOut(UnoRules.CLASSIC, 5000L + game, List(4) { if (it == seat) level else baseline }).result!!.scores[seat]
        }
        return gain
    }

    @Test
    fun `better computers win more`() {
        val normal = gain(BotLevel.NORMAL, BotLevel.EASY, 400)
        val hard = gain(BotLevel.HARD, BotLevel.NORMAL, 400)
        println("Normal over easy: $normal, hard over normal: $hard, across 400 hands")
        assertTrue(normal > 0, "normal computer fell behind easy by $normal points")
        assertTrue(hard > 0, "hard computer fell behind normal by $hard points")
    }
}
