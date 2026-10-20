package io.github.konyaco.tukuai.engine.guandan

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.card.Suit

/** The shapes a Guandan play can take. */
enum class GdPatternType {
    SINGLE,
    PAIR,
    TRIPLE,

    /** Three of a rank with a pair. */
    FULL_HOUSE,

    /** Exactly five ranks in a row, one card each, from A 2 3 4 5 up to 10 J Q K A. */
    STRAIGHT,

    /** Exactly three pairs in a row. */
    PAIR_RUN,

    /** Exactly two threes in a row. */
    PLANE,

    /** Four to ten cards of one rank. */
    BOMB,

    /** A straight all in one suit: stronger than a five-card bomb, weaker than a six-card one. */
    STRAIGHT_FLUSH,

    /** All four jokers, the strongest play there is. */
    JOKER_BOMB,
}

/**
 * One reading of a play.
 *
 * @param main what decides between two plays of a type: the [gdValue] of the rank for
 *   groups of one rank and full houses, and the place of the top card for runs
 * @param size how many cards; it matters only between bombs
 */
data class GdPattern(val type: GdPatternType, val main: Int, val size: Int) {
    /**
     * Where a bomb stands among bombs, -1 for other plays: four of a kind, five, a straight
     * flush, then six to ten of a kind, then the four jokers.
     */
    val tier: Int
        get() = when (type) {
            GdPatternType.BOMB -> when (size) {
                4 -> 0
                5 -> 1
                else -> size - 3
            }
            GdPatternType.STRAIGHT_FLUSH -> 2
            GdPatternType.JOKER_BOMB -> 8
            else -> -1
        }

    val isBomb: Boolean get() = tier >= 0

    /** True when this play may be laid on top of [other]. */
    fun beats(other: GdPattern): Boolean = when {
        isBomb && other.isBomb -> tier > other.tier || (tier == other.tier && main > other.main)
        isBomb -> true
        other.isBomb -> false
        else -> type == other.type && main > other.main
    }
}

data class GdPlay(val cards: List<PlayingCard>, val pattern: GdPattern)

object GdPatterns {
    /**
     * Every way the cards can be read with [level] as the rank in play, strongest first.
     * The wild hearts of that rank fill in for whatever the play needs.
     */
    fun classify(cards: Collection<PlayingCard>, level: Rank): List<GdPattern> {
        val n = cards.size
        if (n == 0) return emptyList()
        val wilds = cards.count { it.isWild(level) }
        val rest = cards.filterNot { it.isWild(level) }
        val found = LinkedHashSet<GdPattern>()
        val small = rest.count { it == PlayingCard.BlackJoker }
        val big = rest.count { it == PlayingCard.RedJoker }
        if (n == 4 && small == 2 && big == 2) found += GdPattern(GdPatternType.JOKER_BOMB, GdValue.BIG_JOKER, 4)

        val values = rest.map { it.gdValue(level) }.toSet()
        if (values.size <= 1) {
            // Wild cards on their own are simply cards of the level.
            val value = values.firstOrNull() ?: GdValue.LEVEL
            val joker = value >= GdValue.SMALL_JOKER
            when {
                n == 1 -> found += GdPattern(GdPatternType.SINGLE, value, 1)
                n == 2 && (!joker || wilds == 0) -> found += GdPattern(GdPatternType.PAIR, value, 2)
                n == 3 && !joker -> found += GdPattern(GdPatternType.TRIPLE, value, 3)
                n in 4..10 && !joker -> found += GdPattern(GdPatternType.BOMB, value, n)
            }
        }
        if (n == 5) fullHouses(rest, wilds, level, found)
        if (small + big == 0) {
            if (n == 5) runs(rest, 1, 5, GdPatternType.STRAIGHT, found)
            if (n == 6) {
                runs(rest, 2, 3, GdPatternType.PAIR_RUN, found)
                runs(rest, 3, 2, GdPatternType.PLANE, found)
            }
        }
        return found.sortedWith(compareByDescending<GdPattern> { it.tier }.thenByDescending { it.main })
    }

    private fun fullHouses(rest: List<PlayingCard>, wilds: Int, level: Rank, found: MutableSet<GdPattern>) {
        val counts = IntArray(GdValue.SLOTS)
        rest.forEach { counts[it.gdValue(level)]++ }
        val present = counts.indices.filter { counts[it] > 0 }
        if (present.size > 2) return
        for (three in present) {
            if (three >= GdValue.SMALL_JOKER || counts[three] > 3) continue
            val needThree = 3 - counts[three]
            val others = present.filter { it != three }
            val needPair = when (val pair = others.firstOrNull()) {
                // Only wild cards left for the pair: they make a pair of the level.
                null -> if (three == GdValue.LEVEL) continue else 2
                else -> {
                    if (counts[pair] > 2) continue
                    if (pair >= GdValue.SMALL_JOKER && counts[pair] < 2) continue
                    2 - counts[pair]
                }
            }
            if (needThree + needPair == wilds) found += GdPattern(GdPatternType.FULL_HOUSE, three, 5)
        }
    }

    /**
     * Runs of [length] places, [width] cards a place, that [rest] fits with the wild cards
     * filling the gaps; a five-card straight in one suit is also a straight flush.
     */
    private fun runs(rest: List<PlayingCard>, width: Int, length: Int, type: GdPatternType, found: MutableSet<GdPattern>) {
        val regular = rest.filterIsInstance<PlayingCard.Regular>()
        for (start in GdValue.LOW_ACE..GdValue.ACE - length + 1) {
            val window = start until start + length
            val counts = IntArray(length)
            val fits = regular.all { card ->
                val place = places(card.rank).firstOrNull { it in window } ?: return@all false
                ++counts[place - start] <= width
            }
            if (!fits) continue
            val top = start + length - 1
            found += GdPattern(type, top, width * length)
            if (type == GdPatternType.STRAIGHT && regular.map { it.suit }.toSet().size <= 1) {
                found += GdPattern(GdPatternType.STRAIGHT_FLUSH, top, 5)
            }
        }
    }

