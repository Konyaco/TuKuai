package io.github.konyaco.tukuai.engine.paodekuai

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.doudizhu.DDZ_ORDER
import io.github.konyaco.tukuai.engine.doudizhu.DdzCards
import io.github.konyaco.tukuai.engine.doudizhu.DdzValue
import io.github.konyaco.tukuai.engine.doudizhu.ddzValue

/**
 * The shapes a Pao De Kuai play can take. Cards rank as in Dou Dizhu, 3 lowest and 2
 * highest, so the values come from [DdzValue]; there are no jokers.
 */
enum class PdkPatternType {
    SINGLE,
    PAIR,

    /** Two or more pairs in a row, such as 5 5 6 6. */
    PAIR_RUN,

    /** Five or more singles in a row, no 2. */
    STRAIGHT,

    /** Three of a kind with any two cards. With the player's last cards, the two may be fewer. */
    TRIO_TWO,

    /** Two or more threes of a kind in a row, each with two cards. With the last cards, the cards may be fewer. */
    PLANE,

    /** Four of a kind with any three cards; not a bomb. */
    FOUR_THREE,
    BOMB,
}

/**
 * One reading of a play.
 *
 * @param main the value that decides who wins: the single, the three, or the lowest step of a run
 * @param length how many steps a run has; 1 for everything else
 */
data class PdkPattern(val type: PdkPatternType, val main: Int, val length: Int = 1) {
    val isBomb: Boolean get() = type == PdkPatternType.BOMB

    /** True when this play may be laid on top of [other]. */
    fun beats(other: PdkPattern): Boolean = when {
        isBomb && other.isBomb -> main > other.main
        isBomb -> true
        other.isBomb -> false
        else -> type == other.type && length == other.length && main > other.main
    }
}

data class PdkPlay(val cards: List<PlayingCard>, val pattern: PdkPattern)

object PdkPatterns {
    private val VALUES: IntRange = DdzValue.THREE..DdzValue.TWO

    /**
     * Every way the cards can be read, bombs and longer runs first. [lastCards] says the
     * cards are all the player has left, which lets a three or a plane go out short of cards.
     */
    fun classify(cards: Collection<PlayingCard>, rules: PdkRules, lastCards: Boolean): List<PdkPattern> {
        if (cards.isEmpty()) return emptyList()
        return classifyCounts(DdzCards.counts(cards), cards.size, rules, lastCards)
    }

    fun classifyCounts(counts: IntArray, total: Int, rules: PdkRules, lastCards: Boolean): List<PdkPattern> {
        val found = LinkedHashSet<PdkPattern>()
        val present = VALUES.filter { counts[it] > 0 }

        if (present.size == 1) {
            val value = present[0]
            when (counts[value]) {
                1 -> found += PdkPattern(PdkPatternType.SINGLE, value)
                2 -> found += PdkPattern(PdkPatternType.PAIR, value)
                4 -> found += PdkPattern(PdkPatternType.BOMB, value)
            }
        }
        // Three with two, or with fewer as the last cards. Five cards can only hold one three.
        if (total == 5 || (lastCards && total in 3..4)) {
            present.filter { counts[it] >= 3 }.forEach { found += PdkPattern(PdkPatternType.TRIO_TWO, it) }
        }
        if (rules.fourWithThree && total == 7) {
            present.filter { counts[it] == 4 }.forEach { found += PdkPattern(PdkPatternType.FOUR_THREE, it) }
        }
        run(present, counts, 1, 5)?.let { found += PdkPattern(PdkPatternType.STRAIGHT, it.first, it.second) }
        run(present, counts, 2, 2)?.let { found += PdkPattern(PdkPatternType.PAIR_RUN, it.first, it.second) }
        for (length in 2..total / 3) {
            val full = total == 5 * length
            val short = lastCards && total >= 3 * length && total < 5 * length
            if (!full && !short) continue
            for (start in DdzValue.THREE..DdzValue.HIGHEST_IN_SEQUENCE - length + 1) {
                if ((start until start + length).all { counts[it] >= 3 }) found += PdkPattern(PdkPatternType.PLANE, start, length)
            }
        }
        return found.sortedWith(compareByDescending<PdkPattern> { it.isBomb }.thenByDescending { it.length }.thenByDescending { it.main })
    }

    /** When every present value has exactly [width] cards and they run in a row, returns (lowest, length). */
    private fun run(present: List<Int>, counts: IntArray, width: Int, minLength: Int): Pair<Int, Int>? {
        if (present.size < minLength) return null
        if (present.any { counts[it] != width || it > DdzValue.HIGHEST_IN_SEQUENCE }) return null
        if (present.last() - present.first() != present.size - 1) return null
        return present.first() to present.size
    }

    /** The reading the table should record for a play, or null when the cards make no play that may go on [toBeat]. */
    fun readingFor(cards: Collection<PlayingCard>, toBeat: PdkPattern?, rules: PdkRules, lastCards: Boolean): PdkPattern? {
        val readings = classify(cards, rules, lastCards)
        return if (toBeat == null) readings.firstOrNull() else readings.firstOrNull { it.beats(toBeat) }
    }

