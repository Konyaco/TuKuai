package io.github.konyaco.tukuai.engine.doudizhu

import io.github.konyaco.tukuai.engine.card.PlayingCard

/** The shapes a Dou Dizhu play can take. */
enum class PatternType {
    SINGLE,
    PAIR,
    TRIO,
    TRIO_SINGLE,
    TRIO_PAIR,

    /** Five or more consecutive singles. */
    STRAIGHT,

    /** Three or more consecutive pairs. */
    PAIR_STRAIGHT,

    /** Two or more consecutive trios with nothing attached. */
    PLANE,
    PLANE_SINGLES,
    PLANE_PAIRS,
    FOUR_TWO_SINGLES,
    FOUR_TWO_PAIRS,

    /** Four or more cards of one value; with two decks, up to eight. */
    BOMB,

    /** Every joker: the two of one deck, or the four of two decks. */
    ROCKET,
}

/**
 * One reading of a play.
 *
 * @param main the value that decides who wins: the single, the trio, or the lowest step of a sequence
 * @param length how many steps a sequence has, or how many cards a bomb or rocket has; 1 for everything else
 */
data class Pattern(val type: PatternType, val main: Int, val length: Int = 1) {
    val cardCount: Int
        get() = when (type) {
            PatternType.SINGLE -> 1
            PatternType.PAIR -> 2
            PatternType.TRIO -> 3
            PatternType.TRIO_SINGLE -> 4
            PatternType.BOMB, PatternType.ROCKET -> length
            PatternType.TRIO_PAIR -> 5
            PatternType.STRAIGHT -> length
            PatternType.PAIR_STRAIGHT -> 2 * length
            PatternType.PLANE -> 3 * length
            PatternType.PLANE_SINGLES -> 4 * length
            PatternType.PLANE_PAIRS -> 5 * length
            PatternType.FOUR_TWO_SINGLES -> 6
            PatternType.FOUR_TWO_PAIRS -> 8
        }

    val isBomb: Boolean get() = type == PatternType.BOMB || type == PatternType.ROCKET

    /** True when this play may be laid on top of [other]. */
    fun beats(other: Pattern): Boolean = when {
        type == PatternType.ROCKET -> true
        other.type == PatternType.ROCKET -> false
        // More cards make the bigger bomb; among bombs of as many cards, the higher value.
        type == PatternType.BOMB && other.type == PatternType.BOMB ->
            length > other.length || (length == other.length && main > other.main)
        type == PatternType.BOMB -> true
        other.type == PatternType.BOMB -> false
        else -> type == other.type && length == other.length && main > other.main
    }
}

/** A concrete play: the cards and the reading the table will use for them. */
data class Play(val cards: List<PlayingCard>, val pattern: Pattern)

object Patterns {
    /**
     * Every way the cards can be read, most natural first. A play is legal when
     * at least one reading is allowed; usually there is exactly one.
     */
    fun classify(cards: Collection<PlayingCard>, rules: DdzRules): List<Pattern> {
        if (cards.isEmpty()) return emptyList()
        return classifyCounts(DdzCards.counts(cards), cards.size, rules)
    }

    fun classifyCounts(counts: IntArray, total: Int, rules: DdzRules): List<Pattern> {
        val found = LinkedHashSet<Pattern>()
        val present = DdzValue.ALL.filter { counts[it] > 0 }

        val jokers = rules.decks
        if (total == 2 * jokers && counts[DdzValue.BLACK_JOKER] == jokers && counts[DdzValue.RED_JOKER] == jokers) {
            found += Pattern(PatternType.ROCKET, DdzValue.RED_JOKER, total)
        }
        if (present.size == 1) {
            val value = present[0]
            when (val n = counts[value]) {
                1 -> found += Pattern(PatternType.SINGLE, value)
                2 -> found += Pattern(PatternType.PAIR, value)
                3 -> found += Pattern(PatternType.TRIO, value)
                else -> found += Pattern(PatternType.BOMB, value, n)
            }
        }
        if (total == 4 && present.size == 2 && rules.trioSingles) {
            present.firstOrNull { counts[it] == 3 }?.let { found += Pattern(PatternType.TRIO_SINGLE, it) }
        }
        if (total == 5 && present.size == 2) {
            val trio = present.firstOrNull { counts[it] == 3 }
            val pair = present.firstOrNull { counts[it] == 2 }
            if (trio != null && pair != null) found += Pattern(PatternType.TRIO_PAIR, trio)
        }
        if (rules.allowFourWithTwo) {
            for (four in present.filter { counts[it] == 4 }) {
                if (total == 6) found += Pattern(PatternType.FOUR_TWO_SINGLES, four)
                if (total == 8 && present.filter { it != four }.all { counts[it] % 2 == 0 }) {
                    found += Pattern(PatternType.FOUR_TWO_PAIRS, four)
                }
            }
        }
        sequenceOf(present, counts, 1, 5)?.let { found += Pattern(PatternType.STRAIGHT, it.first, it.second) }
        sequenceOf(present, counts, 2, 3)?.let { found += Pattern(PatternType.PAIR_STRAIGHT, it.first, it.second) }
        sequenceOf(present, counts, 3, 2)?.let { found += Pattern(PatternType.PLANE, it.first, it.second) }
        addPlanesWithKickers(counts, total, rules, found)

        return found.sortedWith(
            compareByDescending<Pattern> { it.isBomb }.thenByDescending { it.length }.thenByDescending { it.main },
        )
    }

