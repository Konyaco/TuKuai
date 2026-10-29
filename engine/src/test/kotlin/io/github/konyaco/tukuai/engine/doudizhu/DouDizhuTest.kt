package io.github.konyaco.tukuai.engine.doudizhu

import io.github.konyaco.tukuai.engine.card.Decks
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.presetIdOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DouDizhuTest {
    private fun DdzState.act(action: DdzAction) = DouDizhu.apply(this, turn, action)

    @Test
    fun `deal gives 17 cards each and 3 to the kitty, with no card twice`() {
        for (mode in DealMode.entries) {
            val state = DouDizhu.start(DdzRules(deal = mode), seed = 42)
            assertEquals(listOf(17, 17, 17), state.hands.map { it.size })
            assertEquals(3, state.kitty.size)
            assertEquals(Decks.STANDARD_54.toSet(), (state.hands.flatten() + state.kitty).toSet())
        }
    }

    @Test
    fun `same seed deals the same cards`() {
        assertEquals(DouDizhu.start(DdzRules.CLASSIC, 7).hands, DouDizhu.start(DdzRules.CLASSIC, 7).hands)
    }

    @Test
    fun `a bid of 3 makes the landlord at once`() {
        val start = DouDizhu.start(DdzRules.CLASSIC, 1)
        val first = start.turn
        val after = start.act(DdzAction.Bid(3))
        assertEquals(first, after.landlord)
        assertEquals(DdzPhase.PLAYING, after.phase)
        assertEquals(first, after.turn)
        assertEquals(20, after.hands[first].size)
        assertEquals(3, after.bidMultiplier)
    }

    @Test
    fun `highest bid wins after everyone has spoken`() {
        val start = DouDizhu.start(DdzRules.CLASSIC, 2)
        val s1 = start.act(DdzAction.Bid(1))
        assertThrows<IllegalActionException> { s1.act(DdzAction.Bid(1)) }
        val s2 = s1.act(DdzAction.Bid(2)).act(DdzAction.Bid(0))
        assertEquals((start.turn + 1) % 3, s2.landlord)
        assertEquals(2, s2.bidMultiplier)
    }

    @Test
    fun `nobody bidding redeals, and too many redeals force the first bidder`() {
        var state = DouDizhu.start(DdzRules.CLASSIC, 3)
        val firstHands = state.hands
        state = state.act(DdzAction.Bid(0)).act(DdzAction.Bid(0)).act(DdzAction.Bid(0))
        assertEquals(1, state.redeals)
        assertEquals(DdzPhase.BIDDING, state.phase)
        assertTrue(state.hands != firstHands)
        repeat(DdzRules.CLASSIC.redealLimit) {
            state = state.act(DdzAction.Bid(0)).act(DdzAction.Bid(0)).act(DdzAction.Bid(0))
        }
        assertEquals(DdzPhase.PLAYING, state.phase)
        assertEquals(state.firstBidder, state.landlord)
    }

    @Test
    fun `robbing doubles the stake and the caller may rob back`() {
        val start = DouDizhu.start(DdzRules.ROB, 4)
        val caller = start.turn
        var state = start.act(DdzAction.Call(true)).act(DdzAction.Call(true)).act(DdzAction.Call(false))
        assertEquals(caller, state.turn)
        assertEquals(DdzPhase.BIDDING, state.phase)
        state = state.act(DdzAction.Call(true))
        assertEquals(caller, state.landlord)
        assertEquals(4, state.bidMultiplier)
        assertEquals(DdzPhase.DOUBLING, state.phase)
        // Farmers decide first, then the landlord.
        state = state.act(DdzAction.Double(true)).act(DdzAction.Double(false)).act(DdzAction.Double(true))
        assertEquals(DdzPhase.PLAYING, state.phase)
        assertEquals(caller, state.turn)
    }

    @Test
    fun `without a rob the caller is landlord after one round`() {
        val start = DouDizhu.start(DdzRules.ROB, 5)
        val state = start.act(DdzAction.Call(false)).act(DdzAction.Call(true)).act(DdzAction.Call(false))
        assertEquals((start.turn + 1) % 3, state.landlord)
        assertEquals(1, state.bidMultiplier)
    }

    /** A game in the playing phase with chosen hands; seat 0 is landlord and leads. */
    private fun playing(h0: String, h1: String, h2: String, rules: DdzRules = DdzRules.CLASSIC): DdzState =
        DouDizhu.start(rules, 9).copy(
            hands = listOf(cards(h0), cards(h1), cards(h2)).map { hand -> hand.sortedWith(DDZ_ORDER) },
            phase = DdzPhase.PLAYING,
            turn = 0,
            landlord = 0,
            bidMultiplier = 1,
        )

    @Test
    fun `leader cannot pass and followers must beat`() {
        // cards() picks suits by order, so give each seat values the others do not use.
        val state = playing("3 4 4", "5 6", "7 8")
        assertThrows<IllegalActionException> { state.act(DdzAction.Pass) }
        val led = state.act(DdzAction.PlayCards(state.hands[0].filter { it.ddzValue == 3 }))
        assertThrows<IllegalActionException> { led.act(DdzAction.PlayCards(led.hands[1].take(2))) }
        val afterPasses = led.act(DdzAction.Pass).act(DdzAction.Pass)
        assertEquals(0, afterPasses.turn)
        assertNull(DouDizhu.toBeat(afterPasses))
    }

    @Test
    fun `spring doubles and bombs double the landlord's win`() {
        val landlordHand = cards("3 3 3 3 BJ RJ")
        val state = playing("", "5 6", "7 8").copy(hands = listOf(landlordHand, cards("5 6"), cards("7 8")))
        val bomb = state.act(DdzAction.PlayCards(landlordHand.filter { it.ddzValue == 3 }))
        val end = bomb.act(DdzAction.Pass).act(DdzAction.Pass)
            .act(DdzAction.PlayCards(landlordHand.filter { it.ddzValue >= 16 }))
        val s = end.settlement!!
        assertTrue(s.landlordWon)
        assertTrue(s.spring)
        assertEquals(2, s.bombs)
        // 1 (bid) x 2 x 2 (two bombs) x 2 (spring) = 8 from each farmer.
        assertEquals(listOf(16, -8, -8), s.scores)
        assertEquals(DdzPhase.FINISHED, end.phase)
        assertNull(DouDizhu.toAct(end))
    }

    @Test
    fun `doubling applies per farmer`() {
        val rules = DdzRules.ROB
        val state = playing("3", "5", "7", rules).copy(doubles = listOf(false, true, false))
        val end = state.act(DdzAction.PlayCards(state.hands[0]))
        // Spring: x2. Farmer 1 doubled: x2 more.
        assertEquals(listOf(6, -4, -2), end.settlement!!.scores)
    }

    @Test
    fun `codecs round trip`() {
        var state = DouDizhu.start(DdzRules.ROB, 11)
        state = state.act(DdzAction.Call(true)).act(DdzAction.Call(false)).act(DdzAction.Call(false))
        val view = DouDizhu.view(state, 1)
        assertEquals(view, DdzCodecs.VIEW.decode(DdzCodecs.VIEW.encode(view)))
        val spectator = DouDizhu.view(state, null)
        assertTrue(spectator.hand.isEmpty())
        assertEquals(spectator, DdzCodecs.VIEW.decode(DdzCodecs.VIEW.encode(spectator)))
        for (rules in DdzRules.PRESETS.map { it.rules }) {
            assertEquals(rules, DdzRules.CODEC.decode(DdzRules.CODEC.encode(rules)))
        }
        val actions = listOf(
            DdzAction.Bid(2), DdzAction.Call(true), DdzAction.Double(false), DdzAction.Pass,
            DdzAction.PlayCards(cards("3 3 BJ")),
        )
        for (a in actions) assertEquals(a, DdzCodecs.ACTION.decode(DdzCodecs.ACTION.encode(a)))
    }

    @Test
    fun `views hide other hands until the end`() {
        val state = DouDizhu.start(DdzRules.CLASSIC, 12)
        val view = DouDizhu.view(state, 0)
        assertEquals(state.hands[0], view.hand)
        assertTrue(view.revealedHands.isEmpty())
        assertTrue(view.kitty.isEmpty())
    }

    @Test
    fun `open views show every hand for replays`() {
        val state = DouDizhu.start(DdzRules.CLASSIC, 12)
        val view = DouDizhu.openView(state, 1)
        assertEquals(state.hands[1], view.hand)
        assertEquals(state.hands, view.revealedHands)
        assertEquals(state.kitty, view.kitty)
    }

    @Test
    fun `presets are found from their encoded rules`() {
        for (preset in DouDizhu.presets) {
            assertEquals(preset.id, DouDizhu.presetIdOf(DdzRules.CODEC.encode(preset.rules)))
        }
    }

    @Test
    fun `three-player rules are written as before the four-player game`() {
        // The classic rules as 0.8.0 wrote them, so saved tables and replays still read and match.
        val old = io.github.konyaco.tukuai.engine.core.WireWriter().int(0).bool(false).bool(true).int(0).int(1).int(0)
            .bool(true).bool(true).int(3).toByteArray()
        assertTrue(old.contentEquals(DdzRules.CODEC.encode(DdzRules.CLASSIC)))
        assertEquals(DdzRules.CLASSIC, DdzRules.CODEC.decode(old))
        assertEquals(DdzRules.FOUR, DdzRules.CODEC.decode(DdzRules.CODEC.encode(DdzRules.FOUR)))
    }

    // ---- Four players ---------------------------------------------------------------------------

    @Test
    fun `four players get 25 cards each from two decks and 8 go to the kitty`() {
        for (mode in DealMode.entries) {
            val rules = DdzRules.FOUR.copy(deal = mode)
            assertEquals(4, DouDizhu.seatCount(rules))
            val state = DouDizhu.start(rules, seed = 42)
            assertEquals(List(4) { 25 }, state.hands.map { it.size })
            assertEquals(8, state.kitty.size)
            val all = (state.hands.flatten() + state.kitty).groupingBy { it }.eachCount()
            assertEquals(Decks.STANDARD_54.toSet(), all.keys)
            assertTrue(all.values.all { it == 2 })
        }
    }

    @Test
    fun `four players bid in turn and the landlord takes all 8 cards`() {
        var state = DouDizhu.start(DdzRules.FOUR, 5)
        val first = state.turn
        state = state.act(DdzAction.Bid(1)).act(DdzAction.Bid(0)).act(DdzAction.Bid(2))
        assertEquals(DdzPhase.BIDDING, state.phase)
        state = state.act(DdzAction.Bid(0))
        val landlord = (first + 2) % 4
        assertEquals(landlord, state.landlord)
        assertEquals(2, state.bidMultiplier)
        assertEquals(33, state.hands[landlord].size)
        assertEquals(landlord, state.turn)
        assertEquals(DdzPhase.PLAYING, state.phase)
    }

    @Test
    fun `playing one of two equal cards keeps the other`() {
        val five = io.github.konyaco.tukuai.engine.card.PlayingCard.Regular(
            io.github.konyaco.tukuai.engine.card.Rank.FIVE, io.github.konyaco.tukuai.engine.card.Suit.SPADES,
        )
        val hand = listOf(five, five) + cards2("9")
        val state = DouDizhu.start(DdzRules.FOUR, 3).let { s ->
            s.copy(phase = DdzPhase.PLAYING, landlord = 0, turn = 0, hands = s.hands.mapIndexed { i, h -> if (i == 0) hand else h })
        }
        val after = state.act(DdzAction.PlayCards(listOf(five)))
        assertEquals(listOf(five, hand[2]), after.hands[0])
        assertThrows<IllegalActionException> { state.act(DdzAction.PlayCards(listOf(five, five, five))) }
    }

    @Test
    fun `the landlord settles with each of three farmers`() {
        val start = DouDizhu.start(DdzRules.FOUR, 8)
        val state = start.copy(
            phase = DdzPhase.PLAYING, landlord = 1, turn = 1, bidMultiplier = 3,
            hands = start.hands.mapIndexed { i, h -> if (i == 1) cards2("K") else h },
            playCounts = listOf(1, 3, 2, 1),
        )
        val scores = state.act(DdzAction.PlayCards(cards2("K"))).settlement!!.scores
        assertEquals(listOf(-3, 9, -3, -3), scores)
    }
}
