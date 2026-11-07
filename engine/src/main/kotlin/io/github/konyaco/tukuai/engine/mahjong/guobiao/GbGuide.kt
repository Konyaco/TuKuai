package io.github.konyaco.tukuai.engine.mahjong.guobiao

import io.github.konyaco.tukuai.engine.core.GuideBlock
import io.github.konyaco.tukuai.engine.core.GuideBlock.Example
import io.github.konyaco.tukuai.engine.core.GuideBlock.Point
import io.github.konyaco.tukuai.engine.core.GuideBlock.Text
import io.github.konyaco.tukuai.engine.core.GuideSection
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit
import io.github.konyaco.tukuai.engine.mahjong.Tiles

/**
 * The Guobiao mahjong rule book. Each example hand is tiles in groups; the tests score each
 * one with [GbScoring], won on a discard with every tile concealed, and check it has the fan
 * the book names it for.
 */
object GbGuide {
    private const val K = "tukuai.guide.guobiao"

    private fun t(text: String): List<Tile> = Tiles.parse(text)

    /** A hand that shows one fan: [fan] is among the fan it counts. */
    data class Hand(val fan: GbFan, val groups: List<List<Tile>>)

    val SETS: List<Pair<String, List<Tile>>> = listOf(
        "chow" to t("345m"),
        "pung" to t("777p"),
        "kong" to t("2222s"),
        "pair" to t("99p"),
    )

    /** Hands that win in a shape other than four sets and a pair. */
    val SHAPES: List<Hand> = listOf(
        Hand(GbFan.SEVEN_PAIRS, listOf(t("11m"), t("33m"), t("55p"), t("77p"), t("22s"), t("11w"), t("11d"))),
        Hand(GbFan.THIRTEEN_ORPHANS, listOf(t("19m"), t("19p"), t("19s"), t("1234w"), t("123d"), t("1m"))),
        Hand(GbFan.LESSER_HONOURS_KNITTED, listOf(t("147m"), t("258p"), t("36s"), t("1234w"), t("12d"))),
        Hand(GbFan.KNITTED_STRAIGHT, listOf(t("147m"), t("258p"), t("369s"), t("789m"), t("55s"))),
    )

    /** Common ways to reach 8 fan. */
    val HANDS: List<Hand> = listOf(
        Hand(GbFan.MIXED_STRAIGHT, listOf(t("123m"), t("456p"), t("789s"), t("234s"), t("55m"))),
        Hand(GbFan.MIXED_TRIPLE_CHOW, listOf(t("345m"), t("345p"), t("345s"), t("789m"), t("22p"))),
        Hand(GbFan.ALL_PUNGS, listOf(t("222m"), t("555p"), t("888s"), t("111w"), t("99m"))),
        Hand(GbFan.HALF_FLUSH, listOf(t("234p"), t("666p"), t("111d"), t("333w"), t("88p"))),
        Hand(GbFan.FULL_FLUSH, listOf(t("123s"), t("345s"), t("567s"), t("999s"), t("88s"))),
        Hand(GbFan.PURE_STRAIGHT, listOf(t("123m"), t("456m"), t("789m"), t("234p"), t("55s"))),
        Hand(GbFan.ALL_TYPES, listOf(t("123m"), t("456p"), t("789s"), t("222w"), t("33d"))),
    )

    /** The fan in the order the book lists them, by what they are worth. */
    val VALUES: List<Int> = GbFan.entries.map { it.fan }.distinct()

    fun sections(rules: GbRules?): List<GuideSection> = listOfNotNull(
        rules?.let { GuideSection(GuideSection.TABLE, table(it)) },
        GuideSection("goal", texts("goal", 3)),
        GuideSection(
            "tiles",
            listOf(Text("$K.tiles.1")) +
                TileSuit.NUMBERED.map { Example("$K.tiles.${it.name.lowercase()}", null, listOf(it.tiles)) } +
                Example("$K.tiles.winds", "$K.tiles.winds.text", listOf(TileSuit.WINDS.tiles)) +
                Example("$K.tiles.dragons", "$K.tiles.dragons.text", listOf(TileSuit.DRAGONS.tiles)) +
                Example("$K.tiles.flowers", "$K.tiles.flowers.text", listOf(TileSuit.FLOWERS.tiles)),
        ),
        GuideSection(
            "sets",
            listOf(Text("$K.sets.1")) + SETS.map { (name, tiles) -> Example("$K.sets.$name", "$K.sets.$name.text", listOf(tiles)) } +
                Example(null, "$K.sets.example", HANDS.first().groups) + Text("$K.sets.2") +
                SHAPES.map { Example(it.fan.key, "$K.shape.${it.fan.name.lowercase()}", it.groups) },
        ),
        GuideSection("turn", texts("turn", 4)),
        GuideSection(
            "claims",
            texts("claims", 1..1) +
                listOf(
                    Example("$K.claims.chow", "$K.claims.chow.text", listOf(t("46m"), t("5m")), follows = true),
                    Example("$K.claims.pung", "$K.claims.pung.text", listOf(t("77p"), t("7p")), follows = true),
                    Example("$K.claims.kong", "$K.claims.kong.text", listOf(t("777p"), t("7p")), follows = true),
                ) +
                points("claims", 2..4),
        ),
        GuideSection(
            "fan",
            texts("fan", 1..3) + points("fan", 4..6) + Text("$K.fan.7") +
                HANDS.map { Example(it.fan.key, "$K.hand.${it.fan.name.lowercase()}", it.groups) },
        ),
        GuideSection("list", listOf(Text("$K.list.intro")) + VALUES.map { Point("$K.list.$it") }),
        GuideSection("points", texts("points", 1..1) + points("points", 2..3) + Text("$K.points.4")),
        GuideSection("game", texts("game", 3)),
        GuideSection("help", texts("help", 1..1) + points("help", 2..5)),
    )

    /** "This table": every field of [rules], in words. */
    private fun table(rules: GbRules): List<GuideBlock> {
        val t = "$K.table"
        return listOf(
            Point(if (rules.flowers) "$t.flowers.on" else "$t.flowers.off"),
            if (rules.hands == 16) Point("$t.full") else Point("$t.hands", listOf(rules.hands)),
            Point("$t.min", listOf(rules.minFan)),
        )
    }

    private fun texts(section: String, count: Int): List<GuideBlock> = texts(section, 1..count)

    private fun texts(section: String, numbers: IntRange): List<GuideBlock> = numbers.map { Text("$K.$section.$it") }

    private fun points(section: String, numbers: IntRange): List<GuideBlock> = numbers.map { Point("$K.$section.$it") }
}
