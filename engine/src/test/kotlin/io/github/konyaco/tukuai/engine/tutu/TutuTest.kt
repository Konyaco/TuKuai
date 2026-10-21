package io.github.konyaco.tukuai.engine.tutu

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

class TutuTest {
    /** The first unused physical card showing [faces]. */
    private fun cards(vararg faces: String): List<TutuCard> {
        val used = HashSet<TutuCard>()
        return faces.map { text ->
            TutuDeck.ALL.first { it !in used && it.face.toString() == text }.also { used += it }
        }
    }

    /**
     * A table set up by hand: [hands] for seats 0.., [top] on the discard pile, and [drawPile]
     * to draw from, its first card on top; without it the rest of the base deck.
     */
    private fun state(
        hands: List<List<String>>,
        top: String,
        rules: TutuRules = TutuRules(players = hands.size),
        color: TutuColor? = null,
        turn: Int = 0,
        drawPile: List<String>? = null,
    ): TutuState {
        val all = cards(*(hands.flatten() + top + (drawPile ?: emptyList())).toTypedArray())
        var i = 0
        val handCards = hands.map { h -> List(h.size) { all[i++] } }
        val topCard = all[i++]
        val pile = if (drawPile != null) all.drop(i).reversed() else TutuDeck.cards(rules).filter { it !in all }
        return TutuState(
            rules = rules,
            seed = 1,
            drawPile = pile,
            discard = listOf(topCard),
            hands = handCards,
            top = topCard.face,
            color = color ?: topCard.face.color!!,
            turn = turn,
            direction = 1,
            drawn = null,
            cacti = emptyList(),
            fuses = emptyList(),
            rolls = 0,
            log = emptyList(),
            settlement = null,
        )
    }

    private fun TutuState.card(seat: Int, face: String) = hands[seat].first { it.face.toString() == face }

    private fun TutuState.play(face: String, color: TutuColor? = null) =
        Tutu.apply(this, turn, TutuAction.Play(card(turn, face), color))

    private fun TutuState.totalCards() = drawPile.size + discard.size + hands.sumOf { it.size }

    @Test
    fun `the decks have 112 and 122 cards in 55 faces`() {
        assertEquals(112, TutuDeck.cards(TutuRules.CLASSIC).size)
        assertEquals(122, TutuDeck.cards(TutuRules.PARTY).size)
        assertEquals(55, TutuDeck.FACES.size)
        assertEquals(52, TutuDeck.HATCHES.size)
        val base = TutuDeck.cards(TutuRules.CLASSIC)
        for (color in TutuColor.entries) assertEquals(25, base.count { it.face.color == color })
        for (kind in listOf(TutuKind.EGG, TutuKind.TNT, TutuKind.CACTUS)) {
            assertEquals(4, base.count { it.face.kind == kind })
            assertEquals(6, TutuDeck.ALL.count { it.face.kind == kind })
        }
        assertEquals(4, base.count { it.face.kind == TutuKind.LIGHTNING })
        assertEquals(8, TutuDeck.ALL.count { it.face.kind == TutuKind.LIGHTNING })
        assertEquals(2, base.count { it.face.toString() == "B7" })
        assertTrue(base.none { it.face.kind == TutuKind.NUMBER && it.face.number == 0 })
    }

    @Test
    fun `a new hand deals seven cards each and turns up a number`() {
        for (preset in Tutu.presets) {
            for (seed in 0L until 50L) {
                val s = Tutu.start(preset.rules, seed)
                assertTrue(s.hands.all { it.size == 7 })
                assertEquals(TutuKind.NUMBER, s.top.kind)
                assertEquals(TutuDeck.cards(preset.rules).size, s.totalCards())
                assertEquals(TutuDeck.cards(preset.rules).toSet(), (s.drawPile + s.discard + s.hands.flatten()).toSet())
            }
        }
    }

    @Test
    fun `cards follow colour, number or symbol, and wild cards fit anything`() {
        val s = state(listOf(listOf("Y2", "B7", "G4", "BS", "WE", "WT", "WC"), listOf("G1")), top = "Y7")
        val playable = s.hands[0].filter { Tutu.playable(s, it) }.map { it.toString() }
        assertEquals(listOf("Y2", "B7", "WE", "WT", "WC"), playable)
        val onSnowball = state(listOf(listOf("BS", "B+2", "Y3"), listOf("G1")), top = "PS")
        assertEquals(listOf("BS"), onSnowball.hands[0].filter { Tutu.playable(onSnowball, it) }.map { it.toString() })
        assertThrows<IllegalActionException> { s.play("G4") }
    }

