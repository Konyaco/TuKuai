package io.github.konyaco.tukuai.engine.mahjong

/**
 * The kinds of mahjong tile. The three numbered suits run from 1 to 9 and make runs;
 * winds and dragons are honours, which only make pairs, pungs and kongs. Flowers never
 * join a hand: whoever draws one sets it aside and draws again. Rule sets pick the suits
 * they play with: Sichuan mahjong has the numbered suits only.
 */
enum class TileSuit(val ranks: Int, val numbered: Boolean, val letter: Char) {
    CHARACTERS(9, true, 'm'),
    DOTS(9, true, 'p'),
    BAMBOO(9, true, 's'),
    WINDS(4, false, 'w'),
    DRAGONS(3, false, 'd'),

    /** The four seasons and the four flowers, one of each. */
    FLOWERS(8, false, 'f');

    /** The [Tile.id] of this suit's first tile. */
    val first: Int get() = entries.subList(0, ordinal).sumOf { it.ranks }

    val tiles: List<Tile> get() = (1..ranks).map { Tile.of(this, it) }

    companion object {
        val NUMBERED: List<TileSuit> = entries.filter { it.numbered }

        /** Every suit a hand can hold: the numbered ones and the honours. */
        val HAND: List<TileSuit> = entries.filter { it != FLOWERS }
    }
}

/**
 * One kind of tile, such as the five of dots; a set holds four of each. Its [id] runs from
 * 0 to [KINDS] - 1 in suit order, which is also the order hands are sorted in.
 *
 * Some rule sets paint one five of each numbered suit red, and it counts for more. A [red]
 * five is the same kind as the others, with the same [id], so it makes the same sets; it
 * only differs from them in [equals], so a hand knows which of its fives is the red one.
 */
class Tile private constructor(val suit: TileSuit, val rank: Int, val red: Boolean) : Comparable<Tile> {
    val id: Int = suit.first + rank - 1

    /** The next tile up in a numbered suit, for runs; null at the nine and for honours. */
    val next: Tile? get() = if (suit.numbered && rank < suit.ranks) of(suit, rank + 1) else null

    val isHonour: Boolean get() = suit == TileSuit.WINDS || suit == TileSuit.DRAGONS

    /** A one or a nine of a numbered suit. */
    val isTerminal: Boolean get() = suit.numbered && (rank == 1 || rank == suit.ranks)

    /** A terminal or an honour (幺九牌). */
    val isTerminalOrHonour: Boolean get() = isHonour || isTerminal

    /** The same kind, not red. */
    val plain: Tile get() = if (red) of(id) else this

    override fun compareTo(other: Tile): Int = if (id != other.id) id.compareTo(other.id) else red.compareTo(other.red)

    override fun equals(other: Any?): Boolean = other is Tile && other.id == id && other.red == red

    override fun hashCode(): Int = id * 2 + if (red) 1 else 0

    /** Short notation, such as `5p` for the five of dots and `0p` for the red one; see [Tiles.parse]. */
    override fun toString(): String = "${if (red) 0 else rank}${suit.letter}"

    /** A number that tells red fives apart, for sending tiles; see [fromCode]. */
    val code: Int get() = if (red) id + RED_CODE else id

    companion object {
        val KINDS: Int = TileSuit.entries.sumOf { it.ranks }

        private val ALL: List<Tile> = TileSuit.entries.flatMap { suit -> (1..suit.ranks).map { Tile(suit, it, false) } }

        private val REDS: Map<TileSuit, Tile> = TileSuit.NUMBERED.associateWith { Tile(it, 5, true) }

        private const val RED_CODE = 100

        fun of(suit: TileSuit, rank: Int): Tile {
            require(rank in 1..suit.ranks) { "No $rank in $suit" }
            return ALL[suit.first + rank - 1]
        }

        fun of(id: Int): Tile = ALL[id]

        /** The red five of a numbered [suit]. */
        fun red(suit: TileSuit): Tile = REDS.getValue(suit)

        /** The tile a [code] stands for, or null when it stands for none. */
        fun fromCode(code: Int): Tile? = when (code) {
            in 0 until KINDS -> ALL[code]
            in RED_CODE until RED_CODE + KINDS -> ALL[code - RED_CODE].takeIf { it.suit.numbered && it.rank == 5 }?.let { REDS.getValue(it.suit) }
            else -> null
        }
    }
}

object Tiles {
    /** Four of every tile of [suits], in order. */
    fun set(suits: List<TileSuit>, copies: Int = 4): List<Tile> = suits.flatMap { suit -> suit.tiles.flatMap { tile -> List(copies) { tile } } }

    /** How many of each tile kind [tiles] holds, indexed by [Tile.id]. */
    fun counts(tiles: Iterable<Tile>): IntArray = IntArray(Tile.KINDS).also { c -> tiles.forEach { c[it.id]++ } }

    /** The tiles a count array holds, in order. */
    fun list(counts: IntArray): List<Tile> = counts.indices.flatMap { id -> List(counts[id]) { Tile.of(id) } }

    /**
     * Reads tiles written the short way, digits followed by their suit's letter, such as
     * `123m 55p 789s`: m for characters, p for dots, s for bamboo, w for winds (east, south,
     * west, north), d for dragons (red, green, white) and f for flowers. A 0 is a red five.
     */
    fun parse(text: String): List<Tile> {
        val out = ArrayList<Tile>()
        val digits = ArrayList<Int>()
        for (ch in text) {
            when {
                ch.isDigit() -> digits += ch.digitToInt()
                ch.isWhitespace() -> require(digits.isEmpty()) { "Digits without a suit in '$text'" }
                else -> {
                    val suit = TileSuit.entries.firstOrNull { it.letter == ch } ?: throw IllegalArgumentException("Unknown suit '$ch' in '$text'")
                    digits.mapTo(out) { if (it == 0) Tile.red(suit) else Tile.of(suit, it) }
                    digits.clear()
                }
            }
        }
        require(digits.isEmpty()) { "Digits without a suit in '$text'" }
        return out
    }

    /** Four of every tile of [suits], with one five of each numbered suit red when [reds]. */
    fun set(suits: List<TileSuit>, reds: Boolean): List<Tile> {
        val tiles = set(suits).toMutableList()
        if (reds) for (suit in suits.filter { it.numbered }) tiles[tiles.indexOf(Tile.of(suit, 5))] = Tile.red(suit)
        return tiles
    }

    /** [tiles] less one of each of [removed]; throws when a tile is not there. */
    fun minus(tiles: List<Tile>, removed: List<Tile>): List<Tile> {
        val left = tiles.toMutableList()
        for (tile in removed) require(left.remove(tile)) { "$tile is not in $tiles" }
        return left
    }

    /**
     * [tiles] less [count] tiles of [tile]'s kind, red or not, taking the plain ones first
     * unless [redFirst]; throws when there are not that many.
     */
    fun minusKind(tiles: List<Tile>, tile: Tile, count: Int, redFirst: Boolean = false): List<Tile> {
        val left = tiles.toMutableList()
        repeat(count) {
            val same = left.filter { it.id == tile.id }
            require(same.isNotEmpty()) { "Not $count of $tile in $tiles" }
            left.remove(if (redFirst) same.maxBy { if (it.red) 1 else 0 } else same.minBy { if (it.red) 1 else 0 })
        }
        return left
    }

    /** How many tiles of [tile]'s kind [tiles] holds, red or not. */
    fun countKind(tiles: List<Tile>, tile: Tile): Int = tiles.count { it.id == tile.id }
}
