package io.github.konyaco.tukuai.engine.doudizhu

import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Match
import io.github.konyaco.tukuai.engine.core.Replay
import io.github.konyaco.tukuai.engine.doudizhu.bot.HandAnalysis
import io.github.konyaco.tukuai.engine.doudizhu.bot.PlayoutSearch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BotSimulationTest {
    private fun playOut(rules: DdzRules, seed: Long, levels: List<BotLevel>): Match<DdzRules, DdzState, DdzAction, DdzView> {
        val match = Match(DouDizhu, rules, seed)
        var guard = 0
        while (match.toAct != null) {
            val seat = match.toAct!!
            match.playBot(seat, levels[seat])
            check(++guard < 500) { "Game $seed did not end" }
        }
        return match
    }

    @Test
    fun `bots finish every game under every rule set, and points add up to zero`() {
        for (preset in DouDizhu.presets) {
            for (seed in 0L until 150L) {
                // The hard computer is slow to simulate, so it sits in only some of the games.
                val levels = List(3) { BotLevel.entries[((seed + it) % if (seed < 15) 3 else 2).toInt()] }
                val match = playOut(preset.rules, seed, levels)
                val result = match.result!!
                assertEquals(0, result.scores.sum(), "seed $seed")
                assertTrue(result.winners.isNotEmpty())
            }
        }
    }

    @Test
    fun `replays rebuild the same game`() {
        val match = playOut(DdzRules.ROB, 77, List(3) { BotLevel.HARD })
        val replay = Replay.decode(match.replay(listOf("a", "b", "c")).encode())
        val rebuilt = replay.rebuild(DouDizhu)
        assertEquals(match.state, rebuilt.state)
        val halfway = replay.rebuild(DouDizhu, replay.steps.size / 2)
        assertEquals(replay.steps.size / 2, halfway.step)
    }

    /** Points a [level] computer in rotating seats earns over the same deals as a [baseline] computer would. */
    private fun gain(level: BotLevel, baseline: BotLevel, games: Int): Int {
        var gain = 0
        for (game in 0 until games) {
            val seed = 9000L + game
            val seat = game % 3
            val tried = playOut(DdzRules.CLASSIC, seed, List(3) { if (it == seat) level else baseline }).result!!.scores[seat]
            val base = playOut(DdzRules.CLASSIC, seed, List(3) { baseline }).result!!.scores[seat]
            gain += tried - base
        }
        return gain
    }

    @Test
    fun `hard bots beat easy bots`() {
        val gain = gain(BotLevel.HARD, BotLevel.EASY, 60)
        println("Hard bot gain over easy across 60 deals: $gain")
        assertTrue(gain > 0, "hard bot fell behind by $gain points")
    }

    @Test
    fun `hard bots beat normal bots`() {
        val gain = gain(BotLevel.HARD, BotLevel.NORMAL, 60)
        println("Hard bot gain over normal across 60 deals: $gain")
        assertTrue(gain > 0, "hard bot fell behind by $gain points")
    }

    @Test
    fun `guessed deals match what the seat has seen`() {
        val match = playOut(DdzRules.CLASSIC, 31, List(3) { BotLevel.NORMAL })
        val replay = match.replay(listOf("a", "b", "c"))
        for (step in listOf(5, 15, 25)) {
            val state = replay.rebuild(DouDizhu, minOf(step, replay.steps.size - 1)).state
            if (state.phase != DdzPhase.PLAYING) continue
            val seat = state.turn
            val view = DouDizhu.view(state, seat)
            val guess = PlayoutSearch(kotlin.random.Random(step)).guess(view, seat)!!
            assertEquals(state.hands[seat], guess.hands[seat])
            assertEquals(state.hands.map { it.size }, guess.hands.map { it.size })
            assertEquals(state.hands.flatten().toSet(), guess.hands.flatten().toSet())
            val landlord = state.landlord!!
            if (landlord != seat) {
                val kittyLeft = state.kitty.filter { it in state.hands[landlord] }
                assertTrue(guess.hands[landlord].containsAll(kittyLeft))
            }
        }
    }

    @Test
    fun `hand split keeps every card and makes legal plays`() {
        for (seed in 0L until 200L) {
            val hand = DouDizhu.start(DdzRules.UNSHUFFLED, seed).hands[0]
            val split = HandAnalysis.split(hand)
            assertEquals(hand.toSet(), split.plays.flatMap { it.cards }.toSet(), "seed $seed")
            assertEquals(hand.size, split.plays.sumOf { it.cards.size })
            for (play in split.plays) {
                assertTrue(play.pattern in Patterns.classify(play.cards, DdzRules.CLASSIC), "${play.pattern} ${DdzCards.describe(play.cards)}")
            }
        }
    }
}
