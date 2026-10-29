package io.github.konyaco.tukuai.client.mahjong

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.GameUi
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.SeatPlace
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScCodecs
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScEventKind
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScPhase
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScView
import io.github.konyaco.tukuai.engine.mahjong.sichuan.SichuanMahjong
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

object MjUi : GameUi {
    override val gameId: String = SichuanMahjong.id

    override fun decodeView(data: ByteArray): Any = ScCodecs.VIEW.decode(data)

    override fun createPanel(host: PanelHost): GamePanel = MjPanel(host)

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
    fun ownKey(tile: Tile, copy: Int): Int = OWN_KEYS + tile.id * 4 + copy

    /**
     * The table as at a real game: the wall, two tiles high, in a square round the display
     * that shrinks as tiles are drawn; each hand standing at its owner's edge with the melds
     * lying to its right; and the discards in rows of six in front of each player. A winner's
     * hand lies face up, and once the hand is over every hand does.
     */
    override fun scene(table: ClientTable, seats: List<SeatPlace>): List<TableCard> {
        val view = table.view as? ScView ?: return emptyList()
        if (seats.size < SichuanMahjong.SEATS) return emptyList()
        val back = MjArt.backColor()
        val cards = ArrayList<TableCard>()
        wall(cards, view, seats, back)
        for (seat in 0 until SichuanMahjong.SEATS) {
            val place = seats[seat]
            hand(cards, view, seat, place, back)
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
    ): TableCard {
        val x = f.x(distance, offset, shifted)
        val z = f.z(distance, offset, shifted)
        return TableCard(
            x, z, f.upX, f.upZ, sprite, TILE_SCALE, tilt = tilt, lift = lift, fromX = fromX ?: x, fromZ = fromZ ?: z,
            key = key, backSprite = backSprite, mine = mine, outline = outline, tint = tint, tile = true, tileBack = back,
        )
    }

    /** The wall: 27 tiles in front of each player, from the dealer's round, drawn from the front and kongs from the back. */
    private fun wall(cards: MutableList<TableCard>, view: ScView, seats: List<SeatPlace>, back: Int) {
        val ring = listOf(Direction.SOUTH, Direction.EAST, Direction.NORTH, Direction.WEST)
        val start = ring.indexOf(seats[view.dealer].side).coerceAtLeast(0)
        val inner = WALL_DISTANCE - TILE_H / 2
        for (p in view.drawnFront until view.wallSize - view.drawnBack) {
            val side = ring[(start + p / PER_SIDE) % 4]
            val q = p % PER_SIDE
            val stack = q / 2
            // The top of each stack is drawn first; an odd tile at the end lies alone.
            val level = if (q == PER_SIDE - 1) 0 else 1 - q % 2
            // Each side runs on to the corner of the next, in a pinwheel.
            val along = -inner + TILE_W * (stack + 0.5f)
            val tx = side.stepZ.toFloat()
            val tz = -side.stepX.toFloat()
            val x = side.stepX * WALL_DISTANCE + tx * along
            val z = side.stepZ * WALL_DISTANCE + tz * along
            cards += TableCard(
                x, z, -side.stepX.toFloat(), -side.stepZ.toFloat(), CardArt.TILE_BLANK, TILE_SCALE,
                tilt = 180f, lift = level * TILE_T, key = WALL_KEYS + p, tile = true, tileBack = back,
            )
        }
    }

    /** One seat's hand and melds along its edge, the melds to the player's right. */
    private fun hand(cards: MutableList<TableCard>, view: ScView, seat: Seat, place: SeatPlace, back: Int) {
        val f = Frame(place)
        val win = view.winOf(seat)
        val shown = view.shown[seat]
        val flat = win != null || view.finished
        // What lies along the edge, left to right: (sprite, key, set apart, outline).
        data class Slot(val sprite: Int, val key: Int, val apart: Boolean = false, val outline: Int = 0, val mine: Boolean = false, val tint: Int = -1, val backSprite: Int = -1)
        val slots = ArrayList<Slot>()
        when {
            seat == view.seat && !flat -> {
                val void = view.myVoid
                for ((i, entry) in held(view.hand, view.drawn).withIndex()) {
                    val (tile, copy) = entry
                    val apart = view.drawn != null && i == view.hand.size - 1
                    slots += Slot(CardArt.tile(tile), ownKey(tile, copy), apart, mine = true, tint = if (tile.suit == void) VOID_TINT else -1)
                }
            }
            shown.isNotEmpty() -> {
                // A winner's tile lies apart at the end, framed in gold.
                val tiles = if (win != null) shown.toMutableList().also { it.remove(win.tile); it += win.tile } else shown.sorted()
                for ((i, entry) in withCopies(tiles).withIndex()) {
                    val (tile, copy) = entry
                    val last = win != null && i == tiles.size - 1
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
        val meldLength = melds.sumOf { it.tiles.size } * TILE_W + melds.size * MELD_GAP
        val handLength = slots.size * TILE_W + (if (slots.any { it.apart }) APART else 0f)
        var offset = -(handLength + meldLength) / 2 + TILE_W / 2
        for (slot in slots) {
            if (slot.apart) offset += APART
            // New tiles just appear in the hand, as if drawn; a whole hand flying in at once would pile up in the air.
            cards += tileCard(
                f, HAND_DISTANCE, offset, slot.sprite, back, slot.key, tilt = if (flat) 0f else HAND_TILT,
                mine = slot.mine, outline = slot.outline, tint = slot.tint, backSprite = slot.backSprite,
            )
            offset += TILE_W
        }
        for ((m, meld) in melds.withIndex()) {
            offset += MELD_GAP
            for ((i, tile) in meld.tiles.withIndex()) {
                val hidden = meld.kind == MeldKind.CONCEALED_KONG && (i == 0 || i == 3)
                val outline = if (isLatestMeld(view, seat, meld, m)) CLAIM_COLOR else 0
                cards += tileCard(
                    f, HAND_DISTANCE, offset, CardArt.tile(tile), back, MELD_KEYS + seat * 100 + m * 4 + i,
                    tilt = if (hidden) 180f else 0f, outline = outline,
                )
                offset += TILE_W
            }
        }
    }

    /** Whether [meld] was the last thing that happened at the table, to frame it. */
    private fun isLatestMeld(view: ScView, seat: Seat, meld: Meld, index: Int): Boolean {
        val last = view.log.lastOrNull { it.kind != ScEventKind.DRAW } ?: return false
        return (last.kind == ScEventKind.PUNG || last.kind == ScEventKind.KONG) && last.seat == seat && last.tile == meld.tile &&
            index == view.melds[seat].size - 1
    }

    /** One seat's discards in rows of six, the first row nearest the wall. */
    private fun discards(cards: MutableList<TableCard>, view: ScView, seat: Seat, place: SeatPlace, back: Int) {
        val f = Frame(place)
        val tiles = view.discards[seat]
        val rows = (tiles.size + DISCARD_ROW - 1) / DISCARD_ROW
        val pitch = if (rows <= MAX_ROWS) TILE_H + ROW_GAP else (DISCARD_DEPTH - TILE_H) / (rows - 1)
        for ((i, tile) in tiles.withIndex()) {
            val row = i / DISCARD_ROW
            val col = i % DISCARD_ROW
            val claimed = view.claimTile == tile && view.claimFrom == seat && i == tiles.size - 1 && view.phase == ScPhase.CLAIM
            cards += tileCard(
                f, DISCARD_START + TILE_H / 2 + row * pitch, (col - (DISCARD_ROW - 1) / 2f) * TILE_W, CardArt.tile(tile), back,
                DISCARD_KEYS + seat * 100 + i, shifted = false, lift = row * 0.001f,
                fromX = f.x(HAND_DISTANCE, 0f), fromZ = f.z(HAND_DISTANCE, 0f), outline = if (claimed) CLAIM_COLOR else 0,
            )
        }
    }

    fun name(table: ClientTable, seat: Seat): Component = TableScreen.seatName(table.snapshot.seats.getOrNull(seat)?.occupant, seat)

    override fun displayLines(table: ClientTable): List<Component> {
        val view = table.view as? ScView ?: return emptyList()
        val wall = Component.translatable("tukuai.mahjong.wall", view.wallLeft)
        return when (view.phase) {
            ScPhase.SWAP -> listOf(Component.translatable("tukuai.mahjong.swapping"), wall)
            ScPhase.VOID -> listOf(Component.translatable("tukuai.mahjong.picking_void"), wall)
            ScPhase.PLAY, ScPhase.CLAIM, ScPhase.ROB -> listOf(wall, Component.translatable("tukuai.table.turn", name(table, view.toAct ?: view.turn)))
            ScPhase.OVER -> listOf(
                Component.translatable("tukuai.mahjong.over"),
                if (view.wins.isEmpty()) Component.translatable("tukuai.mahjong.nobody_won") else Component.translatable("tukuai.mahjong.winners", view.wins.size),
            )
        }
    }

    override fun turnSeat(table: ClientTable): Int? {
        val view = table.view as? ScView ?: return null
        return if (view.phase == ScPhase.PLAY || view.phase == ScPhase.CLAIM || view.phase == ScPhase.ROB) view.toAct else null
    }

    /** A click of tiles for a discard, a knock for a pung or kong, a fanfare for a win. */
    override fun onUpdate(previous: ClientTable?, table: ClientTable) {
        val view = table.view as? ScView ?: return
        val before = (previous?.view as? ScView)?.log ?: return
        val log = view.log
        if (before.size > log.size || log.subList(0, before.size).map { it.kind } != before.map { it.kind }) return
        val pos = table.pos
        for (event in log.subList(before.size, log.size)) {
            when (event.kind) {
                ScEventKind.DISCARD -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_HAT, 0.7f, 1.6f)
                ScEventKind.PUNG -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BASEDRUM, 1f, 1.2f)
                ScEventKind.KONG -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BASEDRUM, 1f, 0.9f)
                ScEventKind.WIN -> ClientTables.playAt(pos, SoundEvents.PLAYER_LEVELUP, 0.7f, 1.2f)
                ScEventKind.SWAP, ScEventKind.VOID -> ClientTables.playAt(pos, SoundEvents.ITEM_PICKUP, 0.6f, 1f)
                ScEventKind.PIG, ScEventKind.NOT_READY, ScEventKind.EXHAUSTED -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BELL, 0.8f, 0.8f)
                ScEventKind.DEAL, ScEventKind.DRAW -> {}
            }
        }
    }

