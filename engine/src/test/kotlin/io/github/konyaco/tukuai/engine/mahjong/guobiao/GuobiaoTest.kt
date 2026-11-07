package io.github.konyaco.tukuai.engine.mahjong.guobiao

import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.Match
import io.github.konyaco.tukuai.engine.core.Replay
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit
import io.github.konyaco.tukuai.engine.mahjong.Tiles
import io.github.konyaco.tukuai.engine.mahjong.Wall
import io.github.konyaco.tukuai.engine.mahjong.guobiao.bot.GbBot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuobiaoTest {
    private val game = Guobiao
    private fun t(text: String) = Tiles.parse(text)
    private fun tile(text: String) = t(text).single()

    /** A hand in play: [turn] has just drawn [drawn] (already in their hand). Seat 0 deals. */
    private fun state(
        hands: List<String>,
        wall: String = "1s 1s 2s 2s 3s 3s 4s 4s 6s 6s 7s 7s 8s 8s",
        turn: Seat = 0,
        drawn: String? = null,
        melds: List<List<Meld>> = List(4) { emptyList() },
        rules: GbRules = GbRules.ONE_ROUND,
    ): GbState = GbState(
        rules = rules, seed = 0, hand = 0, firstDealer = 0, wall = Wall(t(wall)),
        hands = hands.map { t(it).sorted() }, melds = melds, discards = List(4) { emptyList() }, flowers = List(4) { emptyList() },
        phase = GbPhase.PLAY, turn = turn, drawn = drawn?.let(::tile), claimTile = null, pending = emptyList(), answers = emptyMap(),
        replacement = false, fresh = false, result = null, history = emptyList(), scores = List(4) { 0 }, ready = List(4) { false },
        log = emptyList(),
    )

    private fun GbState.act(seat: Seat, action: GbAction) = game.apply(this, seat, action)

    @Test
    fun `the deal gives thirteen each and the dealer fourteen, flowers set aside and replaced`() {
        for (seed in 0L until 20L) {
            val s = game.start(GbRules.ONE_ROUND, seed)
            for (seat in 0 until 4) {
                assertEquals(if (seat == s.dealer) 14 else 13, s.hands[seat].size)
                assertTrue(s.hands[seat].none { it.suit == TileSuit.FLOWERS })
            }
            val all = s.hands.flatten() + s.flowers.flatten() + s.wall.tiles.subList(s.wall.drawnFront, s.wall.tiles.size - s.wall.drawnBack)
            assertEquals(144, all.size)
            assertEquals(GbPhase.PLAY, s.phase)
            assertEquals(s.dealer, s.turn)
        }
        assertEquals(136, game.start(GbRules.NO_FLOWERS, 1).wall.tiles.size)
    }

    @Test
    fun `only the next player may chow, anyone may pung, and a win beats both`() {
        // Seat 0 discards 5m: seat 1 can chow it (46m), seat 2 can pung it.
        var s = state(listOf("5m 19p 19s 1234w 123d 11m", "46m 124p 456p 789s 11w", "55m 124p 456s 789s 22w", "13579m 13579p 135s"), drawn = "1m")
        s = s.act(0, GbAction.Discard(tile("5m")))
        assertEquals(GbPhase.CLAIM, s.phase)
        assertEquals(listOf(1, 2), s.pending)
        assertEquals(listOf(tile("4m")), game.claimOptions(s, 1, tile("5m"), 0).chows)
        assertTrue(game.claimOptions(s, 2, tile("5m"), 0).pung)
        assertTrue(game.claimOptions(s, 2, tile("5m"), 0).chows.isEmpty())
        // The pung wins over the chow, whoever answers first.
        val chowFirst = s.act(1, GbAction.Chow(tile("4m")))
        assertEquals(GbPhase.CLAIM, chowFirst.phase)
        val after = chowFirst.act(2, GbAction.Pung)
        assertEquals(GbPhase.PLAY, after.phase)
        assertEquals(2, after.turn)
        assertEquals(MeldKind.PUNG, after.melds[2].single().kind)
        // Once the pung is in, the chow need not wait.
        val pungFirst = s.act(2, GbAction.Pung)
        assertEquals(2, pungFirst.turn)
    }

    @Test
    fun `a hand short of 8 fan cannot win, and the chicken hand can`() {
        // Seat 1 waits on 3m or 6m with no fan worth speaking of.
        val short = state(listOf("6m 19p 19s 1234w 123d 11m", "45m 123p 456p 678s 11s", "2468m 2468p 2468s 1w", "13579m 13579p 135s"), drawn = "6m")
        val s = short.act(0, GbAction.Discard(tile("6m")))
        assertFalse(game.claimOptions(s, 1, tile("6m"), 0).win)
        val chicken = state(
            listOf("8s 19p 19m 1234w 123d 11m", "67s 44w", "2468m 2468p 2468s 1w", "13579m 13579p 135s"),
            melds = listOf(emptyList(), listOf(Meld(MeldKind.CHOW, tile("1m"), 0), Meld(MeldKind.CHOW, tile("5p"), 0), Meld(MeldKind.CHOW, tile("2s"), 0)), emptyList(), emptyList()),
            drawn = "8s",
        )
        val c = chicken.act(0, GbAction.Discard(tile("8s")))
        assertTrue(game.claimOptions(c, 1, tile("8s"), 0).win)
    }

    @Test
    fun `the winner takes 8 from everyone and the fan from the discarder, or from everyone when self-drawn`() {
        val melds = listOf(emptyList(), listOf(Meld(MeldKind.CHOW, tile("1m"), 0), Meld(MeldKind.CHOW, tile("5p"), 0), Meld(MeldKind.CHOW, tile("2s"), 0)), emptyList(), emptyList())
        val s = state(listOf("8s 19p 19m 1234w 123d 11m", "67s 44w", "2468m 2468p 2468s 1w", "13579m 13579p 135s"), melds = melds, drawn = "8s")
        val won = s.act(0, GbAction.Discard(tile("8s"))).act(1, GbAction.Win)
        assertEquals(GbPhase.HAND_OVER, won.phase)
        assertEquals(listOf(-16, 32, -8, -8), won.result!!.payments)
        assertEquals(listOf(-16, 32, -8, -8), won.scores)
        // Self-drawn: 8 and the fan from everyone; a mixed straight self-drawn is 9.
        val straight = listOf(emptyList(), listOf(Meld(MeldKind.CHOW, tile("1m"), 0), Meld(MeldKind.CHOW, tile("4p"), 0), Meld(MeldKind.CHOW, tile("7s"), 0)), emptyList(), emptyList())
        val draw = state(listOf("19p 19m 1234w 123d 1m 9s", "23s 4s 33w", "2468m 2468p 2468s 1w", "13579m 13579p 135s"), melds = straight, turn = 1, drawn = "4s")
        val self = draw.act(1, GbAction.Win)
        assertEquals(listOf(-17, 51, -17, -17), self.result!!.payments)
    }

    @Test
    fun `an added kong may be robbed`() {
        val melds = listOf(listOf(Meld(MeldKind.PUNG, tile("8s"), 1)), listOf(Meld(MeldKind.CHOW, tile("1m"), 0), Meld(MeldKind.CHOW, tile("5p"), 0), Meld(MeldKind.CHOW, tile("2s"), 0)), emptyList(), emptyList())
        val s = state(listOf("8s 19p 19m 1234w 12d", "67s 44w", "2468m 2468p 2468s 1w", "13579m 13579p 135s"), melds = melds, drawn = "8s")
        val kong = s.act(0, GbAction.Kong(tile("8s")))
        assertEquals(GbPhase.ROB, kong.phase)
        assertEquals(listOf(1), kong.pending)
        val robbed = kong.act(1, GbAction.Win)
        assertTrue(GbFan.ROBBING_THE_KONG in robbed.result!!.fans)
        assertEquals(0, robbed.result!!.from)
        // Let go, the kong goes through and seat 0 draws from the back.
        val let = kong.act(1, GbAction.Pass)
        assertEquals(MeldKind.ADDED_KONG, let.melds[0].single().kind)
        assertEquals(GbPhase.PLAY, let.phase)
        assertEquals(0, let.turn)
    }

    @Test
    fun `hands pass the deal on, and the game ends after the last`() {
        var s = game.start(GbRules.ONE_ROUND, 5)
        val first = s.dealer
        val bots = List(4) { GbBot(BotLevel.NORMAL, it.toLong()) }
        var hands = 0
        while (!s.finished) {
            val seat = game.toAct(s)!!
            val before = s.phase
            s = game.apply(s, seat, bots[seat].act(game.view(s, seat)))
            if (before != GbPhase.HAND_OVER && s.phase == GbPhase.HAND_OVER) hands++
            if (s.phase == GbPhase.PLAY && s.log.size <= 9) assertEquals((first + s.hand) % 4, s.dealer)
        }
        assertEquals(3, hands)
        assertEquals(3, s.history.size)
        assertEquals(0, s.scores.sum())
        assertTrue(s.result != null)
    }

    @Test
    fun `moves out of turn are refused`() {
        val s = state(listOf("5m 19p 19s 1234w 123d 11m", "46m 123p 456p 789s 11w", "55m 123p 456s 789s 22w", "13579m 13579p 135s"), drawn = "1m")
        assertThrows(IllegalActionException::class.java) { s.act(1, GbAction.Discard(tile("4m"))) }
        assertThrows(IllegalActionException::class.java) { s.act(0, GbAction.Discard(tile("9m"))) }
        assertThrows(IllegalActionException::class.java) { s.act(0, GbAction.Win) }
    }

    // ---- Views, codecs and replays -------------------------------------------------------------

    private fun playOut(match: Match<GbRules, GbState, GbAction, GbView>, levels: List<BotLevel>, stopAfter: Int = Int.MAX_VALUE) {
        val bots = levels.mapIndexed { seat, level -> GbBot(level, match.seed * 31 + seat) }
        var steps = 0
        while (match.toAct != null && steps < stopAfter) {
            val seat = match.toAct!!
            match.apply(seat, bots[seat].act(match.view(seat)))
            check(++steps < 8000) { "Game ${match.seed} did not end" }
        }
    }

    @Test
    fun `views and actions survive the wire, and replays rebuild the same game`() {
        val match = Match(game, GbRules.ONE_ROUND, 11)
        playOut(match, listOf(BotLevel.EASY, BotLevel.NORMAL, BotLevel.HARD, BotLevel.NORMAL), stopAfter = 150)
        for (seat in listOf(null, 0, 1, 2, 3)) {
            val view = game.view(match.state, seat)
            assertEquals(view, GbCodecs.VIEW.decode(GbCodecs.VIEW.encode(view)))
            if (seat != null) assertEquals(match.state.hands[seat], view.hand)
        }
        for ((_, action) in match.history) assertEquals(action, GbCodecs.ACTION.decode(GbCodecs.ACTION.encode(action)))
        val rebuilt = Replay.decode(match.replay(listOf("a", "b", "c", "d")).encode()).rebuild(game)
        assertEquals(match.state, rebuilt.state)
        val spectator = game.view(match.state, null)
        assertTrue(spectator.hand.isEmpty())
        assertTrue(spectator.log.filter { it.kind == GbEventKind.DRAW }.all { it.tile == null })
    }

    @Test
    fun `computers finish every game by the rules, win plenty of hands, and think quickly`() {
        var hands = 0
        var won = 0
        var slowest = 0L
        for (seed in 0L until 12L) {
            val levels = List(4) { BotLevel.entries[((seed + it) % 3).toInt()] }
            val match = Match(game, GbRules.ONE_ROUND, seed)
            val bots = levels.mapIndexed { seat, level -> GbBot(level, seed * 31 + seat) }
            var steps = 0
            while (match.toAct != null) {
                val seat = match.toAct!!
                val view = match.view(seat)
                val start = System.nanoTime()
                val action = bots[seat].act(view)
                slowest = maxOf(slowest, System.nanoTime() - start)
                match.apply(seat, action)
                check(++steps < 8000) { "Game $seed did not end" }
            }
            val s = match.state
            assertEquals(0, match.result!!.scores.sum(), "seed $seed")
            for (r in s.history + s.result!!) {
                hands++
                if (r.winner != null) {
                    won++
                    assertTrue(r.fans.filter { it != GbFan.FLOWER_TILES }.sumOf { it.fan } >= 8, "seed $seed: ${r.fans}")
                }
            }
        }
        assertEquals(48, hands)
        assertTrue(won >= hands / 3, "computers should win plenty of hands, won $won of $hands")
        assertTrue(slowest < 2_000_000_000L, "slowest move took ${slowest / 1_000_000} ms")
        println("Guobiao: $won of $hands hands won, slowest move ${slowest / 1_000_000} ms")
    }

    @Test
    fun `harder computers do better`() {
        var hard = 0
        var easy = 0
        for (seed in 0L until 8L) {
            val levels = List(4) { if (it % 2 == 0) BotLevel.HARD else BotLevel.EASY }
            val match = Match(game, GbRules.ONE_ROUND, seed)
            playOut(match, levels)
            val scores = match.result!!.scores
            hard += scores[0] + scores[2]
            easy += scores[1] + scores[3]
        }
        assertTrue(hard > easy, "hard $hard, easy $easy")
    }
}
