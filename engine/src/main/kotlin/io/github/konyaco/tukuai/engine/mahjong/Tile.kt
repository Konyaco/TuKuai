package io.github.konyaco.tukuai.engine.mahjong

/**
 * The kinds of mahjong tile. The three numbered suits run from 1 to 9 and make runs;
 * winds and dragons are honours, which only make pairs, pungs and kongs. Rule sets pick
 * the suits they play with: Sichuan mahjong has the numbered suits only.
 */
enum class TileSuit(val ranks: Int, val numbered: Boolean, val letter: Char) {
    CHARACTERS(9, true, 'm'),
    DOTS(9, true, 'p'),
    BAMBOO(9, true, 's'),
    WINDS(4, false, 'w'),
    DRAGONS(3, false, 'd');

    /** The [Tile.id] of this suit's first tile. */
    val first: Int get() = entries.subList(0, ordinal).sumOf { it.ranks }

    val tiles: List<Tile> get() = (1..ranks).map { Tile.of(this, it) }

    companion object {
        val NUMBERED: List<TileSuit> = entries.filter { it.numbered }
    }
}

/**
 * One kind of tile, such as the five of dots; a set holds four of each. Its [id] runs from
 * 0 to [KINDS] - 1 in suit order, which is also the order hands are sorted in.
 */
class Tile private constructor(val suit: TileSuit, val rank: Int) : Comparable<Tile> {
    val id: Int = suit.first + rank - 1

    /** The next tile up in a numbered suit, for runs; null at the nine and for honours. */
    val next: Tile? get() = if (suit.numbered && rank < suit.ranks) of(suit, rank + 1) else null

    override fun compareTo(other: Tile): Int = id.compareTo(other.id)

    override fun equals(other: Any?): Boolean = other is Tile && other.id == id

    override fun hashCode(): Int = id

    /** Short notation, such as `5p` for the five of dots; see [Tiles.parse]. */
    override fun toString(): String = "$rank${suit.letter}"

    companion object {
        val KINDS: Int = TileSuit.entries.sumOf { it.ranks }

        private val ALL: List<Tile> = TileSuit.entries.flatMap { suit -> (1..suit.ranks).map { Tile(suit, it) } }

        fun of(suit: TileSuit, rank: Int): Tile {
            require(rank in 1..suit.ranks) { "No $rank in $suit" }
            return ALL[suit.first + rank - 1]
        }

        fun of(id: Int): Tile = ALL[id]
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
     * `123m 55p 789s`: m for characters, p for dots, s for bamboo, w for winds and d for dragons.
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
                    digits.mapTo(out) { Tile.of(suit, it) }
                    digits.clear()
                }
            }
        }
        require(digits.isEmpty()) { "Digits without a suit in '$text'" }
        return out
    }

    /** [tiles] less one of each of [removed]; throws when a tile is not there. */
    fun minus(tiles: List<Tile>, removed: List<Tile>): List<Tile> {
        val left = tiles.toMutableList()
        for (tile in removed) require(left.remove(tile)) { "$tile is not in $tiles" }
        return left
    }
}