    @Test
    fun `TNT and cacti name the colour, and nothing matches them by symbol but more wilds`() {
        val s = state(listOf(listOf("WT", "B3"), listOf("P5", "Y7", "WC")), top = "Y7")
        assertThrows<IllegalActionException> { s.play("WT") }
        val lit = s.play("WT", TutuColor.PURPLE)
        assertEquals(TutuColor.PURPLE, lit.color)
        assertEquals(TutuKind.TNT, lit.top.kind)
        val playable = lit.hands[1].filter { Tutu.playable(lit, it) }.map { it.toString() }
        assertEquals(listOf("P5", "WC"), playable)
    }

    @Test
    fun `a snowball freezes the next player`() {
        val s = state(listOf(listOf("YS", "Y1"), listOf("G1"), listOf("G2")), top = "Y7")
        val after = s.play("YS")
        assertEquals(2, after.turn)
        assertEquals(TutuEventKind.SKIPPED, after.log.last().kind)
        assertEquals(1, after.log.last().seat)
    }

    @Test
    fun `a trident turns play around, and skips the other player between two`() {
        val s = state(listOf(listOf("YR", "Y1"), listOf("G1"), listOf("G2")), top = "Y7")
        val after = s.play("YR")
        assertEquals(-1, after.direction)
        assertEquals(2, after.turn)
        val two = state(listOf(listOf("YR", "Y1"), listOf("G1")), top = "Y7").play("YR")
        assertEquals(0, two.turn)
    }

    @Test
    fun `a shovel makes the next player draw two and miss a turn`() {
        val s = state(listOf(listOf("Y+2", "Y1"), listOf("G1"), listOf("G2")), top = "Y7", drawPile = listOf("B1", "B2", "B3"))
        val after = s.play("Y+2")
        assertEquals(3, after.hands[1].size)
        assertEquals(2, after.turn)
        assertEquals(1, after.drawPile.size)
    }

    @Test
    fun `lightning makes the next player draw until its colour or a wild card comes up`() {
        val s = state(
            listOf(listOf("YL", "Y1"), listOf("G1"), listOf("G2")),
            top = "Y7",
            drawPile = listOf("B1", "G3", "P4", "Y5", "B9", "B8"),
        )
        val after = s.play("YL")
        assertEquals(listOf("G1", "B1", "G3", "P4", "Y5"), after.hands[1].map { it.toString() })
        assertEquals(2, after.turn)
        val draw = after.log.first { it.kind == TutuEventKind.DRAW }
        assertEquals(4, draw.count)
        assertTrue(draw.forced)

        val wild = state(listOf(listOf("YL", "Y1"), listOf("G1")), top = "Y7", drawPile = listOf("B1", "WE", "Y5", "B2")).play("YL")
        assertEquals(listOf("G1", "B1", "WE"), wild.hands[1].map { it.toString() })
    }

    @Test
    fun `an egg hatches into a coloured card that takes effect`() {
        val faces = HashSet<TutuFace>()
        for (seed in 0L until 300L) {
            val s = state(listOf(listOf("WE", "Y1"), listOf("G1", "G2"), listOf("B1", "B2")), top = "Y7").copy(seed = seed)
            val after = s.play("WE")
            val face = after.log.first { it.kind == TutuEventKind.PLAY }.face!!
            assertFalse(face.isWild)
            assertEquals(face, after.top)
            assertEquals(face.color, after.color)
            assertEquals(1, after.rolls)
            when (face.kind) {
                TutuKind.SNOWBALL -> assertEquals(2, after.turn)
                TutuKind.SHOVEL -> assertEquals(4, after.hands[1].size)
                TutuKind.TRIDENT -> assertEquals(2, after.turn)
                TutuKind.NUMBER -> assertEquals(1, after.turn)
                else -> {}
            }
            faces += face
        }
        // Over 300 eggs nearly every one of the 52 faces should hatch at least once.
        assertTrue(faces.size > 45, "only ${faces.size} faces hatched")
        assertTrue(faces.any { it.kind != TutuKind.NUMBER })
    }

    @Test
    fun `a cactus is planted in the draw pile, and whoever draws it takes three more`() {
        val pile = (1..9).map { "B$it" } + (1..9).map { "P$it" } + (1..9).map { "B$it" } + (1..9).map { "P$it" }
        for (seed in 0L until 20L) {
            val s = state(listOf(listOf("WC", "Y1"), listOf("G1"), listOf("G2")), top = "Y7", drawPile = pile).copy(seed = seed)
            val planted = s.play("WC", TutuColor.GREEN)
            val depth = planted.cacti.single()
            assertTrue(depth in 1..pile.size)
            assertEquals(planted.drawPile.size - depth, Tutu.view(planted, 1).cactusIn)

            // Everyone draws and keeps until it springs.
            var t = planted
            var drawsBefore = 0
            while (t.cacti.isNotEmpty()) {
                val seat = t.turn
                val before = t.hands[seat].size
                val left = t.drawPile.size
                t = Tutu.apply(t, seat, TutuAction.Draw)
                if (t.drawn != null) t = Tutu.apply(t, seat, TutuAction.Pass)
                val drew = t.hands[seat].size - before
                if (t.cacti.isEmpty()) {
                    assertEquals(minOf(1 + Tutu.CACTUS_CARDS, left), drew)
                    assertTrue(t.log.any { it.kind == TutuEventKind.CACTUS && it.seat == seat })
                } else {
                    assertEquals(1, drew)
                    drawsBefore++
                }
            }
            assertEquals(planted.drawPile.size - depth, drawsBefore)
            assertEquals(s.totalCards(), t.totalCards())
        }
    }

