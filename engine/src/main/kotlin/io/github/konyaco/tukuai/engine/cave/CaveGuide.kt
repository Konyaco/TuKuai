package io.github.konyaco.tukuai.engine.cave

import io.github.konyaco.tukuai.engine.core.GuideBlock
import io.github.konyaco.tukuai.engine.core.GuideBlock.Example
import io.github.konyaco.tukuai.engine.core.GuideBlock.Point
import io.github.konyaco.tukuai.engine.core.GuideBlock.Text
import io.github.konyaco.tukuai.engine.core.GuideSection

/** The cave expedition rule book. The tests check that its numbers agree with [CaveDeck]. */
object CaveGuide {
    private const val K = "tukuai.guide.cave"

    /** One card of each ore, poorest first, to show what they are worth. */
    val ORES: List<CaveCard> = Ore.entries.map { ore -> CaveDeck.VEINS.first { it.ore == ore } }

    /** One card of each hazard. */
    val HAZARDS: List<CaveCard> = Hazard.entries.map { hazard -> CaveDeck.DANGERS.first { it.hazard == hazard } }

    /** The example of a vein shared out: worth 7, among 3 players. */
    val SHARED: CaveCard = CaveDeck.VEINS.first { it.value == 7 }
    const val SHARED_AMONG = 3

    /** The example of a round ending: a creeper, some ore, and a second creeper. */
    val ENDING: List<CaveCard> = listOf(
        CaveDeck.DANGERS.first { it.hazard == Hazard.CREEPER },
        CaveDeck.VEINS.first { it.value == 9 },
        CaveDeck.DANGERS.last { it.hazard == Hazard.CREEPER },
    )

    fun sections(rules: CaveRules?): List<GuideSection> = listOfNotNull(
        rules?.let { GuideSection(GuideSection.TABLE, table(it)) },
        GuideSection("goal", texts("goal", 2)),
        GuideSection("round", texts("round", 2)),
        GuideSection(
            "ore",
            listOf(
                Text("$K.ore.1", listOf(CaveDeck.VEINS.size, Ore.entries.size)),
                Example(null, "$K.ore.cards", ORES.map { listOf(it) }),
                Text("$K.ore.2"),
                Example(null, "$K.ore.shared", listOf(listOf(SHARED))),
            ),
        ),
        GuideSection("choose", texts("choose", 1..1) + points("choose", 2..3) + Text("$K.choose.4")),
        GuideSection(
            "danger",
            listOf(
                Text("$K.danger.1", listOf(Hazard.entries.size, CaveDeck.COPIES)),
                Example(null, "$K.danger.cards", HAZARDS.map { listOf(it) }),
                Text("$K.danger.2"),
                Example(null, "$K.danger.ending", ENDING.map { listOf(it) }),
                Text("$K.danger.3"),
                Text("$K.danger.4"),
            ),
        ),
        GuideSection("end", texts("end", 2)),
    )

    /** "This table": every field of [rules], in words. */
    private fun table(rules: CaveRules): List<GuideBlock> {
        val t = "$K.table"
        return listOfNotNull(
            Point("$t.players", listOf(rules.players)),
            Point("$t.rounds", listOf(rules.rounds)),
            if (rules.players > 4) Point("$t.big_table") else null,
        )
    }

    private fun texts(section: String, count: Int): List<GuideBlock> = texts(section, 1..count)

    private fun texts(section: String, numbers: IntRange): List<GuideBlock> = numbers.map { Text("$K.$section.$it") }

    private fun points(section: String, numbers: IntRange): List<GuideBlock> = numbers.map { Point("$K.$section.$it") }
}
