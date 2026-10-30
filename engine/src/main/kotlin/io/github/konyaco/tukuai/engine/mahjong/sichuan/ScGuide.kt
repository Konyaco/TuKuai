package io.github.konyaco.tukuai.engine.mahjong.sichuan

import io.github.konyaco.tukuai.engine.core.GuideBlock
import io.github.konyaco.tukuai.engine.core.GuideBlock.Example
import io.github.konyaco.tukuai.engine.core.GuideBlock.Point
import io.github.konyaco.tukuai.engine.core.GuideBlock.Text
import io.github.konyaco.tukuai.engine.core.GuideSection
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit
import io.github.konyaco.tukuai.engine.mahjong.Tiles

/**
 * The Sichuan mahjong rule book. Its example hands are tiles in groups, each group a set or
 * the pair; the tests score every winning hand in it with [ScScoring], so a hand the book
 * says is worth some fan is worth that at the table.
 */
object ScGuide {
    private const val K = "tukuai.guide.mahjong"

    private fun t(text: String): List<Tile> = Tiles.parse(text)

    /** A winning hand: every group concealed, won on an ordinary discard. */
    data class Hand(val key: String, val groups: List<List<Tile>>, val fans: List<ScFan>)

    /** The kinds of set, each as (name, tiles). */
    val SETS: List<Pair<String, List<Tile>>> = listOf(
        "run" to t("345m"),
        "pung" to t("777p"),
        "kong" to t("2222s"),
        "pair" to t("99p"),
    )

    /** A hand of each kind that wins, and the fan it is worth. */
    val HANDS: List<Hand> = listOf(
        Hand("plain", listOf(t("123m"), t("456m"), t("789p"), t("555p"), t("99m")), emptyList()),
        Hand("all_pungs", listOf(t("222m"), t("555m"), t("777p"), t("999p"), t("33p")), listOf(ScFan.ALL_PUNGS)),
        Hand("full_flush", listOf(t("123p"), t("345p"), t("567p"), t("789p"), t("99p")), listOf(ScFan.FULL_FLUSH)),
        Hand("seven_pairs", listOf(t("11m"), t("33m"), t("55m"), t("77m"), t("22s"), t("44s"), t("66s")), listOf(ScFan.SEVEN_PAIRS)),
        Hand("dragon_seven_pairs", listOf(t("11m"), t("11m"), t("55m"), t("77m"), t("22s"), t("44s"), t("66s")), listOf(ScFan.DRAGON_SEVEN_PAIRS)),
        Hand("gen", listOf(t("456m"), t("789m"), t("111s"), t("123s"), t("66s")), listOf(ScFan.GEN)),
    )

    /** The fan that come from how a hand was won rather than its tiles. */
    val WAYS: List<ScFan> = listOf(ScFan.KONG_BLOOM, ScFan.KONG_DISCARD, ScFan.ROB_KONG, ScFan.LAST_TILE)

    fun sections(rules: ScRules?): List<GuideSection> = listOfNotNull(
        rules?.let { GuideSection(GuideSection.TABLE, table(it)) },
        GuideSection("goal", texts("goal", 3)),
        GuideSection(
            "tiles",
            listOf(Text("$K.tiles.1")) +
                TileSuit.NUMBERED.map { Example("$K.tiles.${it.name.lowercase()}", null, listOf(it.tiles)) } +
                Text("$K.tiles.2"),
        ),
        GuideSection(
            "sets",
            listOf(Text("$K.sets.1")) + SETS.map { (name, tiles) -> Example("$K.sets.$name", "$K.sets.$name.text", listOf(tiles)) } +
                Text("$K.sets.2") + Example(null, "$K.sets.example", HANDS.first().groups),
        ),
        GuideSection("start", texts("start", 1..1) + points("start", 2..3) + Text("$K.start.4")),
        GuideSection("turn", texts("turn", 3)),
        GuideSection(
            "claims",
            texts("claims", 1..1) +
                listOf(
                    Example("$K.claims.pung", "$K.claims.pung.text", listOf(t("77p"), t("7p")), follows = true),
                    Example("$K.claims.kong", "$K.claims.kong.text", listOf(t("777p"), t("7p")), follows = true),
                ) +
                points("claims", 2..4) + Text("$K.claims.5"),
        ),
        GuideSection(
            "hands",
            texts("hands", 1..1) + HANDS.map { Example("$K.hands.${it.key}", "$K.hands.${it.key}.text", it.groups) } +
                WAYS.map { Point("$K.fan.${it.name.lowercase()}") } + Point("$K.fan.heavenly"),
        ),
        GuideSection("points", texts("points", 1..1) + points("points", 2..5)),
        GuideSection("end", texts("end", 1..1) + points("end", 2..3) + Text("$K.end.4")),
    )

    /** "This table": every field of [rules], in words. */
    private fun table(rules: ScRules): List<GuideBlock> {
        val t = "$K.table"
        return listOf(
            Point("$t.players"),
            Point(if (rules.swapThree) "$t.swap.on" else "$t.swap.off"),
            if (rules.maxFan > 0) Point("$t.cap", listOf(rules.maxFan, rules.points(rules.maxFan))) else Point("$t.no_cap"),
            Point(if (rules.heavenly) "$t.heavenly.on" else "$t.heavenly.off"),
        )
    }

    private fun texts(section: String, count: Int): List<GuideBlock> = texts(section, 1..count)

    private fun texts(section: String, numbers: IntRange): List<GuideBlock> = numbers.map { Text("$K.$section.$it") }

    private fun points(section: String, numbers: IntRange): List<GuideBlock> = numbers.map { Point("$K.$section.$it") }
}
