package io.github.konyaco.tukuai.engine.tutu

import io.github.konyaco.tukuai.engine.core.GuideBlock
import io.github.konyaco.tukuai.engine.core.GuideBlock.Example
import io.github.konyaco.tukuai.engine.core.GuideBlock.Point
import io.github.konyaco.tukuai.engine.core.GuideBlock.Text
import io.github.konyaco.tukuai.engine.core.GuideSection

/**
 * The Tutu cards rule book. The tests play every example through [Tutu.playable], so what
 * the book says may go on what is what the table allows.
 */
object TutuGuide {
    private const val K = "tukuai.guide.tutu"

    fun sections(rules: TutuRules?): List<GuideSection> = listOfNotNull(
        rules?.let { GuideSection(GuideSection.TABLE, table(it)) },
        GuideSection("goal", texts("goal", 3)),
        GuideSection(
            "turn",
            listOf(
                Text("$K.turn.1"),
                Example(null, "$K.turn.match", listOf(listOf(num(TutuColor.YELLOW, 7)), listOf(num(TutuColor.YELLOW, 2))), follows = true),
                Example(null, "$K.turn.number", listOf(listOf(num(TutuColor.YELLOW, 7)), listOf(num(TutuColor.BLUE, 7))), follows = true),
                Example(null, "$K.turn.neither", listOf(listOf(num(TutuColor.YELLOW, 7)), listOf(num(TutuColor.GREEN, 4))), follows = false),
                Text("$K.turn.2"),
            ),
        ),
        GuideSection(
            "specials",
            listOf(
                Text("$K.specials.1"),
                special("snowball", TutuFace(TutuColor.BLUE, TutuKind.SNOWBALL)),
                special("trident", TutuFace(TutuColor.GREEN, TutuKind.TRIDENT)),
                special("shovel", TutuFace(TutuColor.YELLOW, TutuKind.SHOVEL)),
                special("lightning", TutuFace(TutuColor.PURPLE, TutuKind.LIGHTNING)),
                Example(
                    null,
                    "$K.specials.symbol",
                    listOf(listOf(TutuFace(TutuColor.BLUE, TutuKind.SHOVEL)), listOf(TutuFace(TutuColor.PURPLE, TutuKind.SHOVEL))),
                    follows = true,
                ),
            ),
        ),
        GuideSection(
            "wilds",
            listOf(
                Text("$K.wilds.1"),
                special("egg", EGG),
                special("tnt", TNT),
                special("cactus", CACTUS),
                Text("$K.wilds.2"),
            ),
        ),
        GuideSection(
            "scoring",
            listOf(
                Text("$K.scoring.1"),
                Text("$K.scoring.2"),
                Example(
                    null,
                    "$K.scoring.points",
                    listOf(listOf(num(TutuColor.BLUE, 7), TutuFace(TutuColor.YELLOW, TutuKind.TRIDENT), TNT)),
                ),
            ),
        ),
    )

    /** "This table": every field of [rules], in words. */
    private fun table(rules: TutuRules): List<GuideBlock> {
        val t = "$K.table"
        val size = if (rules.party) TutuDeck.SIZE else TutuDeck.BASE_SIZE
        return listOfNotNull(
            Point("$t.players", listOf(rules.players, rules.handSize)),
            if (rules.party) Point("$t.party", listOf(size)) else Point("$t.classic", listOf(size)),
            // Tutu.play: between two players a trident works like a snowball.
            if (rules.players == 2) Point("$t.two_players") else null,
        )
    }

    private val EGG = TutuFace(null, TutuKind.EGG)
    private val TNT = TutuFace(null, TutuKind.TNT)
    private val CACTUS = TutuFace(null, TutuKind.CACTUS)

    private fun num(color: TutuColor, number: Int) = TutuFace(color, TutuKind.NUMBER, number)

    private fun special(name: String, face: TutuFace) = Example("$K.cards.$name", "$K.cards.$name.text", listOf(listOf(face)))

    private fun texts(section: String, count: Int): List<GuideBlock> = (1..count).map { Text("$K.$section.$it") }
}
