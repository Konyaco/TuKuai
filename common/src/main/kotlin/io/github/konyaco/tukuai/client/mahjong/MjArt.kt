package io.github.konyaco.tukuai.client.mahjong

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientConfig
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit

/** Mahjong tiles on screen: a face from the card atlas over a sliver of the tile's coloured back. */
object MjArt {
    /** Tile back colours as RGB, one for each card back a player may pick, in [CardArt.back] order. */
    private val BACKS = intArrayOf(0xB8402E, 0x2F62B0, 0x8B5A2B, 0x2E7D4F)

    /** The colour of the backs of tiles, following the card back this player picked. */
    fun backColor(): Int = BACKS[ClientConfig.cardBack.coerceIn(0, BACKS.size - 1)]

    /** Width and height of a tile face at [scale] 1, in GUI pixels. */
    const val WIDTH = CardArt.WIDTH
    const val HEIGHT = CardArt.HEIGHT

    /** How much of the tile's back shows under its face, at scale 1. */
    private const val DEPTH = 6

    /** Height of a tile drawn at [scale], with its back showing below. */
    fun height(scale: Float): Int = ((HEIGHT + DEPTH) * scale).toInt()

    fun width(scale: Float): Int = (WIDTH * scale).toInt()

    /**
     * A tile standing up with its face to the player, [x], [y] its top-left corner. A [dim]
     * tile is greyed, as for the void suit; [tint] colours its face, as ARGB.
     */
    fun draw(g: GuiGraphicsExtractor, tile: Tile?, x: Int, y: Int, scale: Float, dim: Boolean = false, tint: Int = 0) {
        val w = width(scale)
        val h = (HEIGHT * scale).toInt()
        val depth = height(scale) - h
        val back = 0xFF000000.toInt() or backColor()
        // The back peeks out below and the ivory layer just above it.
        g.fill(x, y + h, x + w, y + h + depth, back)
        g.fill(x, y + h, x + w, y + h + (depth + 1) / 2, 0xFFE8E0C8.toInt())
        CardArt.draw(g, if (tile == null) CardArt.TILE_BLANK else CardArt.tile(tile), x, y, scale)
        if (dim) g.fill(x, y, x + w, y + h + depth, 0x90303030.toInt())
        if (tint != 0) g.fill(x, y, x + w, y + h, tint)
    }

    /** The back of a tile, lying face down, for tiles whose faces are hidden. */
    fun drawBack(g: GuiGraphicsExtractor, x: Int, y: Int, scale: Float) {
        val w = width(scale)
        val h = height(scale)
        g.fill(x, y, x + w, y + h, 0xFF000000.toInt() or backColor())
        g.fill(x, y + h - (DEPTH * scale).toInt().coerceAtLeast(1), x + w, y + h, 0xFFE8E0C8.toInt())
    }

    /** A row of tiles side by side; returns the x just past the last. */
    fun row(g: GuiGraphicsExtractor, tiles: List<Tile>, x: Int, y: Int, scale: Float, gap: Int = 0): Int {
        var at = x
        for (tile in tiles) {
            draw(g, tile, at, y, scale)
            at += width(scale) + gap
        }
        return at
    }

    /** The language key for a suit's name. */
    fun suitKey(suit: TileSuit): String = "tukuai.mahjong.suit.${suit.name.lowercase()}"

    /** A suit's colour as RGB, for its name and the void badge. */
    fun suitColor(suit: TileSuit): Int = when (suit) {
        TileSuit.CHARACTERS -> 0xFF6A5A
        TileSuit.DOTS -> 0x6FA8FF
        TileSuit.BAMBOO -> 0x7FD67F
        else -> 0xFFFFFF
    }
}