    /** When every present value has exactly [width] cards and they run in a row, returns (lowest, length). */
    private fun sequenceOf(present: List<Int>, counts: IntArray, width: Int, minLength: Int): Pair<Int, Int>? {
        if (present.size < minLength) return null
        if (present.any { counts[it] != width || it > DdzValue.HIGHEST_IN_SEQUENCE }) return null
        if (present.last() - present.first() != present.size - 1) return null
        return present.first() to present.size
    }

    private fun addPlanesWithKickers(counts: IntArray, total: Int, rules: DdzRules, found: MutableSet<Pattern>) {
        for (length in 2..6) {
            val singles = total == 4 * length && rules.trioSingles
            val pairs = total == 5 * length
            if (!singles && !pairs) continue
            for (start in DdzValue.THREE..DdzValue.HIGHEST_IN_SEQUENCE - length + 1) {
                val run = start until start + length
                if (run.any { counts[it] < 3 }) continue
                if (singles) found += Pattern(PatternType.PLANE_SINGLES, start, length)
                if (pairs) {
                    val rest = counts.copyOf()
                    run.forEach { rest[it] -= 3 }
                    if (DdzValue.ALL.all { rest[it] % 2 == 0 }) found += Pattern(PatternType.PLANE_PAIRS, start, length)
                }
            }
        }
    }

    /** The reading the table should record for a legal play, or null when it is not allowed. */
    fun readingFor(cards: Collection<PlayingCard>, toBeat: Pattern?, rules: DdzRules): Pattern? {
        val readings = classify(cards, rules)
        return if (toBeat == null) readings.firstOrNull() else readings.firstOrNull { it.beats(toBeat) }
    }

