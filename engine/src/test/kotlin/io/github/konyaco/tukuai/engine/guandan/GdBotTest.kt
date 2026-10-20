package io.github.konyaco.tukuai.engine.guandan

import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Match
import io.github.konyaco.tukuai.engine.core.Replay
import io.github.konyaco.tukuai.engine.guandan.bot.GdBot
import io.github.konyaco.tukuai.engine.guandan.bot.GdSearch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GdBotTest {
    /**
     * Plays a whole game with computers, applying their moves straight to the rules so an
     * illegal move fails the test instead of being quietly replaced as the table does.
     */
    private fun playOut(rules: GdRules, seed: Long, levels: List<BotLevel>, playouts: Int = 12): Match<GdRules, GdState, GdAction, GdView> {
        val match = Match(Guandan, rules, seed)
        val bots = levels.mapIndexed { seat, level -> GdBot(level, seed * 31 + seat, playouts) }
        var guard = 0
        while (match.toAct != null) {
            val seat = match.toAct!!
            match.apply(seat, bots[seat].act(match.view(seat)))
            check(++guard < 20000) { "Game $seed did not end" }
        }
        return match
    }

    @Test
    fun `computers finish a round, and points add up to zero`() {
        for (seed in 0L until 40L) {
            val levels = List(4) { BotLevel.entries[((seed + it) % if (seed < 4) 3 else 2).toInt()] }
            val match = playOut(GdRules.ONE_ROUND, seed, levels)
            val result = match.result!!
            assertEquals(0, result.scores.sum(), "seed $seed")
            assertEquals(2, result.winners.size)
            assertEquals(1, match.state.rounds.size)
            assertTrue(result.scores.all { it != 0 })
        }
    }

    @Test
    fun `computers play a long game through to A`() {
        for (seed in 0L until 3L) {
            val match = playOut(GdRules.TO_ACE, seed, List(4) { if (seed == 0L && it == 1) BotLevel.HARD else BotLevel.NORMAL })
            val state = match.state
            val result = match.result!!
            assertEquals(0, result.scores.sum())
            val last = state.rounds.last()
            val winners = result.winners
            assertEquals(setOf(last.order[0], Guandan.partner(last.order[0])), winners)
            assertEquals(GdValue.LADDER.size - 1, last.level, "the last round was played at A")
            assertEquals(Guandan.team(last.order[0]), last.levelTeam)
            assertTrue(state.rounds.size > 4, "a game to A takes a few rounds, not ${state.rounds.size}")
            // Every round after the first was played at its winners' level from before.
            state.rounds.zipWithNext().forEach { (a, b) -> assertEquals(a.levelsAfter[a.winnerTeam], b.level) }
        }
    }

    @Test
    fun `replays rebuild the same game`() {
        val match = playOut(GdRules.TO_ACE, 5, List(4) { BotLevel.NORMAL })
        val replay = Replay.decode(match.replay(listOf("a", "b", "c", "d")).encode())
        assertEquals(match.state, replay.rebuild(Guandan).state)
    }

    @Test
    fun `the table's own computers play through a game`() {
        val match = Match(Guandan, GdRules.ONE_ROUND, 5)
        var guard = 0
        while (match.toAct != null) {
            match.playBot(match.toAct!!, BotLevel.entries[guard % 3])
            check(++guard < 2000)
        }
        assertEquals(0, match.result!!.scores.sum())
    }

    @Test
    fun `guessed deals match what the seat has seen`() {
        val match = playOut(GdRules.ONE_ROUND, 31, List(4) { BotLevel.NORMAL })
        val replay = match.replay(listOf("a", "b", "c", "d"))
        for (step in listOf(3, 20, 50)) {
            val state = replay.rebuild(Guandan, minOf(step, replay.steps.size - 1)).state
            val seat = state.turn
            val guess = GdSearch(kotlin.random.Random(1)).guess(Guandan.view(state, seat), seat)!!
            assertEquals(state.hands[seat], guess.hands[seat])
            assertEquals(state.hands.map { it.size }, guess.hands.map { it.size })
            assertEquals(
                GdCards.DECK.groupingBy { it }.eachCount(),
                (guess.hands.flatten() + state.log.filter { it.kind == GdEventKind.PLAY }.flatMap { it.cards }).groupingBy { it }.eachCount(),
            )
        }
    }

    /** Points team 0 earns over [games] rounds, playing as [ours] against [theirs], each deal played from both sides. */
    private fun teamGain(ours: BotLevel, theirs: BotLevel, games: Int): Int {
        var gain = 0
        for (game in 0 until games) {
            val seed = 7000L + game
            for (swap in listOf(false, true)) {
                val levels = List(4) { seat -> if ((Guandan.team(seat) == 0) != swap) ours else theirs }
                val scores = playOut(GdRules.ONE_ROUND, seed, levels).result!!.scores
                gain += if (swap) scores[1] else scores[0]
            }
        }
        return gain
    }

    @Test
    fun `a normal team beats an easy one`() {
        val gain = teamGain(BotLevel.NORMAL, BotLevel.EASY, 30)
        println("Normal against easy over 60 rounds: $gain")
        assertTrue(gain > 30, "normal earned $gain against easy")
    }

    @Test
    fun `computers decide quickly enough`() {
        val match = Match(Guandan, GdRules.ONE_ROUND, 12)
        val bot = GdBot(BotLevel.HARD, 1)
        var slowest = 0L
        var guard = 0
        while (match.toAct != null && guard++ < 60) {
            val seat = match.toAct!!
            val start = System.nanoTime()
            val action = bot.act(match.view(seat))
            slowest = maxOf(slowest, (System.nanoTime() - start) / 1_000_000)
            match.apply(seat, action)
        }
        println("Slowest hard move: $slowest ms")
        assertTrue(slowest < 5000, "a hard move took $slowest ms")
    }
}
