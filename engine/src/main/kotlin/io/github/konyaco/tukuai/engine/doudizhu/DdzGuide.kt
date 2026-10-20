package io.github.konyaco.tukuai.engine.doudizhu

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.card.Suit
import io.github.konyaco.tukuai.engine.core.GuideBlock
import io.github.konyaco.tukuai.engine.core.GuideBlock.Example
import io.github.konyaco.tukuai.engine.core.GuideBlock.Point
import io.github.konyaco.tukuai.engine.core.GuideBlock.Text
import io.github.konyaco.tukuai.engine.core.GuideSection

/**
 * The Dou Dizhu rule book. Every example here is checked against [Patterns] by the tests,
 * so the book can't teach a play the table would refuse.
 */
object DdzGuide {
    private const val K = "tukuai.guide.doudizhu"

    /** One example of each pattern, in the order the book shows them. */
    val PATTERNS: List<Pair<PatternType, List<PlayingCard>>> = listOf(
        PatternType.SINGLE to cards("K"),
        PatternType.PAIR to cards("8 8"),
        PatternType.TRIO to cards("J J J"),
        PatternType.TRIO_SINGLE to cards("9 9 9 4"),
        PatternType.TRIO_PAIR to cards("Q Q Q 6 6"),
        PatternType.STRAIGHT to cards("8 9 10 J Q K A"),
        PatternType.PAIR_STRAIGHT to cards("5 5 6 6 7 7"),
        PatternType.PLANE to cards("7 7 7 8 8 8"),
        PatternType.PLANE_SINGLES to cards("7 7 7 8 8 8 3 K"),
        PatternType.PLANE_PAIRS to cards("7 7 7 8 8 8 4 4 J J"),
        PatternType.FOUR_TWO_SINGLES to cards("6 6 6 6 3 9"),
        PatternType.FOUR_TWO_PAIRS to cards("6 6 6 6 3 3 9 9"),
        PatternType.BOMB to cards("10 10 10 10"),
        PatternType.ROCKET to cards("BJ RJ"),
    )

    fun sections(rules: DdzRules?): List<GuideSection> = listOfNotNull(
        rules?.let { GuideSection(GuideSection.TABLE, table(it)) },
        GuideSection("goal", texts("goal", 3)),
        GuideSection("round", texts("round", 2) + points("round", 3..6)),
        GuideSection(
            "order",
            listOf(
                Text("$K.order.1"),
                Example(null, null, listOf(cards("3 4 5 6 7 8 9 10 J Q K A 2 BJ RJ"))),
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
                beat("straight", "3 4 5 6 7", "4 5 6 7 8", true),
                beat("length", "3 4 5 6 7", "8 9 10 J Q K", false),
                beat("shape", "9 9", "K K K", false),
                beat("kicker", "7 7 7 A", "8 8 8 3", true),
                beat("bomb", "A A", "3 3 3 3", true),
                beat("bombs", "3 3 3 3", "5 5 5 5", true),
                beat("rocket", "2 2 2 2", "BJ RJ", true),
                Text("$K.beating.2"),
            ),
        ),
        GuideSection("scoring", texts("scoring", 2) + points("scoring", 3..6) + Text("$K.scoring.7")),
    )

    /** "This table": every field of [rules], in words. */
    private fun table(rules: DdzRules): List<GuideBlock> {
        val t = "$K.table"
        fun on(name: String, on: Boolean) = Point("$t.$name.${if (on) "on" else "off"}")
        return listOf(
            Point("$t.bidding.${rules.bidding.name.lowercase()}"),
            on("doubling", rules.doubling),
            Point("$t.deal.${rules.deal.name.lowercase()}"),
            on("four_two", rules.allowFourWithTwo),
            Point("$t.base", listOf(rules.baseScore)),
            on("bombs", rules.bombsDouble),
            on("spring", rules.springDoubles),
            if (rules.maxMultiplier > 0) Point("$t.cap", listOf(rules.maxMultiplier)) else Point("$t.cap.none"),
            if (rules.redealLimit > 0) Point("$t.redeal", listOf(rules.redealLimit)) else Point("$t.redeal.none"),
        )
    }

    private fun texts(section: String, count: Int): List<GuideBlock> = (1..count).map { Text("$K.$section.$it") }

    private fun points(section: String, numbers: IntRange): List<GuideBlock> = numbers.map { Point("$K.$section.$it") }

    private fun beat(name: String, first: String, then: String, beats: Boolean) =
        Example(null, "$K.beating.$name", listOf(cards(first), cards(then)), follows = beats)

    /**
     * Cards from text like "3 3 4 BJ". Suits are spread out so a pair shows two suits and
     * a straight isn't all spades, which would only make people wonder whether it matters.
     */
    fun cards(text: String): List<PlayingCard> {
        val seen = HashMap<Int, Int>()
        return text.split(' ').filter { it.isNotBlank() }.map { label ->
            val value = DdzValue.ALL.first { DdzValue.label(it) == label }
            val copy = seen.merge(value, 1, Int::plus)!! - 1
            when (value) {
                DdzValue.BLACK_JOKER -> PlayingCard.BlackJoker
                DdzValue.RED_JOKER -> PlayingCard.RedJoker
                else -> {
                    val rank = when (value) {
                        DdzValue.ACE -> Rank.ACE
                        DdzValue.TWO -> Rank.TWO
                        else -> Rank.entries[value - 1]
                    }
                    PlayingCard.Regular(rank, Suit.entries[(value + copy) % Suit.entries.size])
                }
            }
        }
    }
}
