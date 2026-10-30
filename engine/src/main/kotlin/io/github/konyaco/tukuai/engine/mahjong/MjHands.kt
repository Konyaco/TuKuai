package io.github.konyaco.tukuai.engine.mahjong

/**
 * Reading a hand: whether it is complete, the ways it splits into sets and a pair, how far
 * it is from complete (shanten) and which tiles would complete it. Hands are given as tile
 * counts indexed by [Tile.id]; [sets] is how many sets the concealed tiles must still make,
 * four less one for each meld already laid down.
 */
object MjHands {
    /** A set within the hand: a run of three from [tile] up, or three of [tile]. */
    data class Group(val run: Boolean, val tile: Tile)

    /** One way a complete hand splits up. */
    data class Shape(val pair: Tile, val groups: List<Group>)

    private val K = Tile.KINDS

    /** Whether the tile with id [i] starts a run with the next two. */
    private fun runFrom(i: Int): Boolean {
        val tile = Tile.of(i)
        return tile.suit.numbered && tile.rank <= tile.suit.ranks - 2
    }

    private fun nextInSuit(i: Int, gap: Int): Boolean {
        val tile = Tile.of(i)
        return tile.suit.numbered && tile.rank + gap <= tile.suit.ranks
    }

    /** Whether [counts] is [sets] sets and a pair. */
    fun isStandard(counts: IntArray, sets: Int): Boolean {
        if (counts.sum() != sets * 3 + 2) return false
        val c = counts.copyOf()
        for (i in 0 until K) {
            if (c[i] < 2) continue
            c[i] -= 2
            val ok = groupsOnly(c, 0)
            c[i] += 2
            if (ok) return true
        }
        return false
    }

    private fun groupsOnly(c: IntArray, from: Int): Boolean {
        var i = from
        while (i < K && c[i] == 0) i++
        if (i == K) return true
        if (c[i] >= 3) {
            c[i] -= 3
            val ok = groupsOnly(c, i)
            c[i] += 3
            if (ok) return true
        }
        if (runFrom(i) && c[i + 1] > 0 && c[i + 2] > 0) {
            c[i]--; c[i + 1]--; c[i + 2]--
            val ok = groupsOnly(c, i)
            c[i]++; c[i + 1]++; c[i + 2]++
            if (ok) return true
        }
        return false
    }

    /**
     * Seven pairs: fourteen tiles in pairs. When [fourAsTwoPairs], as in Sichuan mahjong,
     * four of a kind count as two pairs; otherwise the seven must all differ.
     */
    fun isSevenPairs(counts: IntArray, fourAsTwoPairs: Boolean): Boolean =
        counts.sum() == 14 && counts.all { it % 2 == 0 && (fourAsTwoPairs || it <= 2) }

    fun isWin(counts: IntArray, sets: Int, sevenPairs: Boolean, fourAsTwoPairs: Boolean): Boolean =
        isStandard(counts, sets) || (sevenPairs && sets == 4 && isSevenPairs(counts, fourAsTwoPairs))

    /** Every way [counts] splits into [sets] sets and a pair. */
    fun shapes(counts: IntArray, sets: Int): List<Shape> {
        if (counts.sum() != sets * 3 + 2) return emptyList()
        val out = ArrayList<Shape>()
        val c = counts.copyOf()
        for (i in 0 until K) {
            if (c[i] < 2) continue
            c[i] -= 2
            collect(c, 0, ArrayList()) { out += Shape(Tile.of(i), it) }
            c[i] += 2
        }
        return out.distinct()
    }

    private fun collect(c: IntArray, from: Int, groups: ArrayList<Group>, found: (List<Group>) -> Unit) {
        var i = from
        while (i < K && c[i] == 0) i++
        if (i == K) return found(groups.toList())
        if (c[i] >= 3) {
            c[i] -= 3
            groups += Group(false, Tile.of(i))
            collect(c, i, groups, found)
            groups.removeAt(groups.size - 1)
            c[i] += 3
        }
        if (runFrom(i) && c[i + 1] > 0 && c[i + 2] > 0) {
            c[i]--; c[i + 1]--; c[i + 2]--
            groups += Group(true, Tile.of(i))
            collect(c, i, groups, found)
            groups.removeAt(groups.size - 1)
            c[i]++; c[i + 1]++; c[i + 2]++
        }
    }

