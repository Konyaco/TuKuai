package io.github.konyaco.tukuai.engine.bluff

import io.github.konyaco.tukuai.engine.bluff.bot.BluffBot
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.Match
import io.github.konyaco.tukuai.engine.core.Replay
import io.github.konyaco.tukuai.engine.doudizhu.DdzGuide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BluffTest {
    private fun cards(text: String): List<PlayingCard> = DdzGuide.cards(text)

    /** A round set up by hand: [hands] for seats 0.., queens called for, seat [turn] to play. */
    private fun state(
        vararg hands: String,
        rules: BluffRules = BluffRules(players = hands.size),
        fuses: List<Int> = List(hands.size) { 6 },
        lit: List<Int> = List(hands.size) { 0 },
        turn: Int = 0,
    ) = BluffState(
        rules = rules, seed = 9, round = 1, target = Rank.QUEEN,
        hands = hands.map { if (it.isEmpty()) emptyList() else cards(it) },
        fuses = fuses, lit = lit, phase = BluffPhase.PLAY, turn = turn,
        claim = null, pile = emptyList(), reveal = null, log = emptyList(),
    )

    private fun BluffState.play(seat: Int, text: String) = Bluff.apply(this, seat, BluffAction.Play(cards(text)))

    @Test
    fun `the deck takes ranks from the ace down until every hand can be dealt`() {
        assertEquals(listOf(Rank.ACE, Rank.KING, Rank.QUEEN, Rank.JACK, Rank.TEN), Bluff.ranks(BluffRules.CLASSIC))
        assertEquals(22, Bluff.deck(BluffRules.CLASSIC).size)
        assertEquals(18, Bluff.deck(BluffRules.THREE).size)
        assertEquals(30, Bluff.deck(BluffRules.SIX).size)
        assertEquals(14, Bluff.deck(BluffRules(players = 2)).size)
        assertEquals(20, Bluff.deck(BluffRules(jokers = false)).size)
        for (players in 2..8) {
            val rules = BluffRules(players = players)
            val deck = Bluff.deck(rules)
            assertEquals(deck.size, deck.toSet().size)
            assertTrue(deck.size >= players * rules.handSize, "$players players")
        }
    }

    @Test
    fun `a new game deals five each and calls for a rank in the deck`() {
        for (seed in 0L until 20) {
            val s = Bluff.start(BluffRules.CLASSIC, seed)
            assertTrue(s.hands.all { it.size == 5 })
            assertEquals(20, s.hands.flatten().toSet().size)
            assertTrue(s.target in Bluff.ranks(s.rules))
            assertTrue(s.fuses.all { it in 1..6 })
            assertEquals(BluffEventKind.DEAL, s.log.single().kind)
        }
        assertTrue(Bluff.start(BluffRules.HEARTS, 1).fuses.all { it == 3 })
    }

    @Test
    fun `a play lays one to three cards from the hand and passes to the next`() {
        val s = state("Q Q K A 10", "K K A J J", "Q 10 10 J A")
        assertThrows<IllegalActionException> { Bluff.apply(s, 0, BluffAction.Play(emptyList())) }
        assertThrows<IllegalActionException> { s.play(0, "Q Q K A") }
        assertThrows<IllegalActionException> { s.play(0, "J") }
        assertThrows<IllegalActionException> { s.play(1, "K") }
        assertThrows<IllegalActionException> { Bluff.apply(s, 0, BluffAction.Call) }
        val after = s.play(0, "Q K")
        assertEquals(1, after.turn)
        assertEquals(3, after.hands[0].size)
        assertEquals(2, after.pile.size)
        assertEquals(BluffClaim(0, cards("Q K")), after.claim)
    }

    @Test
    fun `calling a lie makes the liar light the fuse`() {
        val s = state("Q Q K A 10", "K K A J J", "Q 10 10 J A").play(0, "Q K")
        val called = Bluff.apply(s, 1, BluffAction.Call)
        assertEquals(BluffPhase.PENALTY, called.phase)
        assertTrue(called.reveal!!.lied)
        assertEquals(0, Bluff.toAct(called))
        assertThrows<IllegalActionException> { Bluff.apply(called, 1, BluffAction.Light) }
        assertThrows<IllegalActionException> { called.play(0, "Q") }
        val lit = Bluff.apply(called, 0, BluffAction.Light)
        assertEquals(listOf(1, 0, 0), lit.lit)
        // The loser deals the next round and plays first.
        assertEquals(2, lit.round)
        assertEquals(0, lit.turn)
        assertTrue(lit.hands.all { it.size == 5 })
        assertNull(lit.claim)
    }

    @Test
    fun `calling an honest play a lie makes the caller light the fuse, and jokers count`() {
        val s = state("Q RJ K A 10", "K K A J J", "Q 10 10 J A").play(0, "Q RJ")
        val called = Bluff.apply(s, 1, BluffAction.Call)
        assertFalse(called.reveal!!.lied)
        assertEquals(1, Bluff.toAct(called))
        val lit = Bluff.apply(called, 1, BluffAction.Light)
        assertEquals(listOf(0, 1, 0), lit.lit)
        assertEquals(1, lit.turn)
    }

    @Test
    fun `a creeper blows on its fuse, and the next one in starts the round`() {
        val s = state("Q K", "K A", "A A", rules = BluffRules(players = 3, handSize = 2, maxPlay = 2), fuses = listOf(6, 2, 6), lit = listOf(0, 1, 0))
            .play(0, "Q")
        val called = Bluff.apply(Bluff.apply(s, 1, BluffAction.Call), 1, BluffAction.Light)
        assertTrue(called.isOut(1))
        assertTrue(called.log.last { it.kind == BluffEventKind.LIGHT }.flag)
        assertEquals(2, called.turn)
        assertTrue(called.hands[1].isEmpty())
        // Seat 1 is skipped from now on.
        val next = Bluff.apply(called, 2, BluffAction.Play(listOf(called.hands[2].first())))
        assertEquals(0, next.turn)
    }

    @Test
    fun `players without cards are skipped, and when nobody else has any the next must call`() {
        val s = state("Q", "K K", "A A A")
        val one = s.play(0, "Q")
        assertEquals(1, one.turn)
        val two = one.play(1, "K K")
        assertEquals(2, two.turn)
        val three = two.play(2, "A A A")
        // Nobody but seat 2 had cards; seat 0 has none and must call.
        assertEquals(0, three.turn)
        assertTrue(Bluff.view(three, 0).mustCall)
        assertThrows<IllegalActionException> { Bluff.apply(three, 0, BluffAction.Play(emptyList())) }
        assertTrue(Bluff.apply(three, 0, BluffAction.Call).reveal!!.lied)
    }

    @Test
    fun `the last one standing wins a point from everyone else`() {
        val s = state("Q K", "K A", rules = BluffRules(players = 2, handSize = 2, maxPlay = 2), fuses = listOf(6, 1)).play(0, "Q")
        val over = Bluff.apply(Bluff.apply(s, 1, BluffAction.Call), 1, BluffAction.Light)
        assertEquals(BluffPhase.OVER, over.phase)
        assertNull(Bluff.toAct(over))
        assertEquals(setOf(0), over.result!!.winners)
        assertEquals(listOf(1, -1), over.result!!.scores)
        assertEquals(0, Bluff.view(over, null).winner)
    }

    @Test
    fun `with hearts a player is out after losing them all`() {
        val s = state("Q K", "K A", rules = BluffRules(players = 2, handSize = 2, maxPlay = 2, penalty = BluffPenalty.HEARTS, chances = 2), fuses = listOf(2, 2), lit = listOf(0, 1))
            .play(0, "Q")
        val over = Bluff.apply(Bluff.apply(s, 1, BluffAction.Call), 1, BluffAction.Light)
        assertTrue(over.finished)
        assertEquals(0, Bluff.view(over, 0).heartsLeft(1))
    }

    @Test
    fun `nobody sees another hand, another play's cards or a fuse`() {
        val s = state("Q Q K A 10", "K K A J J", "Q 10 10 J A").play(0, "K")
        for (seat in listOf(null, 1, 2)) {
            val view = Bluff.view(s, seat)
            assertTrue(view.fuses.isEmpty())
            assertTrue(view.revealedHands.isEmpty())
            assertTrue(view.claimCards.isEmpty())
            assertTrue(view.log.all { it.cards.isEmpty() })
            assertEquals(1, view.claimCount)
        }
        assertEquals(cards("K"), Bluff.view(s, 0).claimCards)
        assertEquals(cards("K"), Bluff.view(s, 0).myPlayed)
        val open = Bluff.openView(s, 1)
        assertEquals(cards("K"), open.claimCards)
        assertEquals(3, open.revealedHands.size)
        // Once the play is called it is turned over for all.
        assertEquals(cards("K"), Bluff.view(Bluff.apply(s, 1, BluffAction.Call), 2).reveal!!.cards)
    }

    @Test
    fun `the book's example plays are what it says`() {
        for ((name, cards, honest) in BluffGuide.PLAYS) {
            assertEquals(honest, cards.all { Bluff.matches(it, BluffGuide.TARGET) }, name)
            assertTrue(cards.size <= BluffRules.CLASSIC.maxPlay, name)
        }
    }

    @Test
    fun `the computer is sure of a lie when it can see too many of the called rank`() {
        // Seat 1 holds all four queens, so seat 0 cannot have three queens or jokers between its cards.
        val s = state("K K A A 10", "Q Q Q Q 10", "A K J J 10", rules = BluffRules(players = 3, jokers = false)).play(0, "K")
        assertEquals(1.0, BluffBot(BotLevel.NORMAL, 0).lieChance(Bluff.view(s, 1)))
        assertEquals(BluffAction.Call, BluffBot(BotLevel.NORMAL, 0).act(Bluff.view(s, 1)))
        assertEquals(1.0, BluffBot.atLeast(10, 10, 3, 3), 1e-9)
        assertEquals(0.0, BluffBot.atLeast(10, 1, 3, 2), 1e-9)
    }

    @Test
    fun `views and actions survive the wire`() {
        val match = playOut(BluffRules.CLASSIC, 3, List(4) { BotLevel.NORMAL }, stopAt = 12)
        for (seat in listOf(null, 0, 1, 2, 3)) {
            val view = match.game.view(match.state, seat)
            assertEquals(view, BluffCodecs.VIEW.decode(BluffCodecs.VIEW.encode(view)))
            val open = match.game.openView(match.state, seat)
            assertEquals(open, BluffCodecs.VIEW.decode(BluffCodecs.VIEW.encode(open)))
        }
        for (action in listOf(BluffAction.Play(cards("Q 10 BJ")), BluffAction.Call, BluffAction.Light)) {
            assertEquals(action, BluffCodecs.ACTION.decode(BluffCodecs.ACTION.encode(action)))
        }
        for (preset in Bluff.presets) assertEquals(preset.rules, BluffRules.CODEC.decode(BluffRules.CODEC.encode(preset.rules)))
    }

    private fun playOut(rules: BluffRules, seed: Long, levels: List<BotLevel>, stopAt: Int = Int.MAX_VALUE): Match<BluffRules, BluffState, BluffAction, BluffView> {
        val match = Match(Bluff, rules, seed)
        var guard = 0
        while (match.toAct != null && match.step < stopAt) {
            val seat = match.toAct!!
            match.playBot(seat, levels[seat])
            check(++guard < 3000) { "Game $seed did not end" }
        }
        return match
    }

    @Test
    fun `bots finish every game under every rule set, and points add up to zero`() {
        for (preset in Bluff.presets) {
            var rounds = 0
            val games = 200
            for (seed in 0L until games) {
                val levels = List(preset.rules.players) { BotLevel.entries[((seed + it) % 3).toInt()] }
                val match = playOut(preset.rules, seed, levels)
                val result = match.result!!
                assertEquals(0, result.scores.sum(), "seed $seed")
                assertEquals(1, result.winners.size)
                assertEquals(1, match.state.alive.size)
                rounds += match.state.round
            }
            println("${preset.id}: ${rounds.toDouble() / games} rounds a game")
        }
    }

    @Test
    fun `replays rebuild the same game`() {
        val match = playOut(BluffRules.CLASSIC, 77, List(4) { BotLevel.HARD })
        val replay = Replay.decode(match.replay(listOf("a", "b", "c", "d")).encode())
        assertEquals(match.state, replay.rebuild(Bluff).state)
    }

    /** Points a [level] computer in rotating seats earns against [baseline] computers. */
    private fun gain(level: BotLevel, baseline: BotLevel, games: Int): Int {
        var gain = 0
        for (game in 0 until games) {
            val seat = game % 4
            gain += playOut(BluffRules.CLASSIC, 5000L + game, List(4) { if (it == seat) level else baseline }).result!!.scores[seat]
        }
        return gain
    }

    @Test
    fun `better computers win more`() {
        val normal = gain(BotLevel.NORMAL, BotLevel.EASY, 800)
        val hard = gain(BotLevel.HARD, BotLevel.NORMAL, 800)
        println("Normal over easy: $normal, hard over normal: $hard, across 800 games")
        assertTrue(normal > 0, "normal computer fell behind easy by $normal points")
        assertTrue(hard > 0, "hard computer fell behind normal by $hard points")
    }
}