    @Test
    fun `a cactus springs in the middle of a shovel, too`() {
        val s = state(listOf(listOf("Y+2", "Y1"), listOf("G1"), listOf("G2")), top = "Y7", drawPile = (1..9).map { "B$it" })
            .copy(cacti = listOf(8))
        val after = s.play("Y+2")
        // One card, then the cactus with 8 below it: three more, and the second card of the shovel.
        assertEquals(1 + 2 + Tutu.CACTUS_CARDS, after.hands[1].size)
        assertTrue(after.cacti.isEmpty())
    }

    @Test
    fun `TNT burns down a hidden fuse and blows up on someone's turn`() {
        val lengths = HashSet<Int>()
        for (seed in 0L until 80L) {
            val s = state(
                listOf(listOf("WT", "Y1"), listOf("G1", "G2"), listOf("B1", "B2"), listOf("P1", "P2")),
                top = "Y7",
            ).copy(seed = seed)
            var t = s.play("WT", TutuColor.YELLOW)
            if (t.fuses.isNotEmpty()) {
                assertEquals(1, Tutu.view(t, 2).bombs)
                assertTrue(Tutu.view(t, 2).fuses.isEmpty())
                assertEquals(t.fuses, Tutu.openView(t, null).fuses)
            }
            // Everyone draws and keeps; the bomb blows as the turn its fuse runs out on begins.
            var turns = 1
            while (t.log.none { it.kind == TutuEventKind.BOOM }) {
                val seat = t.turn
                t = Tutu.apply(t, seat, TutuAction.Draw)
                if (t.drawn != null) t = Tutu.apply(t, seat, TutuAction.Pass)
                turns++
            }
            val boom = t.log.last { it.kind == TutuEventKind.BOOM }
            assertEquals(t.turn, boom.seat)
            assertTrue(t.fuses.isEmpty())
            assertEquals(Tutu.TNT_CARDS, t.log[t.log.indexOf(boom) + 1].count)
            lengths += turns
        }
        // Fuses last one to eight turns at a table of four.
        assertEquals((1..8).toSet(), lengths)
    }

    @Test
    fun `two TNT blowing on one turn cost eight cards`() {
        val s = state(listOf(listOf("Y1", "Y2"), listOf("G1", "G2")), top = "Y7").copy(fuses = listOf(1, 1, 3))
        val after = s.play("Y1")
        assertEquals(2 + 2 * Tutu.TNT_CARDS, after.hands[1].size)
        assertEquals(listOf(2), after.fuses)
        assertEquals(2, after.log.first { it.kind == TutuEventKind.BOOM }.count)
    }

    @Test
    fun `a drawn card can be played at once or kept, and one that does not fit ends the turn`() {
        val s = state(listOf(listOf("B1", "B2"), listOf("G1")), top = "Y9", drawPile = listOf("Y4", "Y5"))
        val drew = Tutu.apply(s, 0, TutuAction.Draw)
        assertEquals(drew.hands[0].last(), drew.drawn)
        assertThrows<IllegalActionException> { drew.play("B1") }
        assertEquals(1, Tutu.apply(drew, 0, TutuAction.Pass).turn)
        assertEquals(1, drew.play("Y4").turn)

        val miss = Tutu.apply(state(listOf(listOf("B1", "B2"), listOf("G1")), top = "Y9", drawPile = listOf("P4", "P5")), 0, TutuAction.Draw)
        assertNull(miss.drawn)
        assertEquals(1, miss.turn)
    }

    @Test
    fun `the first to go out takes ten points a card from everyone else`() {
        val s = state(listOf(listOf("Y1"), listOf("G1", "GS"), listOf("WE", "B9", "B8")), top = "Y9")
        val end = s.play("Y1")
        val settlement = end.settlement!!
        assertEquals(setOf(0), settlement.winners)
        assertFalse(settlement.pileEmpty)
        assertEquals(listOf(0, 20, 30), settlement.handPoints)
        assertEquals(listOf(50, -20, -30), settlement.scores)
        assertNull(Tutu.toAct(end))
    }

