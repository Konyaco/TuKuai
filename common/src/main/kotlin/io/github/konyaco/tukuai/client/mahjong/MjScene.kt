package io.github.konyaco.tukuai.client.mahjong

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.SeatPlace
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.MjTableView
import io.github.konyaco.tukuai.engine.mahjong.Tile
import net.minecraft.core.Direction

/**
 * A mahjong table as at a real game, for every rule set: each hand standing at its owner's
 * edge with the melds and flowers lying to its right, the wall two tiles high in front of it,
 * shrinking as tiles are drawn, and the discards inside the square of the wall, in rows of six
 * in front of each player. A winner's hand lies face up, and once the hand is over every hand does.
 */
object MjScene {
    /**
     * A hand as its player holds it: in order, with the tile just drawn set apart at the end.
     * Each tile comes with its copy number, which tells two of the same kind apart.
     */
    fun held(hand: List<Tile>, drawn: Tile?): List<Pair<Tile, Int>> {
        val ordered = if (drawn != null && drawn in hand) hand.sorted().toMutableList().also { it.remove(drawn); it += drawn } else hand.sorted()
        return withCopies(ordered)
    }

    fun withCopies(tiles: List<Tile>): List<Pair<Tile, Int>> {
        val seen = HashMap<Tile, Int>()
        return tiles.map { it to (seen.merge(it, 1, Int::plus)!! - 1) }
    }

    /** The key of one of the player's own tiles on the table, see [TableCard.key]. */
    fun ownKey(tile: Tile, copy: Int): Int = OWN_KEYS + tile.code * 4 + copy

    /**
     * The cards for [view], with [seats] places round the table. [tint] may colour one of the
     * player's own tiles, as Sichuan mahjong greys the void suit.
     */
    fun scene(view: MjTableView, seats: List<SeatPlace>, tint: (Tile) -> Int = { -1 }): List<TableCard> {
        val count = view.handCounts.size
        if (seats.size < count) return emptyList()
        val back = MjArt.backColor()
        val cards = ArrayList<TableCard>()
        wall(cards, view, seats, back)
        for (seat in 0 until count) {
            val place = seats[seat]
            hand(cards, view, seat, place, back, tint)
            discards(cards, view, seat, place, back)
        }
        return cards
    }

    private class Frame(place: SeatPlace) {
        val side: Direction = place.side
        val upX = -side.stepX.toFloat()
        val upZ = -side.stepZ.toFloat()
        val rightX = -upZ
        val rightZ = upX
        val shiftX = place.shiftX
        val shiftZ = place.shiftZ

        fun x(distance: Float, offset: Float, shifted: Boolean = true) = side.stepX * distance + rightX * offset + if (shifted) shiftX else 0f

        fun z(distance: Float, offset: Float, shifted: Boolean = true) = side.stepZ * distance + rightZ * offset + if (shifted) shiftZ else 0f
    }

    private fun tileCard(
        f: Frame,
        distance: Float,
        offset: Float,
        sprite: Int,
        back: Int,
        key: Int,
        tilt: Float = 0f,
        shifted: Boolean = true,
        lift: Float = 0f,
        fromX: Float? = null,
        fromZ: Float? = null,
        mine: Boolean = false,
        outline: Int = 0,
        tint: Int = -1,
        backSprite: Int = -1,
        sideways: Boolean = false,
    ): TableCard {
        val x = f.x(distance, offset, shifted)
        val z = f.z(distance, offset, shifted)
        // A tile lying sideways has its top towards the player's left.
        val upX = if (sideways) -f.rightX else f.upX
        val upZ = if (sideways) -f.rightZ else f.upZ
        return TableCard(
            x, z, upX, upZ, sprite, TILE_SCALE, tilt = tilt, lift = lift, fromX = fromX ?: x, fromZ = fromZ ?: z,
            key = key, backSprite = backSprite, mine = mine, outline = outline, tint = tint, tile = true, tileBack = back,
        )
    }

