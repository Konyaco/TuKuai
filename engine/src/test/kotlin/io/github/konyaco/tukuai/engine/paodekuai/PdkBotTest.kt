package io.github.konyaco.tukuai.engine.paodekuai

import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Match
import io.github.konyaco.tukuai.engine.core.Replay
import io.github.konyaco.tukuai.engine.paodekuai.bot.PdkBot
import io.github.konyaco.tukuai.engine.paodekuai.bot.PdkSearch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdkBotTest {
    /**
     * Plays a whole game with computers, applying their moves straight to the rules so an
     * illegal move fails the test instead of being quietly replaced as the table does.
     */
    private fun playOut(rules: PdkRules, seed: Long, levels: List<BotLevel>, playouts: Int = 40): Match<PdkRules, PdkState, PdkAction, PdkView> {
        val match = Match(PaoDeKuai, rules, seed)
        val bots = levels.mapIndexed { seat, level -> PdkBot(level, seed * 31 + seat, playouts) }
        var guard = 0
        while (match.toAct != null) {
            val seat = match.toAct!!
            match.apply(seat, bots[seat].act(match.view(seat)))
            check(++guard < 500) { "Game $seed did not end" }
        }
        return match
    }

    @Test
    fun `computers finish every game under every rule set, and points add up to zero`() {
        for (preset in PaoDeKuai.presets) {
            for (seed in 0L until 120L) {
                // The hard computer is slow to simulate, so it sits in only some of the games.
                val levels = List(3) { BotLevel.entries[((seed + it) % if (seed < 12) 3 else 2).toInt()] }
                val result = playOut(preset.rules, seed, levels).result!!
                assertEquals(0, result.scores.sum(), "seed $seed")
                assertEquals(1, result.winners.size)
            }
        }
    }

    @Test
    fun `replays rebuild the same game`() {
        val match = playOut(PdkRules.SIXTEEN, 77, List(3) { BotLevel.HARD })
        val replay = Replay.decode(match.replay(listOf("a", "b", "c")).encode())
        assertEquals(match.state, replay.rebuild(PaoDeKuai).state)
        assertEquals(replay.steps.size / 2, replay.rebuild(PaoDeKuai, replay.steps.size / 2).step)
    }

    @Test
    fun `the table's own computers play through a game`() {
        val match = Match(PaoDeKuai, PdkRules.FIFTEEN, 5)
        var guard = 0
        while (match.toAct != null) {
            match.playBot(match.toAct!!, BotLevel.entries[guard % 3])
            check(++guard < 500)
        }
        assertEquals(0, match.result!!.scores.sum())
    }

    @Test
    fun `guessed deals match what the seat has seen`() {
        val match = playOut(PdkRules.SIXTEEN, 31, List(3) { BotLevel.NORMAL })
        val replay = match.replay(listOf("a", "b", "c"))
        for (step in listOf(3, 10, 20)) {
            val state = replay.rebuild(PaoDeKuai, minOf(step, replay.steps.size - 1)).state
            val seat = state.turn
            val guess = PdkSearch(kotlin.random.Random(1)).guess(PaoDeKuai.view(state, seat), seat)!!
            assertEquals(state.hands[seat], guess.hands[seat])
            assertEquals(state.hands.map { it.size }, guess.hands.map { it.size })
            assertEquals(PaoDeKuai.deck(state.rules).toSet() - state.log.flatMap { it.cards }.toSet(), guess.hands.flatten().toSet())
        }
    }

    /** Points a [level] computer in rotating seats earns over the same deals as a [baseline] computer would. */
    private fun gain(level: BotLevel, baseline: BotLevel, games: Int): Int {
        var gain = 0
        for (game in 0 until games) {
            val seed = 9000L + game
            val seat = game % 3
            val tried = playOut(PdkRules.SIXTEEN, seed, List(3) { if (it == seat) level else baseline }, playouts = 80).result!!.scores[seat]
            val base = playOut(PdkRules.SIXTEEN, seed, List(3) { baseline }).result!!.scores[seat]
            gain += tried - base
        }
        return gain
    }

    @Test
    fun `normal computers beat easy ones`() {
        val gain = gain(BotLevel.NORMAL, BotLevel.EASY, 90)
        println("Normal Pao De Kuai computer gain over easy across 90 deals: $gain")
        assertTrue(gain > 0, "normal fell behind by $gain points")
    }

    @Test
    fun `hard computers beat normal ones`() {
        val gain = gain(BotLevel.HARD, BotLevel.NORMAL, 60)
        println("Hard Pao De Kuai computer gain over normal across 60 deals: $gain")
        assertTrue(gain > 0, "hard fell behind by $gain points")
    }
}
