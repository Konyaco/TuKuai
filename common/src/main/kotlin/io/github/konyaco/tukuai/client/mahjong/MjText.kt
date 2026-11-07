package io.github.konyaco.tukuai.client.mahjong

import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit
import net.minecraft.network.chat.Component

/** Names of mahjong tiles and winds, for every rule set. */
object MjText {
    /** A tile's name, such as 5万, 东风 or 梅; a red five says so. */
    fun tile(tile: Tile): Component = when (tile.suit) {
        TileSuit.WINDS -> Component.translatable("tukuai.mahjong.tile.wind.${tile.rank}")
        TileSuit.DRAGONS -> Component.translatable("tukuai.mahjong.tile.dragon.${tile.rank}")
        TileSuit.FLOWERS -> Component.translatable("tukuai.mahjong.tile.flower.${tile.rank}")
        else -> {
            val plain = Component.translatable("tukuai.mahjong.tile", tile.rank, Component.translatable(MjArt.suitKey(tile.suit)))
            if (tile.red) Component.translatable("tukuai.mahjong.tile.red", plain) else plain
        }
    }

    /** A wind by its number, east first: 东, 南, 西, 北. */
    fun wind(wind: Int): Component = Component.translatable("tukuai.mahjong.wind.${wind.coerceIn(0, 3)}")

    /** A run of three, such as 3-4-5万, by where it starts. */
    fun run(start: Tile): Component =
        Component.translatable("tukuai.mahjong.run", start.rank, start.rank + 1, start.rank + 2, Component.translatable(MjArt.suitKey(start.suit)))
}