    private fun places(rank: Rank): List<Int> =
        if (rank == Rank.ACE) listOf(GdValue.LOW_ACE, GdValue.ACE) else listOf(GdValue.natural(rank))

    /** The reading the table records for a play, or null when the cards make no play that may go on [toBeat]. */
    fun readingFor(cards: Collection<PlayingCard>, toBeat: GdPattern?, level: Rank): GdPattern? {
        val readings = classify(cards, level)
        return if (toBeat == null) readings.firstOrNull() else readings.firstOrNull { it.beats(toBeat) }
    }

    /**
     * Plays from [hand] that beat [toBeat], or every kind of lead when it is null: one play
     * for each reading, using as few wild cards as it can. It is for hints and computers, but
     * it never misses a reading: when it returns nothing, nothing in the hand can be played.
     */
    fun options(hand: List<PlayingCard>, toBeat: GdPattern?, level: Rank): List<GdPlay> {
        val wild = hand.filter { it.isWild(level) }
        val rest = hand.filterNot { it.isWild(level) }.sortedWith(GdCards.ascending(level))
        val byValue = rest.groupBy { it.gdValue(level) }
        val byRank = rest.filterIsInstance<PlayingCard.Regular>().groupBy { it.rank }
        val out = ArrayList<GdPlay>()
        val wanted = toBeat?.type?.takeUnless { toBeat.isBomb }
        fun wants(type: GdPatternType) = toBeat == null || type == wanted
        fun add(cards: List<PlayingCard>, pattern: GdPattern) {
            if (toBeat == null || pattern.beats(toBeat)) out += GdPlay(cards, pattern)
        }

        // Groups of one rank, up to bombs of ten.
        for (value in 2..GdValue.BIG_JOKER) {
            val natural = byValue[value].orEmpty()
            val joker = value >= GdValue.SMALL_JOKER
            if (natural.isEmpty() && !(value == GdValue.LEVEL && wild.isNotEmpty())) continue
            val most = minOf(10, natural.size + if (joker) 0 else wild.size)
            for (k in 1..most) {
                val type = when (k) {
                    1 -> GdPatternType.SINGLE
                    2 -> GdPatternType.PAIR
                    3 -> GdPatternType.TRIPLE
                    else -> GdPatternType.BOMB
                }
                if (joker && k > 2) break
                if (type != GdPatternType.BOMB && !wants(type)) continue
                add(natural.take(k) + wild.take(maxOf(0, k - natural.size)), GdPattern(type, value, k))
            }
        }

        if (wants(GdPatternType.FULL_HOUSE)) {
            for (three in 2..GdValue.LEVEL) {
                val natural = byValue[three].orEmpty()
                if (natural.isEmpty()) continue
                val needThree = maxOf(0, 3 - natural.size)
                if (needThree > wild.size) continue
                val left = wild.size - needThree
                // The cheapest pair: fewest wild cards, then not out of a bigger group, then lowest.
                val pair = byValue.keys.filter { it != three }.mapNotNull { value ->
                    val have = byValue.getValue(value).size
                    val need = maxOf(0, 2 - have)
                    if (need > left || (value >= GdValue.SMALL_JOKER && need > 0)) null else Triple(value, need, have)
                }.minWithOrNull(compareBy<Triple<Int, Int, Int>> { it.second }.thenBy { it.third > 2 }.thenBy { it.third >= 4 }.thenBy { it.first })
                    ?: continue
                val cards = natural.take(3) + wild.take(needThree) + byValue.getValue(pair.first).take(2) + wild.drop(needThree).take(pair.second)
                add(cards, GdPattern(GdPatternType.FULL_HOUSE, three, 5))
            }
        }

        fun runs(width: Int, length: Int, type: GdPatternType) {
            if (!wants(type)) return
            for (start in GdValue.LOW_ACE..GdValue.ACE - length + 1) {
                val cards = ArrayList<PlayingCard>()
                var need = 0
                for (place in start until start + length) {
                    val have = byRank[GdValue.rankAt(place)].orEmpty().take(width)
                    cards += have
                    need += width - have.size
                }
                if (need > wild.size) continue
                add(cards + wild.take(need), GdPattern(type, start + length - 1, width * length))
            }
        }
        runs(1, 5, GdPatternType.STRAIGHT)
        runs(2, 3, GdPatternType.PAIR_RUN)
        runs(3, 2, GdPatternType.PLANE)

        // Straight flushes, suit by suit.
        val regular = rest.filterIsInstance<PlayingCard.Regular>().toSet()
        for (suit in Suit.entries) {
            for (start in GdValue.LOW_ACE..GdValue.ACE - 4) {
                val cards = ArrayList<PlayingCard>()
                var need = 0
                for (place in start until start + 5) {
                    val card = PlayingCard.Regular(GdValue.rankAt(place), suit)
                    if (card in regular) cards += card else need++
                }
                if (need > wild.size) continue
                add(cards + wild.take(need), GdPattern(GdPatternType.STRAIGHT_FLUSH, start + 4, 5))
            }
        }

        if (rest.count { it == PlayingCard.BlackJoker } == 2 && rest.count { it == PlayingCard.RedJoker } == 2) {
            add(rest.filter { it.isJoker }, GdPattern(GdPatternType.JOKER_BOMB, GdValue.BIG_JOKER, 4))
        }
        return out.distinctBy { it.pattern }
    }
}