    /**
     * The wall: a quarter of it in front of each player, from the dealer's round, drawn from
     * the front and kongs from the back. Longer walls stand their tiles a little closer.
     */
    private fun wall(cards: MutableList<TableCard>, view: MjTableView, seats: List<SeatPlace>, back: Int) {
        val ring = listOf(Direction.SOUTH, Direction.EAST, Direction.NORTH, Direction.WEST)
        val start = ring.indexOf(seats[view.dealer].side).coerceAtLeast(0)
        val perSide = (view.wallSize + 3) / 4
        val stacks = (perSide + 1) / 2
        val pitch = minOf(TILE_W, MAX_WALL / stacks)
        for (p in view.drawnFront until view.wallSize - view.drawnBack) {
            val side = ring[(start + p / perSide) % 4]
            val q = p % perSide
            val stack = q / 2
            // The top of each stack is drawn first; an odd tile at the end lies alone.
            val level = if (perSide % 2 == 1 && q == perSide - 1) 0 else 1 - q % 2
            // Each side runs along its player's edge, towards the next side round the table.
            val along = (stack - (stacks - 1) / 2f) * pitch
            val tx = side.stepZ.toFloat()
            val tz = -side.stepX.toFloat()
            val x = side.stepX * WALL_DISTANCE + tx * along
            val z = side.stepZ * WALL_DISTANCE + tz * along
            val face = view.wallFaceUp[p]
            cards += TableCard(
                x, z, -side.stepX.toFloat(), -side.stepZ.toFloat(), if (face != null) CardArt.tile(face) else CardArt.TILE_BLANK, TILE_SCALE,
                tilt = if (face != null) 0f else 180f, lift = level * TILE_T, key = WALL_KEYS + p, tile = true, tileBack = back,
            )
        }
    }

    /** One seat's hand, melds and flowers along its edge, the melds to the player's right. */
    private fun hand(cards: MutableList<TableCard>, view: MjTableView, seat: Seat, place: SeatPlace, back: Int, tint: (Tile) -> Int) {
        val f = Frame(place)
        val winTile = view.winningTile(seat)
        val shown = view.shown.getOrElse(seat) { emptyList() }
        val flat = winTile != null || view.handOver
        data class Slot(val sprite: Int, val key: Int, val apart: Boolean = false, val outline: Int = 0, val mine: Boolean = false, val tint: Int = -1, val backSprite: Int = -1)
        val slots = ArrayList<Slot>()
        when {
            seat == view.seat && !flat -> {
                for ((i, entry) in held(view.hand, view.drawn).withIndex()) {
                    val (tile, copy) = entry
                    val apart = view.drawn != null && i == view.hand.size - 1
                    slots += Slot(CardArt.tile(tile), ownKey(tile, copy), apart, mine = true, tint = tint(tile))
                }
            }
            shown.isNotEmpty() -> {
                // A winner's tile lies apart at the end, framed in gold.
                val tiles = if (winTile != null && winTile in shown) shown.toMutableList().also { it.remove(winTile); it += winTile } else shown.sorted()
                for ((i, entry) in withCopies(tiles).withIndex()) {
                    val (tile, copy) = entry
                    val last = winTile != null && i == tiles.size - 1
                    val key = if (seat == view.seat) ownKey(tile, copy) else HAND_KEYS + seat * 100 + i
                    slots += Slot(CardArt.tile(tile), key, last, if (last) WIN_COLOR else 0, backSprite = if (!flat) CardArt.tile(tile) else -1)
                }
            }
            else -> {
                val count = view.handCounts[seat]
                for (i in 0 until count) slots += Slot(CardArt.TILE_BLANK, HAND_KEYS + seat * 100 + i, apart = count % 3 == 2 && i == count - 1)
            }
        }
        val melds = view.melds[seat]
        val flowers = view.flowers.getOrElse(seat) { emptyList() }
        val meldLength = melds.sumOf { it.tiles.size } * TILE_W + melds.size * MELD_GAP
        val flowerLength = if (flowers.isEmpty()) 0f else MELD_GAP + flowers.size * FLOWER_W
        val handLength = slots.size * TILE_W + (if (slots.any { it.apart }) APART else 0f)
        var offset = -(handLength + meldLength + flowerLength) / 2 + TILE_W / 2
        for (slot in slots) {
            if (slot.apart) offset += APART
            // New tiles just appear in the hand, as if drawn; a whole hand flying in at once would pile up in the air.
            cards += tileCard(
                f, HAND_DISTANCE, offset, slot.sprite, back, slot.key, tilt = if (flat) 0f else HAND_TILT,
                mine = slot.mine, outline = slot.outline, tint = slot.tint, backSprite = slot.backSprite,
            )
            offset += TILE_W
        }
        val latest = view.latestMeld
        for ((m, meld) in melds.withIndex()) {
            offset += MELD_GAP
            for ((i, tile) in meld.tiles.withIndex()) {
                val hidden = meld.kind == MeldKind.CONCEALED_KONG && (i == 0 || i == 3)
                val outline = if (latest == seat to m) CLAIM_COLOR else 0
                cards += tileCard(
                    f, HAND_DISTANCE, offset, CardArt.tile(tile), back, MELD_KEYS + seat * 100 + m * 4 + i,
                    tilt = if (hidden) 180f else 0f, outline = outline,
                )
                offset += TILE_W
            }
        }
        if (flowers.isNotEmpty()) {
            offset += MELD_GAP
            for ((i, tile) in flowers.withIndex()) {
                cards += tileCard(f, HAND_DISTANCE + 0.02f, offset - (TILE_W - FLOWER_W) / 2, CardArt.tile(tile), back, FLOWER_KEYS + seat * 10 + i)
                offset += FLOWER_W
            }
        }
    }

