package io.github.konyaco.tukuai.engine.guandan.bot

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.guandan.GdCards
import io.github.konyaco.tukuai.engine.guandan.GdPattern
import io.github.konyaco.tukuai.engine.guandan.GdPatternType
import io.github.konyaco.tukuai.engine.guandan.GdPatterns
import io.github.konyaco.tukuai.engine.guandan.GdPlay
import io.github.konyaco.tukuai.engine.guandan.GdValue
import io.github.konyaco.tukuai.engine.guandan.isWild

/**
 * Splits a Guandan hand into the plays it would most like to make, and scores how good the
 * hand is, as the other games' computers do: an exact search over the ways to cut the hand
 * into groups of one rank, straights, pairs in a row and planes, with the wild cards
 * filling gaps wherever they help most. Straight flushes are tried on top of that, and
 * each three takes the lowest spare pair along. Higher scores need fewer turns.
 */
object GdHandAnalysis {
    /** Each extra turn the hand needs costs this much. */
    const val TURN_COST = 7

    data class Split(val score: Int, val plays: List<GdPlay>)

    /**
     * One group: [places] are the printed places it takes a card from, once per card, and
     * [wilds] how many wild cards it uses.
     */
    private data class Group(val type: GdPatternType, val main: Int, val size: Int, val places: List<Int>, val wilds: Int)

    private class Node(val score: Int, val groups: List<Group>)

    private val PLACES = GdValue.TWO..GdValue.ACE

    fun split(hand: List<PlayingCard>, level: Rank): Split {
        if (hand.isEmpty()) return Split(0, emptyList())
        var best = splitWithout(hand, level, null)
        // A straight flush or two can be worth more than whatever its cards would make otherwise.
        val flushes = GdPatterns.options(hand, null, level).filter { it.pattern.type == GdPatternType.STRAIGHT_FLUSH }.take(MAX_FLUSHES)
        for (flush in flushes) {
            val rest = GdCards.minus(hand, flush.cards) ?: continue
            val other = splitWithout(rest, level, flush)
            if (other.score > best.score) best = other
        }
        return best
    }

    fun score(hand: List<PlayingCard>, level: Rank): Int = split(hand, level).score

    private fun splitWithout(hand: List<PlayingCard>, level: Rank, flush: GdPlay?): Split {
        val counts = IntArray(GdValue.SLOTS)
        var wilds = 0
        var small = 0
        var big = 0
        for (card in hand) {
            when {
                card.isWild(level) -> wilds++
                card == PlayingCard.BlackJoker -> small++
                card == PlayingCard.RedJoker -> big++
                card is PlayingCard.Regular -> counts[GdValue.natural(card.rank)]++
            }
        }
        val levelPlace = GdValue.natural(level)
        val groups = best(counts, wilds, levelPlace, HashMap()).groups.toMutableList()
        groups += jokerGroups(small, big)
        val plays = ArrayList<GdPlay>()
        flush?.let { plays += it }
        plays += toPlays(hand, attachPairs(groups), level)
        return Split(plays.sumOf { value(it.pattern) - TURN_COST }, plays)
    }

    private fun jokerGroups(small: Int, big: Int): List<Group> {
        if (small == 2 && big == 2) return listOf(Group(GdPatternType.JOKER_BOMB, GdValue.BIG_JOKER, 4, emptyList(), 0))
        val out = ArrayList<Group>()
        for ((value, count) in listOf(GdValue.SMALL_JOKER to small, GdValue.BIG_JOKER to big)) {
            when (count) {
                1 -> out += Group(GdPatternType.SINGLE, value, 1, listOf(-value), 0)
                2 -> out += Group(GdPatternType.PAIR, value, 2, listOf(-value, -value), 0)
            }
        }
        return out
    }

    /** How strong a play is, before the cost of the turn it takes. */
    fun value(p: GdPattern): Int = when (p.type) {
        GdPatternType.SINGLE, GdPatternType.PAIR -> p.main - 9
        GdPatternType.TRIPLE -> p.main - 9 + 2
        GdPatternType.FULL_HOUSE -> p.main - 9 + TURN_COST
        GdPatternType.STRAIGHT, GdPatternType.PAIR_RUN -> p.main - 9 + 1
        GdPatternType.PLANE -> p.main - 9 + 4
        else -> 12 + 2 * p.tier + (p.main - 9) / 3
    }

    private fun groupValue(g: Group): Int = when (g.type) {
        // A three can take a pair along, which saves a turn once it is picked.
        GdPatternType.TRIPLE -> g.main - 9 + 4
        else -> value(GdPattern(g.type, g.main, g.size))
    }

    private fun key(counts: IntArray, wilds: Int): Long {
        var k = wilds.toLong()
        for (p in PLACES) k = k * 9 + counts[p]
        return k
    }

