package io.github.konyaco.tukuai.engine.uno

import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

enum class UnoColor { RED, YELLOW, GREEN, BLUE }

enum class UnoKind { NUMBER, SKIP, REVERSE, DRAW_TWO, WILD, WILD_DRAW_FOUR }

/**
 * What is printed on a card. Wild cards have no colour of their own; the player who
 * plays one names the colour to follow.
 *
 * @param number 0..9 for [UnoKind.NUMBER] cards, otherwise -1
 */
data class UnoFace(val color: UnoColor?, val kind: UnoKind, val number: Int = -1) {
    val isWild: Boolean get() = color == null

    /** What the card costs the player left holding it at the end. */
    val points: Int
        get() = when (kind) {
            UnoKind.NUMBER -> number
            UnoKind.SKIP, UnoKind.REVERSE, UnoKind.DRAW_TWO -> 20
            UnoKind.WILD, UnoKind.WILD_DRAW_FOUR -> 50
        }

    /** Cards the next player has to draw because of this card. */
    val drawPenalty: Int
        get() = when (kind) {
            UnoKind.DRAW_TWO -> 2
            UnoKind.WILD_DRAW_FOUR -> 4
            else -> 0
        }

    /** Short text such as `R5`, `GS`, `B+2` or `W+4`, handy in logs and tests. */
    override fun toString(): String {
        val c = color?.name?.first()?.toString() ?: "W"
        return c + when (kind) {
            UnoKind.NUMBER -> number.toString()
            UnoKind.SKIP -> "S"
            UnoKind.REVERSE -> "R"
            UnoKind.DRAW_TWO -> "+2"
            UnoKind.WILD -> ""
            UnoKind.WILD_DRAW_FOUR -> "+4"
        }
    }
}

/**
 * One physical card of the 108-card deck, numbered 0..107. Two cards can show the same
 * face, such as the two red fives; the number tells them apart, so a card can be followed
 * as it moves from a hand to the discard pile.
 */
@JvmInline
value class UnoCard(val id: Int) {
    val face: UnoFace get() = UnoDeck.faceOf(this)

    override fun toString(): String = face.toString()
}

object UnoDeck {
    /**
     * The 54 different faces: for each colour 0 to 9, skip, reverse and draw two, then the
     * wild and the wild draw four. Clients use these indexes for the card art.
     */
    val FACES: List<UnoFace> =
        UnoColor.entries.flatMap { color ->
            (0..9).map { UnoFace(color, UnoKind.NUMBER, it) } +
                listOf(UnoKind.SKIP, UnoKind.REVERSE, UnoKind.DRAW_TWO).map { UnoFace(color, it) }
        } + listOf(UnoFace(null, UnoKind.WILD), UnoFace(null, UnoKind.WILD_DRAW_FOUR))

    private val faceIndex: Map<UnoFace, Int> = FACES.withIndex().associate { it.value to it.index }

    /**
     * Face index of each card: in each colour one 0 and two of everything else, and four
     * of each wild.
     */
    private val CARD_FACES: IntArray = run {
        val faces = ArrayList<Int>(SIZE)
        for (face in FACES) {
            val copies = when {
                face.isWild -> 4
                face.kind == UnoKind.NUMBER && face.number == 0 -> 1
                else -> 2
            }
            repeat(copies) { faces += faceIndex.getValue(face) }
        }
        faces.toIntArray()
    }

    const val SIZE = 108

    val ALL: List<UnoCard> = List(SIZE) { UnoCard(it) }

    fun faceOf(card: UnoCard): UnoFace = FACES[faceIndexOf(card)]

    fun faceIndexOf(card: UnoCard): Int =
        CARD_FACES.getOrNull(card.id) ?: throw IllegalArgumentException("No card ${card.id}")

    fun indexOf(face: UnoFace): Int = faceIndex.getValue(face)

    fun write(writer: WireWriter, cards: List<UnoCard>) {
        writer.ints(cards.map { it.id })
    }

    fun read(reader: WireReader): List<UnoCard> = reader.ints().map { id ->
        require(id in 0 until SIZE) { "No card $id" }
        UnoCard(id)
    }
}
