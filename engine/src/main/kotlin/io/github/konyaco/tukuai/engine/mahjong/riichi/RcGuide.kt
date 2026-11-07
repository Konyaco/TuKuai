package io.github.konyaco.tukuai.engine.mahjong.riichi

import io.github.konyaco.tukuai.engine.core.GuideBlock
import io.github.konyaco.tukuai.engine.core.GuideBlock.Example
import io.github.konyaco.tukuai.engine.core.GuideBlock.Point
import io.github.konyaco.tukuai.engine.core.GuideBlock.Text
import io.github.konyaco.tukuai.engine.core.GuideSection
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit
import io.github.konyaco.tukuai.engine.mahjong.Tiles

/**
 * The riichi mahjong rule book. Each example hand is tiles in groups, won on a discard of its
 * last tile with every tile concealed, as south in the east round; the tests check it counts
 * the yaku the book names it for.
 */
object RcGuide {
    private const val K = "tukuai.guide.riichi"

    private fun t(text: String): List<Tile> = Tiles.parse(text)

    data class Hand(val yaku: RcYaku, val groups: List<List<Tile>>)

    val SETS: List<Pair<String, List<Tile>>> = listOf(
        "chow" to t("345m"),
        "pung" to t("777p"),
        "kong" to t("2222s"),
        "pair" to t("99p"),
    )

    val SHAPES: List<Hand> = listOf(
        Hand(RcYaku.CHIITOITSU, listOf(t("11m"), t("33m"), t("55p"), t("77p"), t("22s"), t("11w"), t("11d"))),
        Hand(RcYaku.KOKUSHI, listOf(t("19m"), t("19p"), t("19s"), t("1234w"), t("123d"), t("1m"))),
    )

    val HANDS: List<Hand> = listOf(
        Hand(RcYaku.TANYAO, listOf(t("55p"), t("234m"), t("567p"), t("345s"), t("678s"))),
        Hand(RcYaku.PINFU, listOf(t("66s"), t("123m"), t("456m"), t("789p"), t("234s"))),
        Hand(RcYaku.HAKU, listOf(t("11m"), t("234m"), t("567p"), t("789s"), t("333d"))),
        Hand(RcYaku.IIPEIKOU, listOf(t("55s"), t("234m"), t("234m"), t("567p"), t("789s"))),
        Hand(RcYaku.SANSHOKU, listOf(t("22s"), t("789m"), t("345m"), t("345p"), t("345s"))),
        Hand(RcYaku.ITTSU, listOf(t("55m"), t("234s"), t("123p"), t("456p"), t("789p"))),
        Hand(RcYaku.TOITOI, listOf(t("99m"), t("222m"), t("555p"), t("888s"), t("111w"))),
        Hand(RcYaku.HONITSU, listOf(t("88p"), t("234p"), t("666p"), t("333w"), t("111d"))),
    )

    /** The han the yaku list goes by, yakuman last. */
    val GROUPS: List<String> = listOf("1", "2", "3", "6", "yakuman")

    fun sections(rules: RcRules?): List<GuideSection> = listOfNotNull(
        rules?.let { GuideSection(GuideSection.TABLE, table(it)) },
        GuideSection("goal", texts("goal", 3)),
        GuideSection(
            "tiles",
            listOf(Text("$K.tiles.1")) +
                TileSuit.NUMBERED.map { Example("$K.tiles.${it.name.lowercase()}", null, listOf(it.tiles)) } +
                Example("$K.tiles.winds", "$K.tiles.winds.text", listOf(TileSuit.WINDS.tiles)) +
                Example("$K.tiles.dragons", "$K.tiles.dragons.text", listOf(TileSuit.DRAGONS.tiles)) +
                Example("$K.tiles.red", "$K.tiles.red.text", listOf(t("0m 0p 0s"))),
        ),
        GuideSection(
            "sets",
            listOf(Text("$K.sets.1")) + SETS.map { (name, tiles) -> Example("$K.sets.$name", "$K.sets.$name.text", listOf(tiles)) } +
                Example(null, "$K.sets.example", HANDS[1].groups) + Text("$K.sets.2") +
                SHAPES.map { Example(it.yaku.key, "$K.shape.${it.yaku.name.lowercase()}", it.groups) },
        ),
        GuideSection("turn", texts("turn", 4)),
        GuideSection(
            "claims",
            texts("claims", 1..1) +
                listOf(
                    Example("$K.claims.chow", "$K.claims.chow.text", listOf(t("46m"), t("5m")), follows = true),
                    Example("$K.claims.pung", "$K.claims.pung.text", listOf(t("77p"), t("7p")), follows = true),
                    Example("$K.claims.kong", "$K.claims.kong.text", listOf(t("777p"), t("7p")), follows = true),
                ) + points("claims", 2..5),
        ),
        GuideSection("yaku", texts("yaku", 1..2) + HANDS.map { Example(it.yaku.key, "$K.hand.${it.yaku.name.lowercase()}", it.groups) }),
        GuideSection("riichi", texts("riichi", 1..1) + points("riichi", 2..5)),
        GuideSection("furiten", texts("furiten", 1..1) + points("furiten", 2..4)),
        GuideSection("dora", texts("dora", 1..1) + listOf(Example(null, "$K.dora.example", listOf(t("5p"), t("6p")), follows = true)) + points("dora", 2..4)),
        GuideSection("list", listOf(Text("$K.list.intro")) + GROUPS.map { Point("$K.list.$it") } + Text("$K.list.open")),
        GuideSection("points", texts("points", 1..2) + points("points", 3..7)),
        GuideSection("game", texts("game", 1..1) + points("game", 2..5)),
        GuideSection("draws", texts("draws", 1..2) + points("draws", 3..6)),
        GuideSection("help", texts("help", 1..1) + points("help", 2..6)),
    )

    private fun table(rules: RcRules): List<GuideBlock> {
        val t = "$K.table"
        return listOf(
            Point(if (rules.south) "$t.south" else "$t.east"),
            Point(if (rules.reds) "$t.reds.on" else "$t.reds.off"),
            Point("$t.points", listOf(rules.startPoints)),
            Point(if (rules.bust) "$t.bust.on" else "$t.bust.off"),
            Point(if (rules.kuitan) "$t.kuitan.on" else "$t.kuitan.off"),
        )
    }

    private fun texts(section: String, count: Int): List<GuideBlock> = texts(section, 1..count)

    private fun texts(section: String, numbers: IntRange): List<GuideBlock> = numbers.map { Text("$K.$section.$it") }

    private fun points(section: String, numbers: IntRange): List<GuideBlock> = numbers.map { Point("$K.$section.$it") }
}