    /** One seat's discards in rows of six, the first row nearest the display in the middle; a tile turned sideways takes more room. */
    private fun discards(cards: MutableList<TableCard>, view: MjTableView, seat: Seat, place: SeatPlace, back: Int) {
        val f = Frame(place)
        val tiles = view.discards[seat]
        val rows = (tiles.size + DISCARD_ROW - 1) / DISCARD_ROW
        val pitch = if (rows <= MAX_ROWS) TILE_H + ROW_GAP else (DISCARD_DEPTH - TILE_H) / (rows - 1)
        val turned = view.sideways(seat)
        for ((i, tile) in tiles.withIndex()) {
            val row = i / DISCARD_ROW
            val col = i % DISCARD_ROW
            // A sideways tile pushes the rest of its row along by the difference.
            val rowStart = row * DISCARD_ROW
            val sidewaysBefore = turned in rowStart until i
            val sideways = i == turned
            var along = (col - (DISCARD_ROW - 1) / 2f) * TILE_W
            if (sidewaysBefore) along += TILE_H - TILE_W
            if (sideways) along += (TILE_H - TILE_W) / 2
            val claimed = view.claimTile == tile && view.claimFrom == seat && i == tiles.size - 1 && view.claimingDiscard
            cards += tileCard(
                f, DISCARD_START + TILE_H / 2 + row * pitch, along, CardArt.tile(tile), back,
                DISCARD_KEYS + seat * 100 + i, shifted = false, lift = row * 0.001f,
                fromX = f.x(HAND_DISTANCE, 0f), fromZ = f.z(HAND_DISTANCE, 0f), outline = if (claimed) CLAIM_COLOR else 0,
                sideways = sideways,
            )
        }
    }

    /** Scale of a tile on the table: a bit over a third of a card's width. */
    const val TILE_SCALE = 0.36f

    // Distances are from the middle of the 3-block table; its cloth reaches 1.375 out.
    private const val TILE_W = 0.3f * TILE_SCALE
    private const val TILE_H = TILE_W * 44f / 32f
    private const val TILE_T = 0.2f * TILE_SCALE

    /** Flowers lie closer together than tiles in a hand. */
    private const val FLOWER_W = TILE_W * 0.8f

    /**
     * Distance of the middle of each side of the wall from the middle of the table: just in
     * front of the hand, leaving the square inside it for the discards.
     */
    private const val WALL_DISTANCE = 1.05f

    /** The longest a side of the wall may be, so its ends stay clear of the sides next to it. */
    private const val MAX_WALL = 1.95f

    /** The discards start just past the display in the middle, see [io.github.konyaco.tukuai.client.render.TableArt.DISPLAY_HALF_WIDTH]. */
    private const val DISCARD_START = 0.36f
    private const val DISCARD_ROW = 6
    private const val MAX_ROWS = 4
    private const val ROW_GAP = 0.004f

    /** How deep the discards may reach before they would touch the wall. */
    private const val DISCARD_DEPTH = WALL_DISTANCE - TILE_H / 2 - DISCARD_START - 0.012f

    /** The hand stands just behind the wall; flat melds and a winner's hand reach towards the edge. */
    private const val HAND_DISTANCE = 1.27f
    private const val HAND_TILT = 65f
    private const val APART = 0.03f
    private const val MELD_GAP = 0.02f

    const val WIN_COLOR = 0xFFFFC020.toInt()
    const val CLAIM_COLOR = 0xFFFF7040.toInt()

    private const val WALL_KEYS = 30000
    private const val HAND_KEYS = 40000
    private const val OWN_KEYS = 50000
    private const val DISCARD_KEYS = 60000
    private const val MELD_KEYS = 70000
    private const val FLOWER_KEYS = 80000
}
