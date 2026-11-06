package io.github.konyaco.tukuai.client.mahjong

import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.GameUi
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.SeatPlace
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScCodecs
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScEventKind
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScPhase
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScView
import io.github.konyaco.tukuai.engine.mahjong.sichuan.SichuanMahjong
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

object MjUi : GameUi {
    override val gameId: String = SichuanMahjong.id

    override fun decodeView(data: ByteArray): Any = ScCodecs.VIEW.decode(data)

    override fun createPanel(host: PanelHost): GamePanel = MjPanel(host)

    fun held(hand: List<Tile>, drawn: Tile?): List<Pair<Tile, Int>> = MjScene.held(hand, drawn)

    fun withCopies(tiles: List<Tile>): List<Pair<Tile, Int>> = MjScene.withCopies(tiles)

    fun ownKey(tile: Tile, copy: Int): Int = MjScene.ownKey(tile, copy)

    /** The table as at a real game, see [MjScene]; the player's own void suit is greyed. */
    override fun scene(table: ClientTable, seats: List<SeatPlace>): List<TableCard> {
        val view = table.view as? ScView ?: return emptyList()
        val void = view.myVoid
        return MjScene.scene(view, seats) { if (it.suit == void) VOID_TINT else -1 }
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

    private const val VOID_TINT = 0xFFB0B0B0.toInt()
    const val WIN_COLOR = MjScene.WIN_COLOR
    const val CLAIM_COLOR = MjScene.CLAIM_COLOR
}
