package io.github.konyaco.tukuai.client.mahjong

import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.GameUi
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.SeatPlace
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.engine.mahjong.guobiao.GbCodecs
import io.github.konyaco.tukuai.engine.mahjong.guobiao.GbEventKind
import io.github.konyaco.tukuai.engine.mahjong.guobiao.GbPhase
import io.github.konyaco.tukuai.engine.mahjong.guobiao.GbView
import io.github.konyaco.tukuai.engine.mahjong.guobiao.Guobiao
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/** Guobiao mahjong at the table: the shared mahjong scene, and the round and hand on the display. */
object GbUi : GameUi {
    override val gameId: String = Guobiao.id

    override fun decodeView(data: ByteArray): Any = GbCodecs.VIEW.decode(data)

    override fun createPanel(host: PanelHost): GamePanel = GbPanel(host)

    override fun scene(table: ClientTable, seats: List<SeatPlace>): List<TableCard> {
        val view = table.view as? GbView ?: return emptyList()
        return MjScene.scene(view, seats)
    }

    override fun displayLines(table: ClientTable): List<Component> {
        val view = table.view as? GbView ?: return emptyList()
        val round = Component.translatable("tukuai.guobiao.hand_title", MjText.wind(view.prevalentWind), view.handIndex % 4 + 1)
        return when (view.phase) {
            GbPhase.PLAY, GbPhase.CLAIM, GbPhase.ROB -> listOf(round, Component.translatable("tukuai.mahjong.wall", view.wallLeft))
            GbPhase.HAND_OVER, GbPhase.OVER -> listOf(
                round,
                view.result?.winner?.let { Component.translatable("tukuai.guobiao.won", MjUi.name(table, it)) } ?: Component.translatable("tukuai.mahjong.nobody_won"),
            )
        }
    }

    override fun turnSeat(table: ClientTable): Int? {
        val view = table.view as? GbView ?: return null
        return if (view.phase == GbPhase.PLAY) view.toAct else null
    }

    /** A click of tiles for a discard, a knock for a claim or kong, a fanfare for a win. */
    override fun onUpdate(previous: ClientTable?, table: ClientTable) {
        val view = table.view as? GbView ?: return
        val before = (previous?.view as? GbView) ?: return
        if (before.handIndex != view.handIndex) return
        val log = view.log
        if (before.log.size > log.size) return
        val pos = table.pos
        for (event in log.subList(before.log.size, log.size)) {
            when (event.kind) {
                GbEventKind.DISCARD -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_HAT, 0.7f, 1.6f)
                GbEventKind.CHOW, GbEventKind.PUNG -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BASEDRUM, 1f, 1.2f)
                GbEventKind.KONG -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BASEDRUM, 1f, 0.9f)
                GbEventKind.FLOWER -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_CHIME, 0.5f, 1.4f)
                GbEventKind.WIN -> ClientTables.playAt(pos, SoundEvents.PLAYER_LEVELUP, 0.7f, 1.2f)
                GbEventKind.EXHAUSTED -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BELL, 0.8f, 0.8f)
                GbEventKind.DEAL, GbEventKind.DRAW -> {}
            }
        }
    }
}
