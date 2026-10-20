package io.github.konyaco.tukuai.engine.uno

import io.github.konyaco.tukuai.engine.core.GuideBlock
import io.github.konyaco.tukuai.engine.core.GuideBlock.Example
import io.github.konyaco.tukuai.engine.core.GuideBlock.Point
import io.github.konyaco.tukuai.engine.core.GuideBlock.Text
import io.github.konyaco.tukuai.engine.core.GuideSection

/**
 * The UNO rule book. The tests play every example through [Uno.playable], so what the
 * book says may go on what is what the table allows.
 */
object UnoGuide {
    private const val K = "tukuai.guide.uno"

    /** The section whose examples follow [UnoRules.STACKING] rather than the classic rules. */
    const val STACKING = "stacking"

    fun sections(rules: UnoRules?): List<GuideSection> = listOfNotNull(
        rules?.let { GuideSection(GuideSection.TABLE, table(it)) },
        GuideSection("goal", texts("goal", 3)),
        GuideSection(
            "turn",
            listOf(
                Text("$K.turn.1"),
                Example(null, "$K.turn.match", listOf(listOf(num(UnoColor.RED, 7)), listOf(num(UnoColor.RED, 2))), follows = true),
                Example(null, "$K.turn.number", listOf(listOf(num(UnoColor.RED, 7)), listOf(num(UnoColor.BLUE, 7))), follows = true),
                Example(null, "$K.turn.neither", listOf(listOf(num(UnoColor.RED, 7)), listOf(num(UnoColor.GREEN, 4))), follows = false),
                Text("$K.turn.2"),
                Text("$K.turn.3"),
            ),
        ),
        GuideSection(
            "actions",
            listOf(
                Text("$K.actions.1"),
                action("skip", UnoFace(UnoColor.RED, UnoKind.SKIP)),
                action("reverse", UnoFace(UnoColor.YELLOW, UnoKind.REVERSE)),
                action("draw_two", UnoFace(UnoColor.GREEN, UnoKind.DRAW_TWO)),
                action("wild", WILD),
                action("wild_four", WILD_FOUR),
                Example(
                    null,
                    "$K.actions.symbol",
                    listOf(listOf(UnoFace(UnoColor.RED, UnoKind.SKIP)), listOf(UnoFace(UnoColor.BLUE, UnoKind.SKIP))),
                    follows = true,
                ),
            ),
        ),
        GuideSection("call", texts("call", 3)),
        GuideSection(
            STACKING,
            listOf(
                Text("$K.stacking.1"),
                Example(
                    null,
                    "$K.stacking.chain",
                    listOf(listOf(draw2(UnoColor.GREEN)), listOf(draw2(UnoColor.BLUE)), listOf(WILD_FOUR)),
                    follows = true,
                ),
                Example(null, "$K.stacking.four", listOf(listOf(WILD_FOUR), listOf(draw2(UnoColor.RED))), follows = false),
                Text("$K.stacking.2"),
            ),
        ),
        GuideSection(
            "scoring",
            listOf(
                Text("$K.scoring.1"),
                Example(
                    null,
                    "$K.scoring.points",
                    listOf(listOf(num(UnoColor.BLUE, 7)), listOf(UnoFace(UnoColor.YELLOW, UnoKind.REVERSE)), listOf(WILD)),
                ),
                Text("$K.scoring.2"),
            ),
        ),
    )

    /** "This table": every field of [rules], in words. */
    private fun table(rules: UnoRules): List<GuideBlock> {
        val t = "$K.table"
        return listOfNotNull(
            Point("$t.players", listOf(rules.players, rules.handSize)),
            Point("$t.stacking.${if (rules.stacking) "on" else "off"}"),
            Point("$t.wild_four.${if (rules.strictWildFour) "strict" else "free"}"),
            if (rules.unoPenalty > 0) Point("$t.penalty", listOf(rules.unoPenalty)) else Point("$t.penalty.none"),
            // Uno.play: between two players a reverse works like a skip.
            if (rules.players == 2) Point("$t.two_players") else null,
        )
    }

    private val WILD = UnoFace(null, UnoKind.WILD)
    private val WILD_FOUR = UnoFace(null, UnoKind.WILD_DRAW_FOUR)

    private fun num(color: UnoColor, number: Int) = UnoFace(color, UnoKind.NUMBER, number)

    private fun draw2(color: UnoColor) = UnoFace(color, UnoKind.DRAW_TWO)

    private fun action(name: String, face: UnoFace) = Example("$K.actions.$name", "$K.actions.$name.text", listOf(listOf(face)))

    private fun texts(section: String, count: Int): List<GuideBlock> = (1..count).map { Text("$K.$section.$it") }
}
