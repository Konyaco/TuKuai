package io.github.konyaco.tukuai.engine.paodekuai

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.card.Suit
import io.github.konyaco.tukuai.engine.core.GuideBlock
import io.github.konyaco.tukuai.engine.core.GuideBlock.Example
import io.github.konyaco.tukuai.engine.core.GuideBlock.Point
import io.github.konyaco.tukuai.engine.core.GuideBlock.Text
import io.github.konyaco.tukuai.engine.core.GuideSection
import io.github.konyaco.tukuai.engine.doudizhu.DdzGuide

/**
 * The Pao De Kuai rule book. Every example here is checked against [PdkPatterns] by the
 * tests, so the book can't teach a play the table would refuse.
 */
object PdkGuide {
    private const val K = "tukuai.guide.paodekuai"

    /** The section on what may go out short as the last cards; its examples are last hands. */
    const val LAST = "last"

    /** One example of each pattern, in the order the book shows them. */
    val PATTERNS: List<Pair<PdkPatternType, List<PlayingCard>>> = listOf(
        PdkPatternType.SINGLE to cards("K"),
        PdkPatternType.PAIR to cards("8 8"),
        PdkPatternType.PAIR_RUN to cards("5 5 6 6 7 7"),
        PdkPatternType.STRAIGHT to cards("8 9 10 J Q K A"),
        PdkPatternType.TRIO_TWO to cards("Q Q Q 6 9"),
        PdkPatternType.PLANE to cards("7 7 7 8 8 8 3 4 J J"),
        PdkPatternType.FOUR_THREE to cards("6 6 6 6 3 9 J"),
        PdkPatternType.BOMB to cards("10 10 10 10"),
    )

    /** Hands that may only be played as the last cards, each as (name, cards). */
    val LAST_HANDS: List<Pair<String, List<PlayingCard>>> = listOf(
        "trio" to cards("J J J"),
        "trio_one" to cards("J J J 5"),
        "plane" to cards("7 7 7 8 8 8 4 9"),
    )

    fun sections(rules: PdkRules?): List<GuideSection> = listOfNotNull(
        rules?.let { GuideSection(GuideSection.TABLE, table(it)) },
        GuideSection("goal", texts("goal", 2)),
        GuideSection(
            "round",
            texts("round", 1) + Example(null, null, listOf(listOf(PaoDeKuai.STARTER))) + points("round", 2..5),
        ),
        GuideSection(
            "order",
            listOf(
                Text("$K.order.1"),
                Example(null, null, listOf(cards("3 4 5 6 7 8 9 10 J Q K A 2"))),
                Text("$K.order.2"),
            ),
        ),
        GuideSection(
            "patterns",
            listOf(Text("$K.patterns.1")) + PATTERNS.map { (type, cards) ->
                val key = "$K.pattern.${type.name.lowercase()}"
                Example(key, "$key.text", listOf(cards))
            },
        ),
        GuideSection(
            "beating",
            listOf(
                Text("$K.beating.1"),
                beat("pair", "4 4", "9 9", true),
                beat("run", "5 5 6 6", "9 9 10 10", true),
                beat("length", "3 4 5 6 7", "8 9 10 J Q K", false),
                beat("trio", "8 8 8 3 4", "Q Q Q 5 5", true),
                beat("shape", "9 9", "J J J 4 4", false),
                beat("bomb", "Q Q Q 3 3", "5 5 5 5", true),
                beat("bombs", "3 3 3 3", "5 5 5 5", true),
            ),
        ),
        GuideSection(
            LAST,
            listOf(Text("$K.last.1")) + LAST_HANDS.map { (name, cards) ->
                Example("$K.last.$name", "$K.last.$name.text", listOf(cards))
            } + Text("$K.last.2"),
        ),
        GuideSection("warning", texts("warning", 2) + Example(null, "$K.warning.example", listOf(cards("4 9 K"))) + Text("$K.warning.3")),
        GuideSection("scoring", texts("scoring", 1) + points("scoring", 2..5)),
    )

    /** "This table": every field of [rules], in words. */
    private fun table(rules: PdkRules): List<GuideBlock> {
        val t = "$K.table"
        fun on(name: String, on: Boolean) = Point("$t.$name.${if (on) "on" else "off"}")
        return listOf(
            Point("$t.deal.${rules.handSize}"),
            on("must_beat", rules.mustBeat),
            on("four_three", rules.fourWithThree),
            if (rules.bombPoints > 0) Point("$t.bombs", listOf(rules.bombPoints)) else Point("$t.bombs.none"),
        )
    }

    private fun texts(section: String, count: Int): List<GuideBlock> = (1..count).map { Text("$K.$section.$it") }

    private fun points(section: String, numbers: IntRange): List<GuideBlock> = numbers.map { Point("$K.$section.$it") }

    private fun beat(name: String, first: String, then: String, beats: Boolean) =
        Example(null, "$K.beating.$name", listOf(cards(first), cards(then)), follows = beats)

    /** Cards from text like "3 3 4", as [DdzGuide.cards] gives them, but with the one 2 that is in this deck. */
    fun cards(text: String): List<PlayingCard> = DdzGuide.cards(text).map { card ->
        if (card is PlayingCard.Regular && card.rank == Rank.TWO) PlayingCard.Regular(Rank.TWO, Suit.SPADES) else card
    }
}