    /** Scale of a tile on the table: a quarter of a card's width. */
    const val TILE_SCALE = 0.25f

    // Distances are from the middle of the 3-block table; its cloth reaches 1.375 out.
    private const val TILE_W = 0.3f * TILE_SCALE
    private const val TILE_H = TILE_W * 44f / 32f
    private const val TILE_T = 0.2f * TILE_SCALE

    /** Distance of the middle of the wall from the middle of the table: 14 stacks make a side of a square. */
    private const val WALL_DISTANCE = 14 * TILE_W / 2
    private const val PER_SIDE = 27
    private const val DISCARD_START = WALL_DISTANCE + TILE_H / 2 + 0.015f
    private const val DISCARD_ROW = 6
    private const val MAX_ROWS = 4
    private const val ROW_GAP = 0.004f

    /** How deep the discards may reach before they would touch the hand. */
    private const val DISCARD_DEPTH = 0.43f
    private const val HAND_DISTANCE = 1.12f
    private const val HAND_TILT = 65f
    private const val APART = 0.03f
    private const val MELD_GAP = 0.02f

    private const val VOID_TINT = 0xFFB0B0B0.toInt()
    const val WIN_COLOR = 0xFFFFC020.toInt()
    const val CLAIM_COLOR = 0xFFFF7040.toInt()

    private const val WALL_KEYS = 30000
    private const val HAND_KEYS = 40000
    private const val OWN_KEYS = 50000
    private const val DISCARD_KEYS = 60000
    private const val MELD_KEYS = 70000
}
