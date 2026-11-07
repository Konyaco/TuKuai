package io.github.konyaco.tukuai.engine.mahjong.riichi

import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.Match
import io.github.konyaco.tukuai.engine.core.Replay
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.mahjong.Tiles
import io.github.konyaco.tukuai.engine.mahjong.Wall
import io.github.konyaco.tukuai.engine.mahjong.riichi.bot.RcBot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RiichiTest {
    private val game = Riichi
    private fun t(text: String) = Tiles.parse(text)
    private fun tile(text: String) = t(text).single()

    /**
     * A hand in play, seat 0 dealing, [turn] having just drawn [drawn] (in their hand). The
     * wall is [live] then a dead wall of 14 whose dora indicator is 9s.
     */
    private fun state(
        hands: List<String>,
        turn: Seat = 0,
        drawn: String? = null,
        live: String = "1m 1m 2m 2m 3m 3m 4p 4p 6s 6s 7s 7s 8s 8s",
        points: List<Int> = List(4) { 25000 },
        firstGo: Boolean = false,
    ): RcState {
        val dead = t("1p 1p 1p 2p 2p 2p 9s 3p 3p 3p 9p 9p 9p 9p").reversed()
        val s = game.start(RcRules.EAST, 1)
        return s.copy(
            firstDealer = 0, round = 0, kyoku = 0, wall = Wall(t(live) + dead),
            hands = hands.map { t(it).sorted() }, points = points, turn = turn, drawn = drawn?.let(::tile),
            firstGo = List(4) { firstGo }, interrupted = !firstGo, log = emptyList(),
        )
    }

    private fun RcState.act(seat: Seat, action: RcAction) = game.apply(this, seat, action)

    @Test
    fun `the deal gives thirteen each, the dealer draws, and a dora is turned up`() {
        for (seed in 0L until 10L) {
            val s = game.start(RcRules.EAST, seed)
            for (seat in 0 until 4) assertEquals(if (seat == s.dealer) 14 else 13, s.hands[seat].size)
            assertEquals(136, s.wall.tiles.size)
            assertEquals(3, s.wall.tiles.count { it.red })
            assertEquals(136 - 53 - 14, s.liveLeft)
            assertEquals(1, s.doraIndicators.size)
        }
        assertEquals(0, game.start(RcRules.EAST_NO_RED, 1).wall.tiles.count { it.red })
    }

    @Test
    fun `riichi puts a stick down once the discard goes through, and then the drawn tile must go`() {
        // Seat 0 is ready on 3-6s after discarding 9m.
        var s = state(listOf("123m 456m 789p 45s 66s 9m", "1234w 123d 19m 19p 9s", "13579m 2468p 13s", "2468m 13579p 79s"), drawn = "9m")
        assertTrue(tile("9m") in game.riichiTiles(s, 0))
        s = s.act(0, RcAction.Riichi(tile("9m")))
        assertTrue(s.riichi[0])
        assertEquals(24000, s.points[0])
        assertEquals(1, s.sticks)
        assertEquals(0, s.sideways[0])
        // Seat 1 has drawn; skip to seat 0's next draw.
        assertEquals(1, s.turn)
        s = s.act(1, RcAction.Discard(s.drawn!!))
        while (s.turn != 0 || s.phase != RcPhase.PLAY) {
            s = if (s.phase == RcPhase.PLAY) s.act(s.turn, RcAction.Discard(s.drawn!!)) else s.act(s.pending.first(), RcAction.Pass)
        }
        val drawn = s.drawn!!
        val other = s.hands[0].first { it != drawn }
        assertThrows(IllegalActionException::class.java) { s.act(0, RcAction.Discard(other)) }
    }

    @Test
    fun `a hand waiting on a tile it discarded cannot win on a discard`() {
        // Seat 1 waits on 3s or 6s but threw a 6s before.
        var s = state(listOf("3s 19m 19p 19s 1234w 123d", "123m 456m 789p 45s 66s", "13579m 2468p 13s", "2468m 13579p 79s"), drawn = "3s")
        s = s.copy(discarded = s.discarded.toMutableList().also { it[1] = t("6s") })
        assertTrue(game.furiten(s, 1))
        val after = s.act(0, RcAction.Discard(tile("3s")))
        assertFalse(game.claimOptions(after.copy(phase = RcPhase.CLAIM), 1, tile("3s"), 0).win)
    }

    @Test
    fun `letting a winning tile go is furiten until your own next discard`() {
        // Seat 1 has all simples ready on 3-6s; seat 0 throws 3s and seat 1 passes.
        var s = state(listOf("3s 19m 19p 19s 1234w 123d", "234m 456m 678p 45s 66s", "13579m 2468p 13s", "2468m 13579p 79s"), drawn = "3s")
        s = s.act(0, RcAction.Discard(tile("3s")))
        assertEquals(RcPhase.CLAIM, s.phase)
        s = s.act(1, RcAction.Pass)
        assertTrue(s.missed[1])
        assertTrue(game.furiten(s, 1))
        assertEquals(1, s.turn)
        s = s.act(1, RcAction.Discard(s.drawn!!))
        assertFalse(s.missed[1])
    }

    @Test
    fun `a ron pays the discarder, with honba and the riichi sticks`() {
        var s = state(listOf("3s 19m 19p 19s 1234w 123d", "234m 456m 678p 45s 66s", "13579m 2468p 13s", "2468m 13579p 79s"), drawn = "3s")
        s = s.copy(honba = 2, sticks = 1)
        s = s.act(0, RcAction.Discard(tile("3s"))).act(1, RcAction.Win)
        val win = s.result!!.wins.single()
        assertTrue(RcYaku.TANYAO in win.yaku.map { it.first })
        val value = win.value
        assertEquals(-(value + 600), s.result!!.payments[0])
        assertEquals(value + 600 + 1000, s.result!!.payments[1])
        // The stick came off the table.
        assertEquals(1000, s.result!!.payments.sum())
        assertEquals(RcPhase.HAND_OVER, s.phase)
        // A non-dealer won: the deal passes on and honba go back to none.
        assertEquals(1, s.nextKyoku)
        assertEquals(0, s.nextHonba)
    }

    @Test
    fun `several players may win on one discard`() {
        var s = state(listOf("3s 19m 19p 19s 1234w 123d", "234m 456m 678p 45s 66s", "234p 456p 678m 45s 77m", "2468m 13579p 79s"), drawn = "3s")
        s = s.act(0, RcAction.Discard(tile("3s")))
        assertEquals(listOf(1, 2), s.pending)
        s = s.act(2, RcAction.Win)
        assertEquals(RcPhase.CLAIM, s.phase)
        s = s.act(1, RcAction.Win)
        assertEquals(2, s.result!!.wins.size)
    }

    @Test
    fun `at the end of the wall the players not ready pay those who are`() {
        var s = state(listOf("9m 19p 19s 123w 12d 159m 5p", "234m 456m 678p 45s 66s", "13579m 2468p 13s", "2468m 13579p 79s"), drawn = "5p", live = "")
        s = s.act(0, RcAction.Discard(tile("5p")))
        val r = s.result!!
        assertTrue(r.exhausted)
        assertEquals(listOf(false, true, false, false), r.ready)
        assertEquals(listOf(-1000, 3000, -1000, -1000), r.payments)
        // The dealer was not ready, so the deal passes on, with a honba.
        assertEquals(1, s.nextKyoku)
        assertEquals(1, s.nextHonba)
    }

    @Test
    fun `nine kinds of terminals and honours on the first draw may call the hand off`() {
        val s = state(listOf("19m 19p 19s 1234w 12d 5m", "234m 456m 678p 45s 66s", "13579m 2468p 13s", "2468m 13579p 79s"), drawn = "5m", firstGo = true)
        assertTrue(game.canAbort(s, 0))
        val after = s.act(0, RcAction.Abort)
        assertEquals(RcAbort.NINE_TERMINALS, after.result!!.abort)
        assertEquals(0, after.nextKyoku)
        assertEquals(1, after.nextHonba)
    }

    // ---- Whole games ---------------------------------------------------------------------------

    private fun playOut(match: Match<RcRules, RcState, RcAction, RcView>, levels: List<BotLevel>, stopAfter: Int = Int.MAX_VALUE) {
        val bots = levels.mapIndexed { seat, level -> RcBot(level, match.seed * 31 + seat) }
        var steps = 0
        while (match.toAct != null && steps < stopAfter) {
            val seat = match.toAct!!
            match.apply(seat, bots[seat].act(match.view(seat)))
            check(++steps < 20000) { "Game ${match.seed} did not end" }
        }
    }

    @Test
    fun `views and actions survive the wire, and replays rebuild the same game`() {
        val match = Match(game, RcRules.EAST, 11)
        playOut(match, listOf(BotLevel.EASY, BotLevel.NORMAL, BotLevel.HARD, BotLevel.NORMAL), stopAfter = 200)
        for (seat in listOf(null, 0, 1, 2, 3)) {
            val view = game.view(match.state, seat)
            assertEquals(view, RcCodecs.VIEW.decode(RcCodecs.VIEW.encode(view)))
            if (seat != null) assertEquals(match.state.hands[seat], view.hand)
        }
        for ((_, action) in match.history) assertEquals(action, RcCodecs.ACTION.decode(RcCodecs.ACTION.encode(action)))
        val rebuilt = Replay.decode(match.replay(listOf("a", "b", "c", "d")).encode()).rebuild(game)
        assertEquals(match.state, rebuilt.state)
    }

    @Test
    fun `computers finish every game by the rules, with points adding up and yaku in every win`() {
        var hands = 0
        var won = 0
        var slowest = 0L
        for (seed in 0L until 12L) {
            val levels = List(4) { BotLevel.entries[((seed + it) % 3).toInt()] }
            val rules = if (seed % 3 == 0L) RcRules.SOUTH else RcRules.EAST
            val match = Match(game, rules, seed)
            val bots = levels.mapIndexed { seat, level -> RcBot(level, seed * 31 + seat) }
            var steps = 0
            while (match.toAct != null) {
                val seat = match.toAct!!
                val view = match.view(seat)
                val start = System.nanoTime()
                val action = bots[seat].act(view)
                slowest = maxOf(slowest, System.nanoTime() - start)
                match.apply(seat, action)
                check(++steps < 20000) { "Game $seed did not end" }
            }
            val s = match.state
            assertEquals(0, match.result!!.scores.sum(), "seed $seed")
            assertEquals(0, s.sticks)
            for (r in s.history + s.result!!) {
                hands++
                if (r.wins.isNotEmpty()) won++
                for (w in r.wins) assertTrue(w.yaku.isNotEmpty(), "seed $seed: $w")
            }
        }
        assertTrue(won >= hands / 2, "computers should win most hands, won $won of $hands")
        assertTrue(slowest < 2_000_000_000L, "slowest move took ${slowest / 1_000_000} ms")
        println("Riichi: $won of $hands hands won, slowest move ${slowest / 1_000_000} ms")
    }

    @Test
    fun `harder computers do better`() {
        var hard = 0
        var easy = 0
        for (seed in 0L until 10L) {
            val levels = List(4) { if (it % 2 == 0) BotLevel.HARD else BotLevel.EASY }
            val match = Match(game, RcRules.EAST, seed)
            playOut(match, levels)
            val scores = match.result!!.scores
            hard += scores[0] + scores[2]
            easy += scores[1] + scores[3]
        }
        assertTrue(hard > easy, "hard $hard, easy $easy")
    }
}
