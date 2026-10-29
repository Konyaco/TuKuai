package io.github.konyaco.tukuai.engine.bluff.bot

import io.github.konyaco.tukuai.engine.bluff.Bluff
import io.github.konyaco.tukuai.engine.bluff.BluffAction
import io.github.konyaco.tukuai.engine.bluff.BluffPhase
import io.github.konyaco.tukuai.engine.bluff.BluffView
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import kotlin.random.Random

/**
 * A computer at the bluffing table. It plays what is called for when it holds some and
 * otherwise slips in one odd card, and calls a play a lie when the cards it can't see
 * would hardly have given the player that many. Harder computers judge closer and bluff
 * less clumsily; the easy one calls on a hunch.
 */
class BluffBot(private val level: BotLevel, seed: Long) : Bot<BluffView, BluffAction> {
    private val random = Random(seed)

    override fun act(view: BluffView): BluffAction {
        if (view.phase == BluffPhase.PENALTY) return BluffAction.Light
        if (view.mustCall) return BluffAction.Call
        if (view.canCall && shouldCall(view)) return BluffAction.Call
        return BluffAction.Play(pick(view))
    }

    /** The chance the last play holds a card that is not what was called for, from what this seat can see. */
    fun lieChance(view: BluffView): Double {
        val claimer = view.claimSeat ?: return 0.0
        val count = view.claimCount
        val matching = Bluff.matchCount(view.rules)
        val mine = view.hand.count { Bluff.matches(it, view.target) } + view.myPlayed.count { Bluff.matches(it, view.target) }
        val unseen = Bluff.deck(view.rules).size - view.hand.size - view.myPlayed.size
        val unseenMatching = matching - mine
        if (count > unseenMatching) return 1.0
        // Whoever holds enough plays them honestly, so the play is a lie when the hand did not.
        val handBefore = view.handCounts[claimer] + count
        return 1.0 - atLeast(unseen, unseenMatching, handBefore, count)
    }

    private fun shouldCall(view: BluffView): Boolean {
        val chance = lieChance(view)
        if (chance >= 1.0) return true
        val holding = view.hand.any { Bluff.matches(it, view.target) }
        // With nothing true to play, letting the play stand means lying next: calling looks better.
        val threshold = when (level) {
            BotLevel.EASY -> 0.35 + random.nextDouble() * 0.4
            BotLevel.NORMAL -> if (holding) 0.6 else 0.45
            BotLevel.HARD -> if (holding) 0.55 else 0.4
        }
        return chance > threshold
    }

    private fun pick(view: BluffView): List<PlayingCard> {
        val hand = view.hand
        val max = view.rules.maxPlay
        val honest = hand.filter { Bluff.matches(it, view.target) }.shuffled(random)
        val odd = hand.filter { !Bluff.matches(it, view.target) }.shuffled(random)
        if (level == BotLevel.EASY) return hand.shuffled(random).take(1 + random.nextInt(minOf(max, 2, hand.size)))
        if (honest.isNotEmpty()) {
            // Keep a true card back for later when there are odd ones to shed too, unless a hand can go out.
            val count = when {
                honest.size + odd.size <= max && odd.isEmpty() -> honest.size
                level == BotLevel.HARD && odd.isNotEmpty() && honest.size > 1 -> honest.size - 1
                else -> 1 + random.nextInt(minOf(max, honest.size))
            }
            return honest.take(count.coerceIn(1, max))
        }
        // A lie: one card is the likeliest to pass, two to get rid of a hand quickly.
        val count = if (level == BotLevel.NORMAL && odd.size > 3 && random.nextInt(4) == 0) 2 else 1
        return odd.take(count)
    }

    companion object {
        /** The chance that [draws] cards out of [pool], [hits] of them good, hold at least [needed] good ones. */
        fun atLeast(pool: Int, hits: Int, draws: Int, needed: Int): Double {
            if (needed <= 0) return 1.0
            val n = minOf(draws, pool)
            var total = 0.0
            for (k in needed..minOf(n, hits)) total += choose(hits, k) * choose(pool - hits, n - k)
            return (total / choose(pool, n)).coerceIn(0.0, 1.0)
        }

        private fun choose(n: Int, k: Int): Double {
            if (k < 0 || k > n) return 0.0
            var result = 1.0
            for (i in 0 until k) result = result * (n - i) / (i + 1)
            return result
        }
    }
}