    @Test
    fun `when the draw pile runs out the fewest cards win`() {
        val s = state(listOf(listOf("B1", "B2"), listOf("G1", "G2", "G3"), listOf("P1", "P2")), top = "Y9", drawPile = listOf("P4"))
        val end = Tutu.apply(s, 0, TutuAction.Draw)
        val settlement = end.settlement!!
        assertTrue(settlement.pileEmpty)
        assertEquals(setOf(2), settlement.winners)
        assertEquals(listOf(30, 30, 0), settlement.handPoints)
        assertEquals(listOf(-30, -30, 60), settlement.scores)

        // A tie: each winner collects from every loser.
        val tie = Tutu.apply(state(listOf(listOf("B1"), listOf("G1", "G2"), listOf("P1", "P2")), top = "Y9", drawPile = listOf("P4")), 0, TutuAction.Draw)
        assertEquals(setOf(0, 1, 2), tie.settlement!!.winners)
        assertEquals(listOf(0, 0, 0), tie.settlement!!.scores)
        val split = Tutu.apply(state(listOf(listOf("B1", "B2"), listOf("G1", "G2"), listOf("P1", "P2", "P3")), top = "Y9", drawPile = listOf("Y4")), 0, TutuAction.Draw)
        assertEquals(setOf(1), split.settlement!!.winners)
    }

    @Test
    fun `spectators and other seats never see a hand or a fuse`() {
        val s = Tutu.start(TutuRules.CLASSIC, 5).copy(fuses = listOf(3))
        val spectator = Tutu.view(s, null)
        assertTrue(spectator.hand.isEmpty())
        assertTrue(spectator.revealedHands.isEmpty())
        assertTrue(spectator.fuses.isEmpty())
        assertEquals(1, spectator.bombs)
        val drew = Tutu.apply(s, s.turn, TutuAction.Draw)
        if (drew.drawn != null) assertNull(Tutu.view(drew, (drew.turn + 1) % 4).drawn)
    }

    @Test
    fun `views and actions survive the wire`() {
        val match = playOut(TutuRules.PARTY, 3, List(4) { BotLevel.NORMAL }, stopAt = 30)
        for (seat in listOf(null, 0, 1, 2, 3)) {
            val view = match.game.view(match.state, seat)
            assertEquals(view, TutuCodecs.VIEW.decode(TutuCodecs.VIEW.encode(view)))
        }
        val open = Tutu.openView(match.state.copy(fuses = listOf(2, 5), cacti = listOf(4)), 1)
        assertEquals(open, TutuCodecs.VIEW.decode(TutuCodecs.VIEW.encode(open)))
        val actions = listOf(TutuAction.Play(TutuCard(104), TutuColor.BLUE), TutuAction.Play(TutuCard(3)), TutuAction.Draw, TutuAction.Pass)
        for (action in actions) assertEquals(action, TutuCodecs.ACTION.decode(TutuCodecs.ACTION.encode(action)))
    }

    private fun playOut(rules: TutuRules, seed: Long, levels: List<BotLevel>, stopAt: Int = Int.MAX_VALUE): Match<TutuRules, TutuState, TutuAction, TutuView> {
        val match = Match(Tutu, rules, seed)
        val total = TutuDeck.cards(rules).size
        var guard = 0
        while (match.toAct != null && match.step < stopAt) {
            val seat = match.toAct!!
            match.playBot(seat, levels[seat])
            assertEquals(total, match.state.totalCards())
            check(++guard < 3000) { "Game $seed did not end" }
        }
        return match
    }

    @Test
    fun `bots finish every game under every rule set, and points add up to zero`() {
        for (preset in Tutu.presets) {
            var emptied = 0
            val games = 150
            for (seed in 0L until games) {
                val levels = List(preset.rules.players) { BotLevel.entries[((seed + it) % 3).toInt()] }
                val match = playOut(preset.rules, seed, levels)
                val result = match.result!!
                assertEquals(0, result.scores.sum(), "seed $seed")
                assertTrue(result.winners.isNotEmpty())
                if (match.state.settlement!!.pileEmpty) emptied++
            }
            println("${preset.id}: the draw pile ran out in $emptied of $games hands")
        }
    }

    @Test
    fun `replays rebuild the same game`() {
        val match = playOut(TutuRules.PARTY, 77, List(4) { BotLevel.HARD })
        val replay = Replay.decode(match.replay(listOf("a", "b", "c", "d")).encode())
        assertEquals(match.state, replay.rebuild(Tutu).state)
    }

    /** Points a [level] computer in rotating seats earns against [baseline] computers. */
    private fun gain(level: BotLevel, baseline: BotLevel, games: Int): Int {
        var gain = 0
        for (game in 0 until games) {
            val seat = game % 4
            gain += playOut(TutuRules.CLASSIC, 5000L + game, List(4) { if (it == seat) level else baseline }).result!!.scores[seat]
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
