package io.github.konyaco.tukuai.engine.tutu

import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

enum class TutuColor { YELLOW, PURPLE, BLUE, GREEN }

enum class TutuKind {
    NUMBER,

    /** The next player is frozen and misses a turn. */
    SNOWBALL,

    /** Play turns the other way round the table, like a trident thrown with loyalty. */
    TRIDENT,

    /** The next player digs up two cards and misses a turn. */
    SHOVEL,

    /** The next player draws until a card of this colour or a wild card turns up, and misses a turn. */
    LIGHTNING,

    /** Wild: hatches into a random coloured card the moment it is played, which then takes effect. */
    EGG,

    /** Wild: lights a fuse of a hidden length; whoever's turn it blows on draws four. */
    TNT,

    /** Wild: plants a cactus at a random depth in the draw pile, in plain sight; whoever draws it takes three more. */
    CACTUS,
    ;

    /** Wild cards fit on anything and have no colour of their own. */
    val isWild: Boolean get() = this == EGG || this == TNT || this == CACTUS

    /** The player has to name the colour to follow; an egg takes the colour of what hatches. */
    val namesColor: Boolean get() = this == TNT || this == CACTUS
}

/**
 * What is printed on a card.
 *
 * @param number 1..9 for [TutuKind.NUMBER] cards, otherwise -1
 */
data class TutuFace(val color: TutuColor?, val kind: TutuKind, val number: Int = -1) {
    val isWild: Boolean get() = color == null

    /** Short text such as `Y5`, `PS` (snowball), `B+2` or `WT` (TNT), handy in logs and tests. */
    override fun toString(): String {
        val c = color?.name?.first()?.toString() ?: "W"
        return c + when (kind) {
            TutuKind.NUMBER -> number.toString()
            TutuKind.SNOWBALL -> "S"
            TutuKind.TRIDENT -> "R"
            TutuKind.SHOVEL -> "+2"
            TutuKind.LIGHTNING -> "L"
            TutuKind.EGG -> "E"
            TutuKind.TNT -> "T"
            TutuKind.CACTUS -> "C"
        }
    }
}

/**
 * One physical card, numbered from 0. The base deck is cards 0 until [TutuDeck.BASE_SIZE];
 * the party deck adds [TutuDeck.PARTY_EXTRA] more specials after them.
 */
@JvmInline
value class TutuCard(val id: Int) {
    val face: TutuFace get() = TutuDeck.faceOf(this)

    override fun toString(): String = face.toString()
}

object TutuDeck {
    /** Coloured kinds in the order of [FACES]. */
    val COLORED_KINDS = listOf(TutuKind.SNOWBALL, TutuKind.TRIDENT, TutuKind.SHOVEL, TutuKind.LIGHTNING)

    /**
     * The 55 different faces: for each colour 1 to 9 and the four coloured specials, then
     * the egg, TNT and cactus. Clients use these indexes for the card art.
     */
    val FACES: List<TutuFace> =
        TutuColor.entries.flatMap { color ->
            (1..9).map { TutuFace(color, TutuKind.NUMBER, it) } + COLORED_KINDS.map { TutuFace(color, it) }
        } + listOf(TutuKind.EGG, TutuKind.TNT, TutuKind.CACTUS).map { TutuFace(null, it) }

    /** The 52 faces an egg can hatch into: every coloured face, each as likely as the others. */
    val HATCHES: List<TutuFace> = FACES.filter { !it.isWild }

    private val faceIndex: Map<TutuFace, Int> = FACES.withIndex().associate { it.value to it.index }

    private fun copies(face: TutuFace, party: Boolean): Int = when (face.kind) {
        TutuKind.NUMBER, TutuKind.SNOWBALL, TutuKind.TRIDENT, TutuKind.SHOVEL -> if (party) 0 else 2
        TutuKind.LIGHTNING -> 1
        TutuKind.EGG, TutuKind.TNT, TutuKind.CACTUS -> if (party) 2 else 4
    }

    /** Face index of each card: the base deck, then the party extras. */
    private val CARD_FACES: IntArray = run {
        val faces = ArrayList<Int>()
        for (party in listOf(false, true)) {
            for (face in FACES) repeat(copies(face, party)) { faces += faceIndex.getValue(face) }
        }
        faces.toIntArray()
    }

    /** Two of each coloured number, snowball, trident and shovel, one lightning, and four of each wild. */
    const val BASE_SIZE = 112

    /** One more lightning in each colour and two more of each wild. */
    const val PARTY_EXTRA = 10

    const val SIZE = BASE_SIZE + PARTY_EXTRA

    val ALL: List<TutuCard> = List(SIZE) { TutuCard(it) }

    /** The cards a hand is played with. */
    fun cards(rules: TutuRules): List<TutuCard> = if (rules.party) ALL else ALL.subList(0, BASE_SIZE)

    fun faceOf(card: TutuCard): TutuFace = FACES[faceIndexOf(card)]

    fun faceIndexOf(card: TutuCard): Int =
        CARD_FACES.getOrNull(card.id) ?: throw IllegalArgumentException("No card ${card.id}")

    fun indexOf(face: TutuFace): Int = faceIndex.getValue(face)

    fun write(writer: WireWriter, cards: List<TutuCard>) {
        writer.ints(cards.map { it.id })
    }

    fun read(reader: WireReader): List<TutuCard> = reader.ints().map { id ->
        require(id in 0 until SIZE) { "No card $id" }
        TutuCard(id)
    }
}
