package io.github.konyaco.tukuai.engine.doudizhu.bot

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.doudizhu.DdzCards
import io.github.konyaco.tukuai.engine.doudizhu.DdzValue
import io.github.konyaco.tukuai.engine.doudizhu.Pattern
import io.github.konyaco.tukuai.engine.doudizhu.PatternType
import io.github.konyaco.tukuai.engine.doudizhu.Play
import io.github.konyaco.tukuai.engine.doudizhu.Patterns

/**
 * Splits a hand into the plays it would most like to make, and scores how good the
 * hand is. Higher scores mean fewer turns needed and stronger cards. The split is an
 * exact search over every way to cut the hand into singles, pairs, trios, bombs,
 * straights, pair straights and planes; kickers are attached afterwards.
 */
object HandAnalysis {
    /** Each extra turn the hand needs costs this much. */
    const val TURN_COST = 7

    data class Split(val score: Int, val plays: List<Play>)

    private class Node(val score: Int, val groups: List<Pattern>)

    fun split(hand: List<PlayingCard>): Split {
        if (hand.isEmpty()) return Split(0, emptyList())
        val counts = DdzCards.counts(hand)
        val memo = HashMap<Long, Node>()
        val groups = best(counts, memo).groups
        val plays = attachKickers(hand, groups)
        return Split(plays.sumOf { value(it.pattern) - TURN_COST }, plays)
    }

    fun score(hand: List<PlayingCard>): Int = split(hand).score

    /** How strong a single group is, before the cost of the turn it takes. */
    fun value(p: Pattern): Int {
        val top = p.main + p.length - 1
        return when (p.type) {
            PatternType.SINGLE, PatternType.PAIR -> p.main - 10
            PatternType.TRIO -> p.main - 10 + 3
            PatternType.TRIO_SINGLE, PatternType.TRIO_PAIR -> p.main - 10 + TURN_COST
            PatternType.STRAIGHT, PatternType.PAIR_STRAIGHT -> top - 10 + 1
            PatternType.PLANE -> top - 10 + 3 * p.length
            PatternType.PLANE_SINGLES, PatternType.PLANE_PAIRS -> top - 10 + TURN_COST * p.length
            PatternType.FOUR_TWO_SINGLES, PatternType.FOUR_TWO_PAIRS -> p.main - 10 + TURN_COST
            PatternType.BOMB -> 12 + (p.main - 3) / 3
            PatternType.ROCKET -> 20
        }
    }

    private fun key(counts: IntArray): Long {
        var k = 0L
        for (v in DdzValue.ALL) k = k * 5 + counts[v]
        return k
    }

    private fun best(counts: IntArray, memo: HashMap<Long, Node>): Node {
        val low = DdzValue.ALL.firstOrNull { counts[it] > 0 } ?: return Node(0, emptyList())
        val k = key(counts)
        memo[k]?.let { return it }

        var bestNode: Node? = null
        fun consider(group: Pattern, values: List<Int>) {
            values.forEach { counts[it]-- }
            val rest = best(counts, memo)
            values.forEach { counts[it]++ }
            val score = rest.score + value(group) - TURN_COST
            if (bestNode == null || score > bestNode!!.score) bestNode = Node(score, listOf(group) + rest.groups)
        }

        val c = counts[low]
        if (low == DdzValue.BLACK_JOKER && counts[DdzValue.RED_JOKER] == 1) {
            consider(Pattern(PatternType.ROCKET, DdzValue.RED_JOKER), listOf(DdzValue.BLACK_JOKER, DdzValue.RED_JOKER))
        }
        val whole = when (c) {
            1 -> PatternType.SINGLE
            2 -> PatternType.PAIR
            3 -> PatternType.TRIO
            else -> PatternType.BOMB
        }
        consider(Pattern(whole, low), List(c) { low })
        if (c == 4) consider(Pattern(PatternType.TRIO, low), List(3) { low })
        if (c >= 2) consider(Pattern(PatternType.SINGLE, low), listOf(low))

        sequences(counts, low, 1, 5, PatternType.STRAIGHT, ::consider)
        sequences(counts, low, 2, 3, PatternType.PAIR_STRAIGHT, ::consider)
        sequences(counts, low, 3, 2, PatternType.PLANE, ::consider)

        val node = bestNode!!
        memo[k] = node
        return node
    }

