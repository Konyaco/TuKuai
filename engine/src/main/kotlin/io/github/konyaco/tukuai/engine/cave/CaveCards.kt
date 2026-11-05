package io.github.konyaco.tukuai.engine.cave

/** The ores of the cave, poorest first, with what each of their four cards is worth in emeralds. */
enum class Ore(val values: List<Int>) {
    COAL(listOf(2, 3, 4, 5)),
    IRON(listOf(6, 7, 8, 9)),
    GOLD(listOf(10, 11, 12, 13)),
    DIAMOND(listOf(14, 15, 16, 17)),
}

/** What can go wrong down there. The second card of one kind in a round ends it for everyone still inside. */
enum class Hazard { LAVA, CREEPER, GRAVEL, SPIDER }

sealed interface CaveCard {
    /** A vein of [ore] worth [value] emeralds, shared out among everyone inside when it turns up. */
    data class Vein(val ore: Ore, val value: Int) : CaveCard

    /** One of the [CaveDeck.COPIES] cards of [hazard]; [copy] tells them apart. */
    data class Danger(val hazard: Hazard, val copy: Int) : CaveCard
}

object CaveDeck {
    /** Cards of each hazard in a full deck. */
    const val COPIES = 3

    val VEINS: List<CaveCard.Vein> = Ore.entries.flatMap { ore -> ore.values.map { CaveCard.Vein(ore, it) } }

    val DANGERS: List<CaveCard.Danger> = Hazard.entries.flatMap { hazard -> (0 until COPIES).map { CaveCard.Danger(hazard, it) } }

    /** Every card once, veins first; a card's place here is its id on the wire and in the atlas. */
    val ALL: List<CaveCard> = VEINS + DANGERS

    private val ids: Map<CaveCard, Int> = ALL.withIndex().associate { it.value to it.index }

    fun id(card: CaveCard): Int = ids.getValue(card)

    fun of(id: Int): CaveCard = ALL.getOrNull(id) ?: throw IllegalArgumentException("Unknown cave card $id")

    /** The cards of a round: the full deck less one card of each hazard in [removed], once for every time it is there. */
    fun deck(removed: List<Hazard>): List<CaveCard> {
        val cards = ALL.toMutableList()
        for (hazard in removed) {
            val last = cards.lastOrNull { it is CaveCard.Danger && it.hazard == hazard } ?: continue
            cards.remove(last)
        }
        return cards
    }
}
