package io.github.konyaco.tukuai.engine.doudizhu

import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Match
import io.github.konyaco.tukuai.engine.core.Replay
import io.github.konyaco.tukuai.engine.doudizhu.bot.HandAnalysis
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
                val levels = List(3) { BotLevel.entries[((seed + it) % 3).toInt()] }
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

    @Test
    fun `hard bots beat easy bots`() {
        // Seats 0 and 1 swap levels every other game so the deal does not decide the outcome.
        var hardPoints = 0
        val games = 300
        for (seed in 0L until games) {
            val hardSeat = (seed % 3).toInt()
            val levels = List(3) { if (it == hardSeat) BotLevel.HARD else BotLevel.EASY }
            hardPoints += playOut(DdzRules.CLASSIC, 1000 + seed, levels).result!!.scores[hardSeat]
        }
        println("Hard bot net points over $games games against two easy bots: $hardPoints")
        assertTrue(hardPoints > 0, "hard bot lost $hardPoints points")
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
