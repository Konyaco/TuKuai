package io.github.konyaco.tukuai.engine.bluff

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.core.GuideBlock
import io.github.konyaco.tukuai.engine.core.GuideBlock.Example
import io.github.konyaco.tukuai.engine.core.GuideBlock.Point
import io.github.konyaco.tukuai.engine.core.GuideBlock.Text
import io.github.konyaco.tukuai.engine.core.GuideSection
import io.github.konyaco.tukuai.engine.doudizhu.DdzGuide

/**
 * The creeper bluff rule book. The tests turn over each example play with [Bluff.matches],
 * so a play the book calls honest is one a call would find honest.
 */
object BluffGuide {
    private const val K = "tukuai.guide.bluff"

    /** The rank the book's examples call for. */
    val TARGET = Rank.QUEEN

    /** Example plays when queens are called for, each as (name, cards, whether it is honest). */
    val PLAYS: List<Triple<String, List<PlayingCard>, Boolean>> = listOf(
        Triple("honest", DdzGuide.cards("Q Q"), true),
        Triple("joker", DdzGuide.cards("Q RJ"), true),
        Triple("lie", DdzGuide.cards("Q 7 Q"), false),
    )

    fun sections(rules: BluffRules?): List<GuideSection> = listOfNotNull(
        rules?.let { GuideSection(GuideSection.TABLE, table(it)) },
        GuideSection("goal", texts("goal", 2)),
        GuideSection(
            "round",
            listOf(Text("$K.round.1"), Example(null, "$K.round.target", listOf(DdzGuide.cards("Q")))) + texts("round", 2..3),
        ),
        GuideSection(
            "turn",
            listOf(Text("$K.turn.1")) + PLAYS.map { (name, cards, _) -> Example("$K.turn.$name", "$K.turn.$name.text", listOf(cards)) } +
                Text("$K.turn.2"),
        ),
        GuideSection("call", texts("call", 1..1) + points("call", 2..3) + Text("$K.call.4")),
        GuideSection("penalty", texts("penalty", 1..3)),
        GuideSection("empty", texts("empty", 1..2)),
        GuideSection("end", texts("end", 1..2)),
    )

    /** "This table": every field of [rules], in words. */
    private fun table(rules: BluffRules): List<GuideBlock> {
        val t = "$K.table"
        val ranks = Bluff.ranks(rules).size
        return listOfNotNull(
            Point("$t.players", listOf(rules.players, rules.handSize)),
            Point("$t.deck", listOf(ranks, Bluff.deck(rules).size)),
            Point(if (rules.jokers) "$t.jokers.on" else "$t.jokers.off"),
            Point("$t.max_play", listOf(rules.maxPlay)),
            when (rules.penalty) {
                BluffPenalty.FUSE -> Point("$t.fuse", listOf(rules.chances))
                BluffPenalty.HEARTS -> Point("$t.hearts", listOf(rules.chances))
            },
            if (rules.players > 4) Point("$t.big_table") else null,
        )
    }

    private fun texts(section: String, count: Int): List<GuideBlock> = texts(section, 1..count)

    private fun texts(section: String, numbers: IntRange): List<GuideBlock> = numbers.map { Text("$K.$section.$it") }

    private fun points(section: String, numbers: IntRange): List<GuideBlock> = numbers.map { Point("$K.$section.$it") }
}
