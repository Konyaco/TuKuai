package io.github.konyaco.tukuai.engine.guandan

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.core.GuideBlock
import io.github.konyaco.tukuai.engine.core.GuideBlock.Example
import io.github.konyaco.tukuai.engine.core.GuideBlock.Point
import io.github.konyaco.tukuai.engine.core.GuideBlock.Text
import io.github.konyaco.tukuai.engine.core.GuideSection

/**
 * The Guandan rule book. Its examples are played at level 2, as the first round is, so
 * the two 2 of hearts are the wild cards; the tests check every one against [GdPatterns].
 */
object GdGuide {
    private const val K = "tukuai.guide.guandan"

    /** The level the examples are played at. */
    val LEVEL: Rank = Rank.TWO

    /** One example of each pattern, in the order the book shows them. */
    val PATTERNS: List<Pair<GdPatternType, List<PlayingCard>>> = listOf(
        GdPatternType.SINGLE to cards("K"),
        GdPatternType.PAIR to cards("9 9"),
        GdPatternType.TRIPLE to cards("6 6 6"),
        GdPatternType.FULL_HOUSE to cards("J J J 4 4"),
        GdPatternType.STRAIGHT to cards("10s Jc Qd Ks Ah"),
        GdPatternType.PAIR_RUN to cards("5 5 6 6 7 7"),
        GdPatternType.PLANE to cards("8 8 8 9 9 9"),
        GdPatternType.BOMB to cards("4 4 4 4"),
        GdPatternType.STRAIGHT_FLUSH to cards("6h 7h 8h 9h 10h"),
        GdPatternType.JOKER_BOMB to cards("SJ SJ BJ BJ"),
    )

    /** What the wild hearts can make, each as (name, cards, the pattern they make). */
    val WILD: List<Triple<String, List<PlayingCard>, GdPatternType>> = listOf(
        Triple("triple", cards("8 8 W"), GdPatternType.TRIPLE),
        Triple("straight", cards("5s 6c W 8d 9s"), GdPatternType.STRAIGHT),
        Triple("bomb", cards("Q Q Q Q W"), GdPatternType.BOMB),
        Triple("pair", cards("W W"), GdPatternType.PAIR),
    )

    /** Bombs from weakest to strongest, each beating the one before. */
    val BOMBS: List<List<PlayingCard>> = listOf(
        cards("7 7 7 7"),
        cards("3 3 3 3 3"),
        cards("4d 5d 6d 7d 8d"),
        cards("9 9 9 9 9 9"),
        cards("SJ SJ BJ BJ"),
    )

    /** Pairs of plays (name, first, then, whether the second may go on the first). */
    val BEATING: List<Pair<String, Triple<List<PlayingCard>, List<PlayingCard>, Boolean>>> = listOf(
        "pair" to Triple(cards("4 4"), cards("9 9"), true),
        "level" to Triple(cards("A"), cards("2s"), true),
        "joker" to Triple(cards("2s"), cards("SJ"), true),
        "straight" to Triple(cards("As 2c 3d 4s 5c"), cards("10s Jc Qd Ks Ac"), true),
        "shape" to Triple(cards("9 9"), cards("J J J 4 4"), false),
        "bomb" to Triple(cards("K K K 5 5"), cards("3 3 3 3"), true),
    )

    fun sections(rules: GdRules?): List<GuideSection> = listOfNotNull(
        rules?.let { GuideSection(GuideSection.TABLE, table(it)) },
        GuideSection("goal", texts("goal", 3)),
        GuideSection("order", listOf(Text("$K.order.1"), Example(null, "$K.order.example", listOf(cards("2 3 4 5 6 8 9 10 J Q K A 7 SJ BJ", Rank.SEVEN)))) + texts("order", 2..3)),
        GuideSection(
            "wild",
            listOf(Text("$K.wild.1"), Example(null, null, listOf(cards("W W")))) +
                WILD.map { (name, cards, _) -> Example("$K.wild.$name", "$K.wild.$name.text", listOf(cards)) } +
                Text("$K.wild.2"),
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
            listOf(Text("$K.beating.1")) + BEATING.map { (name, play) ->
                Example(null, "$K.beating.$name", listOf(play.first, play.second), follows = play.third)
            },
        ),
        // Two rows of three, the straight flush in both, so the cards stay large enough to read.
        GuideSection(
            "bombs",
            listOf(
                Text("$K.bombs.1"),
                Example(null, "$K.bombs.example", BOMBS.take(3), follows = true),
                Example(null, null, BOMBS.drop(2), follows = true),
                Text("$K.bombs.2"),
            ),
        ),
        GuideSection("round", texts("round", 1) + points("round", 2..4) + Text("$K.round.5")),
        GuideSection("levels", texts("levels", 1) + points("levels", 2..4) + texts("levels", 5..6)),
        GuideSection("tribute", texts("tribute", 1) + points("tribute", 2..5)),
    )

    /** "This table": how long the game goes. */
    private fun table(rules: GdRules): List<GuideBlock> = listOf(
        Point("$K.table.${if (rules.toAce) "to_ace" else "one_round"}"),
        Point("$K.table.seats"),
    )

    private fun texts(section: String, count: Int): List<GuideBlock> = (1..count).map { Text("$K.$section.$it") }

    private fun texts(section: String, numbers: IntRange): List<GuideBlock> = numbers.map { Text("$K.$section.$it") }

    private fun points(section: String, numbers: IntRange): List<GuideBlock> = numbers.map { Point("$K.$section.$it") }

    fun cards(text: String, level: Rank = LEVEL): List<PlayingCard> = GdCards.parse(text, level)
}
