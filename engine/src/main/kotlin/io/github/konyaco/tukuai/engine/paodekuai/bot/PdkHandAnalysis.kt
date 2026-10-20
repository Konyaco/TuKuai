package io.github.konyaco.tukuai.engine.paodekuai.bot

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.doudizhu.DdzCards
import io.github.konyaco.tukuai.engine.doudizhu.DdzValue
import io.github.konyaco.tukuai.engine.paodekuai.PdkPattern
import io.github.konyaco.tukuai.engine.paodekuai.PdkPatternType
import io.github.konyaco.tukuai.engine.paodekuai.PdkPatterns
import io.github.konyaco.tukuai.engine.paodekuai.PdkPlay

/**
 * Splits a Pao De Kuai hand into the plays it would most like to make, and scores how good
 * the hand is, the way the Dou Dizhu computer's hand analysis does: an exact search over
 * every way to cut the hand into singles, pairs, threes, bombs and runs, with the cards
 * that go with threes and planes picked afterwards. Higher scores need fewer turns.
 */
object PdkHandAnalysis {
    /** Each extra turn the hand needs costs this much. */
    const val TURN_COST = 7

    data class Split(val score: Int, val plays: List<PdkPlay>)

    private enum class Kind { SINGLE, PAIR, TRIO, BOMB, STRAIGHT, PAIR_RUN, PLANE }

    private data class Group(val kind: Kind, val main: Int, val length: Int = 1)

    private class Node(val score: Int, val groups: List<Group>)

    private val VALUES = DdzValue.THREE..DdzValue.TWO

    fun split(hand: List<PlayingCard>): Split {
        if (hand.isEmpty()) return Split(0, emptyList())
        val groups = best(DdzCards.counts(hand), HashMap()).groups
        val plays = withExtras(hand, groups)
        return Split(plays.sumOf { value(it.pattern) - TURN_COST }, plays)
    }

    fun score(hand: List<PlayingCard>): Int = split(hand).score

    /** How strong a play is, before the cost of the turn it takes. */
    fun value(p: PdkPattern): Int {
        val top = p.main + p.length - 1
        return when (p.type) {
            PdkPatternType.SINGLE, PdkPatternType.PAIR -> p.main - 10
            PdkPatternType.STRAIGHT, PdkPatternType.PAIR_RUN -> top - 10 + 1
            PdkPatternType.TRIO_TWO, PdkPatternType.FOUR_THREE -> p.main - 10 + TURN_COST
            PdkPatternType.PLANE -> top - 10 + TURN_COST * p.length
            PdkPatternType.BOMB -> 14 + (p.main - 3) / 3
        }
    }

    private fun groupValue(g: Group): Int {
        val top = g.main + g.length - 1
        return when (g.kind) {
            Kind.SINGLE, Kind.PAIR -> g.main - 10
            // A three takes two other cards along, which saves turns once they are picked.
            Kind.TRIO -> g.main - 10 + 4
            Kind.PLANE -> top - 10 + 4 * g.length
            Kind.STRAIGHT, Kind.PAIR_RUN -> top - 10 + 1
            Kind.BOMB -> 14 + (g.main - 3) / 3
        }
    }

    private fun key(counts: IntArray): Long {
        var k = 0L
        for (v in VALUES) k = k * 5 + counts[v]
        return k
    }

    private fun best(counts: IntArray, memo: HashMap<Long, Node>): Node {
        val low = VALUES.firstOrNull { counts[it] > 0 } ?: return Node(0, emptyList())
        val k = key(counts)
        memo[k]?.let { return it }

        var bestNode: Node? = null
        fun consider(group: Group, values: List<Int>) {
            values.forEach { counts[it]-- }
            val rest = best(counts, memo)
            values.forEach { counts[it]++ }
            val score = rest.score + groupValue(group) - TURN_COST
            if (bestNode == null || score > bestNode!!.score) bestNode = Node(score, listOf(group) + rest.groups)
        }

        val c = counts[low]
        val whole = when (c) {
            1 -> Kind.SINGLE
            2 -> Kind.PAIR
            3 -> Kind.TRIO
            else -> Kind.BOMB
        }
        consider(Group(whole, low), List(c) { low })
        if (c == 4) consider(Group(Kind.TRIO, low), List(3) { low })
        if (c >= 2) consider(Group(Kind.SINGLE, low), listOf(low))
        if (c >= 3) consider(Group(Kind.PAIR, low), listOf(low, low))

        runs(counts, low, 1, 5, Kind.STRAIGHT, ::consider)
        runs(counts, low, 2, 2, Kind.PAIR_RUN, ::consider)
        runs(counts, low, 3, 2, Kind.PLANE, ::consider)

        val node = bestNode!!
        memo[k] = node
        return node
    }