    /**
     * Plays from [hand] that beat [toBeat], or every kind of lead when it is null.
     * There is one play per reading; kickers are the cheapest cards available, so
     * this is good for hints and simple bots but does not list every kicker choice.
     */
    fun options(hand: List<PlayingCard>, toBeat: Pattern?, rules: DdzRules): List<Play> {
        val counts = DdzCards.counts(hand)
        val result = ArrayList<Play>()
        fun add(pattern: Pattern, values: List<Int>) {
            if (toBeat != null && !pattern.beats(toBeat)) return
            result += Play(take(hand, values), pattern)
        }

        val wantedType = toBeat?.type?.takeUnless { it == PatternType.BOMB || it == PatternType.ROCKET }
        fun wants(type: PatternType) = toBeat == null || wantedType == type

        for (v in DdzValue.ALL) {
            val c = counts[v]
            if (c >= 1 && wants(PatternType.SINGLE)) add(Pattern(PatternType.SINGLE, v), listOf(v))
            if (c >= 2 && wants(PatternType.PAIR)) add(Pattern(PatternType.PAIR, v), listOf(v, v))
            if (c >= 3) {
                val trio = listOf(v, v, v)
                if (wants(PatternType.TRIO)) add(Pattern(PatternType.TRIO, v), trio)
                if (wants(PatternType.TRIO_SINGLE) && rules.trioSingles) {
                    kickers(counts, setOf(v), 1, false)?.let { add(Pattern(PatternType.TRIO_SINGLE, v), trio + it) }
                }
                if (wants(PatternType.TRIO_PAIR)) {
                    kickers(counts, setOf(v), 1, true)?.let { add(Pattern(PatternType.TRIO_PAIR, v), trio + it) }
                }
            }
            if (c == 4 && rules.allowFourWithTwo) {
                val four = listOf(v, v, v, v)
                if (wants(PatternType.FOUR_TWO_SINGLES)) {
                    kickers(counts, setOf(v), 2, false)?.let { add(Pattern(PatternType.FOUR_TWO_SINGLES, v), four + it) }
                }
                if (wants(PatternType.FOUR_TWO_PAIRS)) {
                    kickers(counts, setOf(v), 2, true)?.let { add(Pattern(PatternType.FOUR_TWO_PAIRS, v), four + it) }
                }
            }
        }

        addSequences(counts, 1, 5, PatternType.STRAIGHT, toBeat, ::wants, ::add)
        addSequences(counts, 2, 3, PatternType.PAIR_STRAIGHT, toBeat, ::wants, ::add)
        addSequences(counts, 3, 2, PatternType.PLANE, toBeat, ::wants, ::add)
        for (length in 2..6) {
            for (start in DdzValue.THREE..DdzValue.HIGHEST_IN_SEQUENCE - length + 1) {
                val run = (start until start + length).toList()
                if (run.any { counts[it] < 3 }) continue
                val body = run.flatMap { listOf(it, it, it) }
                if (wants(PatternType.PLANE_SINGLES) && rules.trioSingles && hand.size >= 4 * length) {
                    kickers(counts, run.toSet(), length, false)?.let {
                        add(Pattern(PatternType.PLANE_SINGLES, start, length), body + it)
                    }
                }
                if (wants(PatternType.PLANE_PAIRS) && hand.size >= 5 * length) {
                    kickers(counts, run.toSet(), length, true)?.let {
                        add(Pattern(PatternType.PLANE_PAIRS, start, length), body + it)
                    }
                }
            }
        }

        // With two decks a value may be held five to eight times: each size is its own bomb.
        for (v in DdzValue.ALL) for (n in 4..counts[v]) add(Pattern(PatternType.BOMB, v, n), List(n) { v })
        val jokers = rules.decks
        if (counts[DdzValue.BLACK_JOKER] == jokers && counts[DdzValue.RED_JOKER] == jokers) {
            add(Pattern(PatternType.ROCKET, DdzValue.RED_JOKER, 2 * jokers), List(jokers) { DdzValue.BLACK_JOKER } + List(jokers) { DdzValue.RED_JOKER })
        }
        return result
    }

    private inline fun addSequences(
        counts: IntArray,
        width: Int,
        minLength: Int,
        type: PatternType,
        toBeat: Pattern?,
        wants: (PatternType) -> Boolean,
        add: (Pattern, List<Int>) -> Unit,
    ) {
        if (!wants(type)) return
        val lengths = if (toBeat != null) listOf(toBeat.length) else (minLength..12).toList()
        for (length in lengths) {
            for (start in DdzValue.THREE..DdzValue.HIGHEST_IN_SEQUENCE - length + 1) {
                val run = start until start + length
                if (run.all { counts[it] >= width }) add(Pattern(type, start, length), run.flatMap { v -> List(width) { v } })
            }
        }
    }

    /**
     * Picks [groups] kicker singles or pairs, never from [exclude], preferring
     * values that are loose in the hand so pairs, trios and bombs stay whole.
     */
    private fun kickers(counts: IntArray, exclude: Set<Int>, groups: Int, pairs: Boolean): List<Int>? {
        val width = if (pairs) 2 else 1
        val candidates = DdzValue.ALL
            .filter { it !in exclude && counts[it] >= width }
            .sortedWith(compareBy<Int> { counts[it] }.thenBy { it })
        if (pairs) {
            if (candidates.size < groups) return null
            return candidates.take(groups).flatMap { listOf(it, it) }
        }
        // Singles may repeat a value, but a rocket split into kickers would waste it, so jokers go last.
        val pool = candidates.flatMap { v -> List(minOf(counts[v], 2)) { v } }
            .sortedWith(compareBy<Int> { it >= DdzValue.BLACK_JOKER }.thenBy { counts[it] }.thenBy { it })
        if (pool.size < groups) return null
        return pool.take(groups)
    }

    /** Takes actual cards of the requested values from the hand, lowest suits first. */
    fun take(hand: List<PlayingCard>, values: List<Int>): List<PlayingCard> {
        val pool = hand.sortedWith(DDZ_ORDER).toMutableList()
        return values.map { value ->
            val index = pool.indexOfFirst { it.ddzValue == value }
            require(index >= 0) { "Hand has no card of value $value" }
            pool.removeAt(index)
        }
    }
}