    /**
     * Plays from [hand] that beat [toBeat], or every kind of lead when it is null; with
     * [mustInclude], only plays with that card. There is one play per reading, with the
     * loosest low cards as the extra cards, so this is for hints and computers, but it never
     * misses a reading: when it returns nothing, nothing in the hand can be played.
     */
    fun options(hand: List<PlayingCard>, toBeat: PdkPattern?, rules: PdkRules, mustInclude: PlayingCard? = null): List<PdkPlay> {
        val counts = DdzCards.counts(hand)
        val result = ArrayList<PdkPlay>()
        val wanted = toBeat?.type?.takeUnless { it == PdkPatternType.BOMB }
        fun wants(type: PdkPatternType) = toBeat == null || wanted == type

        fun add(pattern: PdkPattern, body: List<Int>, extra: Int = 0) {
            if (toBeat != null && !pattern.beats(toBeat)) return
            if (body.size + extra > hand.size) return
            val cards = take(hand, body, mustInclude)
            result += PdkPlay(cards + extraCards(hand - cards.toSet(), counts, body.toSet(), extra, mustInclude), pattern)
        }

        for (v in VALUES) {
            val c = counts[v]
            if (c >= 1 && wants(PdkPatternType.SINGLE)) add(PdkPattern(PdkPatternType.SINGLE, v), listOf(v))
            if (c >= 2 && wants(PdkPatternType.PAIR)) add(PdkPattern(PdkPatternType.PAIR, v), listOf(v, v))
            if (c >= 3 && wants(PdkPatternType.TRIO_TWO)) add(PdkPattern(PdkPatternType.TRIO_TWO, v), listOf(v, v, v), 2)
            if (c == 4 && rules.fourWithThree && wants(PdkPatternType.FOUR_THREE)) {
                add(PdkPattern(PdkPatternType.FOUR_THREE, v), listOf(v, v, v, v), 3)
            }
        }
        runs(counts, 1, 5, PdkPatternType.STRAIGHT, toBeat, ::wants) { p, values -> add(p, values) }
        runs(counts, 2, 2, PdkPatternType.PAIR_RUN, toBeat, ::wants) { p, values -> add(p, values) }
        runs(counts, 3, 2, PdkPatternType.PLANE, toBeat, ::wants) { p, values -> add(p, values, 2 * p.length) }
        for (v in VALUES) if (counts[v] == 4) add(PdkPattern(PdkPatternType.BOMB, v), listOf(v, v, v, v))

        // Going out with everything left, which may be a three or a plane short of cards.
        if (mustInclude == null || mustInclude in hand) {
            readingFor(hand, toBeat, rules, lastCards = true)?.let { reading ->
                if (result.none { it.cards.size == hand.size }) result += PdkPlay(hand.sortedWith(DDZ_ORDER), reading)
            }
        }
        return if (mustInclude == null) result else result.filter { mustInclude in it.cards }
    }

    private inline fun runs(
        counts: IntArray,
        width: Int,
        minLength: Int,
        type: PdkPatternType,
        toBeat: PdkPattern?,
        wants: (PdkPatternType) -> Boolean,
        add: (PdkPattern, List<Int>) -> Unit,
    ) {
        if (!wants(type)) return
        val lengths = if (toBeat != null) listOf(toBeat.length) else (minLength..12).toList()
        for (length in lengths) {
            for (start in DdzValue.THREE..DdzValue.HIGHEST_IN_SEQUENCE - length + 1) {
                val run = start until start + length
                if (run.all { counts[it] >= width }) add(PdkPattern(type, start, length), run.flatMap { v -> List(width) { v } })
            }
        }
    }

    /**
     * [count] cards to go with a three, plane or four: [mustInclude] if it is there, then low
     * cards that stand alone, then pairs, keeping 2s, aces, bombs and the play's own values for last.
     */
    private fun extraCards(rest: List<PlayingCard>, counts: IntArray, body: Set<Int>, count: Int, mustInclude: PlayingCard?): List<PlayingCard> {
        if (count == 0) return emptyList()
        return rest.sortedWith(
            compareBy<PlayingCard> { it != mustInclude }
                .thenBy { it.ddzValue in body }
                .thenBy { counts[it.ddzValue] == 4 }
                .thenBy { it.ddzValue >= DdzValue.ACE }
                .thenBy { counts[it.ddzValue] }
                .thenBy { it.ddzValue }
                .thenBy { DdzCards.index(it) },
        ).take(count)
    }

    /** Takes actual cards of the requested values from the hand, [preferred] first, then the lowest suits. */
    fun take(hand: List<PlayingCard>, values: List<Int>, preferred: PlayingCard? = null): List<PlayingCard> {
        val pool = hand.sortedWith(compareBy<PlayingCard> { it != preferred }.then(DDZ_ORDER)).toMutableList()
        return values.map { value ->
            val index = pool.indexOfFirst { it.ddzValue == value }
            require(index >= 0) { "Hand has no card of value $value" }
            pool.removeAt(index)
        }
    }
}