    private inline fun runs(counts: IntArray, start: Int, width: Int, minLength: Int, kind: Kind, consider: (Group, List<Int>) -> Unit) {
        if (start > DdzValue.HIGHEST_IN_SEQUENCE) return
        var end = start
        while (end <= DdzValue.HIGHEST_IN_SEQUENCE && counts[end] >= width) end++
        for (length in minLength..end - start) {
            consider(Group(kind, start, length), (start until start + length).flatMap { v -> List(width) { v } })
        }
    }

    /**
     * Gives each three and plane two cards a step from the low loose singles, then the low
     * pairs. One without enough stays bare, which only works as the last cards.
     */
    private fun withExtras(hand: List<PlayingCard>, groups: List<Group>): List<PdkPlay> {
        val singles = groups.filter { it.kind == Kind.SINGLE && it.main < DdzValue.ACE }.map { it.main }.sorted().toMutableList()
        val pairs = groups.filter { it.kind == Kind.PAIR && it.main < DdzValue.ACE }.map { it.main }.sorted().toMutableList()
        val usedSingles = ArrayList<Int>()
        val usedPairs = ArrayList<Int>()
        val brokenPairs = ArrayList<Int>()
        val result = ArrayList<Pair<PdkPattern, List<Int>>>()

        for (carrier in groups.filter { it.kind == Kind.TRIO || it.kind == Kind.PLANE }.sortedByDescending { it.length }) {
            val span = carrier.main until carrier.main + carrier.length
            val body = span.flatMap { listOf(it, it, it) }
            var need = 2 * carrier.length
            val extra = ArrayList<Int>()
            while (need > 0 && singles.isNotEmpty()) {
                extra += singles.removeAt(0).also { usedSingles += it }
                need--
            }
            while (need > 0 && pairs.isNotEmpty()) {
                val pair = pairs.removeAt(0)
                if (need >= 2) {
                    extra += listOf(pair, pair)
                    usedPairs += pair
                    need -= 2
                } else {
                    // One card of the pair goes along; the other is left as a single.
                    extra += pair
                    brokenPairs += pair
                    need = 0
                }
            }
            val type = if (carrier.kind == Kind.TRIO) PdkPatternType.TRIO_TWO else PdkPatternType.PLANE
            result += PdkPattern(type, carrier.main, carrier.length) to body + extra
        }
        for (g in groups) {
            when (g.kind) {
                Kind.TRIO, Kind.PLANE -> continue
                Kind.SINGLE -> if (usedSingles.remove(g.main)) continue
                Kind.PAIR -> {
                    if (usedPairs.remove(g.main)) continue
                    if (brokenPairs.remove(g.main)) {
                        result += PdkPattern(PdkPatternType.SINGLE, g.main) to listOf(g.main)
                        continue
                    }
                }
                else -> {}
            }
            result += patternOf(g) to valuesOf(g)
        }

        val pool = hand.toMutableList()
        return result.map { (pattern, values) ->
            val cards = PdkPatterns.take(pool, values)
            pool.removeAll(cards.toSet())
            PdkPlay(cards, pattern)
        }
    }

    private fun patternOf(g: Group): PdkPattern = when (g.kind) {
        Kind.SINGLE -> PdkPattern(PdkPatternType.SINGLE, g.main)
        Kind.PAIR -> PdkPattern(PdkPatternType.PAIR, g.main)
        Kind.BOMB -> PdkPattern(PdkPatternType.BOMB, g.main)
        Kind.STRAIGHT -> PdkPattern(PdkPatternType.STRAIGHT, g.main, g.length)
        Kind.PAIR_RUN -> PdkPattern(PdkPatternType.PAIR_RUN, g.main, g.length)
        Kind.TRIO, Kind.PLANE -> error("Threes are handled with their extra cards")
    }

    private fun valuesOf(g: Group): List<Int> = when (g.kind) {
        Kind.SINGLE -> listOf(g.main)
        Kind.PAIR -> listOf(g.main, g.main)
        Kind.BOMB -> List(4) { g.main }
        Kind.STRAIGHT -> (g.main until g.main + g.length).toList()
        Kind.PAIR_RUN -> (g.main until g.main + g.length).flatMap { listOf(it, it) }
        Kind.TRIO, Kind.PLANE -> error("Threes are handled with their extra cards")
    }
}