    private inline fun sequences(
        counts: IntArray,
        start: Int,
        width: Int,
        minLength: Int,
        type: PatternType,
        consider: (Pattern, List<Int>) -> Unit,
    ) {
        if (start > DdzValue.HIGHEST_IN_SEQUENCE) return
        var end = start
        while (end <= DdzValue.HIGHEST_IN_SEQUENCE && counts[end] >= width) end++
        val maxLength = end - start
        for (length in minLength..maxLength) {
            consider(Pattern(type, start, length), (start until start + length).flatMap { v -> List(width) { v } })
        }
    }

    /**
     * Gives trios and planes the lowest loose singles or pairs as kickers. A kicker is
     * only taken when it is weak (below A), because a strong single is better kept.
     */
    private fun attachKickers(hand: List<PlayingCard>, groups: List<Pattern>): List<Play> {
        val singles = groups.filter { it.type == PatternType.SINGLE && it.main < DdzValue.ACE }
            .sortedBy { it.main }.toMutableList()
        val pairs = groups.filter { it.type == PatternType.PAIR && it.main < DdzValue.ACE }
            .sortedBy { it.main }.toMutableList()
        val used = ArrayList<Pattern>()
        val result = ArrayList<Pair<Pattern, List<Int>>>()

        val carriers = groups.filter { it.type == PatternType.TRIO || it.type == PatternType.PLANE }
            .sortedByDescending { it.length }
        for (carrier in carriers) {
            val span = carrier.main until carrier.main + carrier.length
            val body = span.flatMap { listOf(it, it, it) }
            val need = carrier.length
            // A kicker of the carrier's own value would turn a trio into a bomb, so skip those.
            val freeSingles = singles.filter { it.main !in span }
            val freePairs = pairs.filter { it.main !in span }
            val withPairs = freeSingles.size < need
            val kickers = (if (withPairs) freePairs else freeSingles).take(need)
            if (kickers.size < need) {
                result += carrier to body
                continue
            }
            kickers.forEach { (if (withPairs) pairs else singles).remove(it) }
            used += kickers
            val type = when {
                carrier.type == PatternType.TRIO && withPairs -> PatternType.TRIO_PAIR
                carrier.type == PatternType.TRIO -> PatternType.TRIO_SINGLE
                withPairs -> PatternType.PLANE_PAIRS
                else -> PatternType.PLANE_SINGLES
            }
            val kickerValues = kickers.flatMap { k -> List(if (withPairs) 2 else 1) { k.main } }
            result += Pattern(type, carrier.main, carrier.length) to body + kickerValues
        }
        for (g in groups) {
            if (g.type == PatternType.TRIO || g.type == PatternType.PLANE) continue
            // The same pattern can appear twice (two single 3s of different suits), so remove one at a time.
            if (used.remove(g)) continue
            result += g to cardsFor(g)
        }

        val pool = hand.toMutableList()
        return result.map { (pattern, values) ->
            val cards = Patterns.take(pool, values)
            pool.removeAll(cards.toSet())
            Play(cards, pattern)
        }
    }

    private fun cardsFor(p: Pattern): List<Int> = when (p.type) {
        PatternType.SINGLE -> listOf(p.main)
        PatternType.PAIR -> listOf(p.main, p.main)
        PatternType.BOMB -> List(4) { p.main }
        PatternType.ROCKET -> listOf(DdzValue.BLACK_JOKER, DdzValue.RED_JOKER)
        PatternType.STRAIGHT -> (p.main until p.main + p.length).toList()
        PatternType.PAIR_STRAIGHT -> (p.main until p.main + p.length).flatMap { listOf(it, it) }
        else -> error("Unexpected group $p")
    }
}