    /**
     * How many tiles away from ready a hand of sets and a pair is: -1 when complete, 0 when
     * one tile completes it. Counts sets, partial sets (pairs and runs missing a tile) and a
     * pair, the usual way.
     */
    fun standardShanten(counts: IntArray, sets: Int): Int {
        val c = counts.copyOf()
        var best = search(c, 0, sets, 0, 0, false)
        for (i in 0 until K) {
            if (c[i] < 2) continue
            c[i] -= 2
            best = minOf(best, search(c, 0, sets, 0, 0, true))
            c[i] += 2
        }
        return best
    }

    private fun search(c: IntArray, from: Int, n: Int, sets: Int, partials: Int, pair: Boolean): Int {
        var i = from
        while (i < K && c[i] == 0) i++
        if (i == K) {
            val s = minOf(sets, n)
            val p = minOf(partials, n - s)
            return 2 * n - 2 * s - p - (if (pair) 1 else 0)
        }
        var best = Int.MAX_VALUE
        if (c[i] >= 3) {
            c[i] -= 3
            best = minOf(best, search(c, i, n, sets + 1, partials, pair))
            c[i] += 3
        }
        if (runFrom(i) && c[i + 1] > 0 && c[i + 2] > 0) {
            c[i]--; c[i + 1]--; c[i + 2]--
            best = minOf(best, search(c, i, n, sets + 1, partials, pair))
            c[i]++; c[i + 1]++; c[i + 2]++
        }
        if (sets + partials < n) {
            if (c[i] >= 2) {
                c[i] -= 2
                best = minOf(best, search(c, i, n, sets, partials + 1, pair))
                c[i] += 2
            }
            if (nextInSuit(i, 1) && c[i + 1] > 0) {
                c[i]--; c[i + 1]--
                best = minOf(best, search(c, i, n, sets, partials + 1, pair))
                c[i]++; c[i + 1]++
            }
            if (nextInSuit(i, 2) && c[i + 2] > 0) {
                c[i]--; c[i + 2]--
                best = minOf(best, search(c, i, n, sets, partials + 1, pair))
                c[i]++; c[i + 2]++
            }
        }
        // Whatever is left of this tile stays loose.
        val held = c[i]
        c[i] = 0
        best = minOf(best, search(c, i + 1, n, sets, partials, pair))
        c[i] = held
        return best
    }

    /** Shanten towards seven pairs; see [isSevenPairs] for [fourAsTwoPairs]. */
    fun sevenPairsShanten(counts: IntArray, fourAsTwoPairs: Boolean): Int {
        if (fourAsTwoPairs) return 6 - minOf(7, counts.sumOf { it / 2 })
        val pairs = counts.count { it >= 2 }
        val kinds = counts.count { it > 0 }
        return 6 - pairs + maxOf(0, 7 - kinds)
    }

    /** The lower of the two shantens, seven pairs only when allowed and nothing is melded. */
    fun shanten(counts: IntArray, sets: Int, sevenPairs: Boolean, fourAsTwoPairs: Boolean): Int {
        val standard = standardShanten(counts, sets)
        return if (sevenPairs && sets == 4) minOf(standard, sevenPairsShanten(counts, fourAsTwoPairs)) else standard
    }

    /**
     * The tiles that would complete a hand one short of complete, among those [allowed]. A
     * tile the hand already holds all four of is left out, since it cannot come.
     */
    fun waits(
        counts: IntArray,
        sets: Int,
        sevenPairs: Boolean,
        fourAsTwoPairs: Boolean,
        allowed: (Tile) -> Boolean = { true },
    ): List<Tile> {
        val c = counts.copyOf()
        val out = ArrayList<Tile>()
        for (i in 0 until K) {
            if (c[i] >= 4 || !allowed(Tile.of(i))) continue
            c[i]++
            if (isWin(c, sets, sevenPairs, fourAsTwoPairs)) out += Tile.of(i)
            c[i]--
        }
        return out
    }
}
