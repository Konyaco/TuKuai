package io.github.konyaco.tukuai.client.mahjong

import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.GameUi
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.SeatPlace
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcCodecs
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcEventKind
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcPhase
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcView
import io.github.konyaco.tukuai.engine.mahjong.riichi.Riichi
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/** Riichi mahjong at the table: the shared mahjong scene, with dora indicators turned up on the dead wall and riichi tiles lying sideways. */
object RcUi : GameUi {
    override val gameId: String = Riichi.id

    override fun decodeView(data: ByteArray): Any = RcCodecs.VIEW.decode(data)

    override fun createPanel(host: PanelHost): GamePanel = RcPanel(host)

    override fun scene(table: ClientTable, seats: List<SeatPlace>): List<TableCard> {
        val view = table.view as? RcView ?: return emptyList()
        return MjScene.scene(view, seats)
    }

    override fun displayLines(table: ClientTable): List<Component> {
        val view = table.view as? RcView ?: return emptyList()
        val round = Component.translatable("tukuai.riichi.hand_title", MjText.wind(view.round), view.kyoku + 1, view.honba)
        return when (view.phase) {
            RcPhase.PLAY, RcPhase.CLAIM, RcPhase.ROB -> listOf(round, Component.translatable("tukuai.riichi.live_wall", view.liveLeft.coerceAtLeast(0)))
            RcPhase.HAND_OVER, RcPhase.OVER -> listOf(round, Component.translatable("tukuai.mahjong.over"))
        }
    }

    override fun turnSeat(table: ClientTable): Int? {
        val view = table.view as? RcView ?: return null
        return if (view.phase == RcPhase.PLAY) view.toAct else null
    }

    /** A click of tiles for a discard, a knock for a claim or kong, a fanfare for a win. */
    override fun onUpdate(previous: ClientTable?, table: ClientTable) {
        val view = table.view as? RcView ?: return
        val before = (previous?.view as? RcView) ?: return
        if (before.round != view.round || before.kyoku != view.kyoku || before.honba != view.honba) return
        val log = view.log
        if (before.log.size > log.size) return
        val pos = table.pos
        for (event in log.subList(before.log.size, log.size)) {
            when (event.kind) {
                RcEventKind.DISCARD -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_HAT, 0.7f, 1.6f)
                RcEventKind.CHOW, RcEventKind.PUNG -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BASEDRUM, 1f, 1.2f)
                RcEventKind.KONG -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BASEDRUM, 1f, 0.9f)
                RcEventKind.RIICHI -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BELL, 0.8f, 1.5f)
                RcEventKind.DORA -> {}
                RcEventKind.WIN -> ClientTables.playAt(pos, SoundEvents.PLAYER_LEVELUP, 0.7f, 1.2f)
                RcEventKind.EXHAUSTED, RcEventKind.ABORT -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BELL, 0.8f, 0.8f)
                RcEventKind.DEAL, RcEventKind.DRAW -> {}
            }
        }
    }
}