    private fun best(counts: IntArray, wilds: Int, levelPlace: Int, memo: HashMap<Long, Node>): Node {
        val low = PLACES.firstOrNull { counts[it] > 0 }
        if (low == null) {
            return when (wilds) {
                0 -> Node(0, emptyList())
                1 -> Node(value(GdPattern(GdPatternType.SINGLE, GdValue.LEVEL, 1)) - TURN_COST, listOf(Group(GdPatternType.SINGLE, GdValue.LEVEL, 1, emptyList(), 1)))
                else -> Node(value(GdPattern(GdPatternType.PAIR, GdValue.LEVEL, 2)) - TURN_COST, listOf(Group(GdPatternType.PAIR, GdValue.LEVEL, 2, emptyList(), 2)))
            }
        }
        val k = key(counts, wilds)
        memo[k]?.let { return it }

        var bestNode: Node? = null
        fun consider(group: Group) {
            group.places.forEach { counts[it]-- }
            val rest = best(counts, wilds - group.wilds, levelPlace, memo)
            group.places.forEach { counts[it]++ }
            val score = rest.score + groupValue(group) - TURN_COST
            if (bestNode == null || score > bestNode!!.score) bestNode = Node(score, listOf(group) + rest.groups)
        }

        // Cards of the lowest rank left, alone or with wild cards, all of them or some.
        val c = counts[low]
        val value = if (low == levelPlace) GdValue.LEVEL else low
        fun same(take: Int, extra: Int) {
            val size = take + extra
            val type = when (size) {
                1 -> GdPatternType.SINGLE
                2 -> GdPatternType.PAIR
                3 -> GdPatternType.TRIPLE
                else -> GdPatternType.BOMB
            }
            if (size <= 10) consider(Group(type, value, size, List(take) { low }, extra))
        }
        for (extra in 0..wilds) same(c, extra)
        if (c >= 2) same(1, 0)
        if (c >= 3) same(2, 0)
        if (c >= 4) same(3, 0)
        if (c >= 5) same(4, 0)

        // Runs that take the lowest card, wild cards filling any gaps.
        fun runs(width: Int, length: Int, type: GdPatternType) {
            for (start in maxOf(GdValue.TWO, low - length + 1)..low) {
                val end = start + length - 1
                if (end > GdValue.ACE) break
                var need = 0
                val places = ArrayList<Int>()
                for (p in start..end) {
                    val have = minOf(width, counts[p])
                    repeat(have) { places += p }
                    need += width - have
                }
                if (need <= wilds) consider(Group(type, end, width * length, places, need))
            }
        }
        runs(1, 5, GdPatternType.STRAIGHT)
        runs(2, 3, GdPatternType.PAIR_RUN)
        runs(3, 2, GdPatternType.PLANE)

        val node = bestNode!!
        memo[k] = node
        return node
    }

    /** Each three that isn't strong takes the lowest pair along, making a full house. */
    private fun attachPairs(groups: List<Group>): List<Group> {
        val rest = groups.toMutableList()
        val out = ArrayList<Group>()
        for (three in groups.filter { it.type == GdPatternType.TRIPLE && it.main < GdValue.LEVEL }.sortedBy { it.main }) {
            val pair = rest.filter { it.type == GdPatternType.PAIR && it.main < GdValue.ACE && it.wilds == 0 && it.main != three.main }
                .minByOrNull { it.main } ?: continue
            rest.remove(three)
            rest.remove(pair)
            out += Group(GdPatternType.FULL_HOUSE, three.main, 5, three.places + pair.places, three.wilds + pair.wilds)
        }
        return out + rest
    }

    private fun toPlays(hand: List<PlayingCard>, groups: List<Group>, level: Rank): List<GdPlay> {
        val pool = hand.toMutableList()
        val wild = pool.filter { it.isWild(level) }.toMutableList()
        pool.removeAll { it.isWild(level) }
        val out = ArrayList<GdPlay>()
        for (g in groups) {
            val cards = ArrayList<PlayingCard>()
            for (place in g.places) {
                val card = when (place) {
                    -GdValue.SMALL_JOKER -> pool.first { it == PlayingCard.BlackJoker }
                    -GdValue.BIG_JOKER -> pool.first { it == PlayingCard.RedJoker }
                    else -> pool.first { it is PlayingCard.Regular && GdValue.natural(it.rank) == place }
                }
                pool.remove(card)
                cards += card
            }
            if (g.type == GdPatternType.JOKER_BOMB) {
                val jokers = pool.filter { it == PlayingCard.BlackJoker || it == PlayingCard.RedJoker }
                cards += jokers
                jokers.forEach { pool.remove(it) }
            }
            repeat(g.wilds) { cards += wild.removeAt(0) }
            val pattern = GdPatterns.readingFor(cards, null, level) ?: continue
            out += GdPlay(cards, pattern)
        }
        return out
    }

    private const val MAX_FLUSHES = 6
}
