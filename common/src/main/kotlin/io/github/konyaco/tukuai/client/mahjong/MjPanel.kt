package io.github.konyaco.tukuai.client.mahjong

import com.mojang.blaze3d.platform.InputConstants
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.PanelText
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.mahjong.ClaimKind
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScAction
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScCodecs
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScEventKind
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScPhase
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScScoring
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScView
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScWin
import io.github.konyaco.tukuai.engine.mahjong.sichuan.SichuanMahjong
import io.github.konyaco.tukuai.engine.mahjong.sichuan.WinContext
import io.github.konyaco.tukuai.engine.mahjong.sichuan.bot.ScBot
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.network.chat.Component

/**
 * The Sichuan mahjong part of the table screen, on the shared mahjong layout of [MjPanelBase]:
 * passing three tiles on, picking the void suit, then play. The void suit is marked in red
 * along the bottom of your tiles, and must go first.
 */
class MjPanel(host: PanelHost) : MjPanelBase<ScView>(host) {
    private fun send(action: ScAction) = screen.send(TableOp.ACT, data = ScCodecs.ACTION.encode(action))

    override fun view(table: ClientTable): ScView? = table.view as? ScView

    override fun isMyTurn(view: ScView): Boolean = view.isMyTurn

    override fun discarding(view: ScView): Boolean = view.phase == ScPhase.PLAY && view.isMyTurn

    override fun canDiscard(view: ScView, tile: Tile): Boolean = tile in view.discardable

    override fun discard(view: ScView, tile: Tile) = send(ScAction.Discard(tile))

    override fun hintTile(view: ScView): Tile? = ScBot(BotLevel.NORMAL, 0).discard(view)

    override fun showingResult(view: ScView): Boolean = view.finished

    override fun markColor(view: ScView, tile: Tile): Int = if (tile.suit == view.myVoid) VOID_MARK else 0

    // ---- Passing three tiles on ----------------------------------------------------------------

    /** While passing tiles on, picks up to three of one suit; picking another suit starts again. */
    override fun pick(table: ClientTable, view: ScView, index: Int): Boolean {
        if (view.phase != ScPhase.SWAP) return super.pick(table, view, index)
        if (!asked(table, view)) return false
        val held = held(view)
        val tile = held.getOrNull(index)?.first ?: return false
        if (index in selected) {
            selected.remove(index)
        } else {
            if (selected.any { held[it].first.suit != tile.suit }) selected.clear()
            if (selected.size >= 3) selected.removeAt(0)
            selected += index
        }
        screen.refresh()
        return true
    }

    private fun hintSwap(view: ScView) {
        selected.clear()
        val held = held(view)
        for (tile in ScBot(BotLevel.NORMAL, 0).swapTiles(view.hand)) {
            held.indices.firstOrNull { held[it].first == tile && it !in selected }?.let { selected += it }
        }
        screen.refresh()
    }

    override fun keyPressed(table: ClientTable, key: Int): Boolean {
        val view = view(table)
        if (view != null && view.phase == ScPhase.SWAP && asked(table, view)) {
            when (key) {
                InputConstants.KEY_H -> hintSwap(view)
                InputConstants.KEY_SPACE, InputConstants.KEY_RETURN -> {
                    val picked = pickedTiles(view)
                    if (SichuanMahjong.swapProblem(view.hand, picked) == null) send(ScAction.Swap(picked))
                }
                else -> return false
            }
            return true
        }
        return super.keyPressed(table, key)
    }

    override fun onEnter(view: ScView) {
        if (view.phase == ScPhase.CLAIM || view.phase == ScPhase.ROB) send(ScAction.Pass)
    }

    // ---- Buttons and words ---------------------------------------------------------------------

    override fun buttons(table: ClientTable, view: ScView): List<Btn> {
        val out = ArrayList<Btn>()
        when (view.phase) {
            ScPhase.SWAP -> {
                val picked = pickedTiles(view)
                out += Btn(Component.translatable("tukuai.ddz.hint")) { hintSwap(view) }
                out += Btn(Component.translatable("tukuai.mahjong.pass_on"), SichuanMahjong.swapProblem(view.hand, picked) == null) { send(ScAction.Swap(picked)) }
            }
            ScPhase.VOID -> {
                val suggestion = ScBot(BotLevel.NORMAL, 0).voidSuit(view.hand)
                for (suit in SichuanMahjong.SUITS) {
                    val label = Component.translatable(MjArt.suitKey(suit)).copy().append(" (${view.hand.count { it.suit == suit }})")
                    if (suit == suggestion) label.withColor(TableScreen.YELLOW and 0xFFFFFF)
                    out += Btn(label) { send(ScAction.ChooseVoid(suit)) }
                }
            }
            ScPhase.PLAY -> {
                if (view.canWin) out += Btn(Component.translatable("tukuai.mahjong.win")) { send(ScAction.Win) }
                for (tile in view.kongs) out += Btn(Component.translatable("tukuai.mahjong.kong_tile", MjText.tile(tile))) { send(ScAction.Kong(tile)) }
                out += Btn(Component.translatable("tukuai.ddz.hint")) { hint(view) }
                val pick = pickedTiles(view).singleOrNull()
                out += Btn(Component.translatable("tukuai.mahjong.discard"), pick != null && pick in view.discardable) { pick?.let { send(ScAction.Discard(it)) } }
            }
            ScPhase.CLAIM, ScPhase.ROB -> {
                val tile = view.claimTile ?: return out
                if (ClaimKind.WIN in view.options) out += Btn(Component.translatable("tukuai.mahjong.win")) { send(ScAction.Win) }
                if (ClaimKind.KONG in view.options) out += Btn(Component.translatable("tukuai.mahjong.kong")) { send(ScAction.Kong(tile)) }
                if (ClaimKind.PUNG in view.options) out += Btn(Component.translatable("tukuai.mahjong.pung")) { send(ScAction.Pung) }
                out += Btn(Component.translatable("tukuai.ddz.pass")) { send(ScAction.Pass) }
            }
            ScPhase.OVER -> {}
        }
        return out
    }

    private fun waitingFor(table: ClientTable, view: ScView): Component? {
        val waiting = view.picked.withIndex().filter { !it.value }.map { it.index }
        if (waiting.isEmpty()) return null
        return Component.translatable("tukuai.mahjong.waiting_for", MjUi.name(table, waiting.first()))
    }

    override fun prompt(table: ClientTable, view: ScView, mine: Boolean): Pair<Component?, Component?> = when (view.phase) {
        ScPhase.SWAP -> null to (if (mine) Component.translatable("tukuai.mahjong.note.swap") else waitingFor(table, view))
        ScPhase.VOID -> view.swapDirection?.let { Component.translatable("tukuai.mahjong.swap.${it.name.lowercase()}") } to
            (if (mine) Component.translatable("tukuai.mahjong.note.void") else waitingFor(table, view))
        ScPhase.PLAY -> null to when {
            !mine -> null
            view.canWin -> Component.translatable("tukuai.mahjong.note.can_win")
            view.discardable.any { it.suit == view.myVoid } -> Component.translatable("tukuai.mahjong.note.void_first", Component.translatable(MjArt.suitKey(view.myVoid!!)))
            else -> Component.translatable("tukuai.mahjong.note.discard")
        }
        ScPhase.CLAIM, ScPhase.ROB -> {
            val tile = view.claimTile
            val from = view.claimFrom
            if (tile != null && from != null) {
                val key = if (view.phase == ScPhase.ROB) "tukuai.mahjong.note.robbing" else "tukuai.mahjong.note.discarded"
                Component.translatable(key, MjUi.name(table, from), MjText.tile(tile)) to (if (mine) Component.translatable("tukuai.mahjong.note.claim") else null)
            } else null to null
        }
        ScPhase.OVER -> null to null
    }

    /** A winner's place, the void suit once shown, a tick for a pick made, and the dealer. */
    override fun badge(view: ScView, seat: Seat): Component? {
        val parts = ArrayList<Component>()
        view.winOf(seat)?.let { parts += Component.translatable("tukuai.mahjong.won_place", view.wins.indexOf(it) + 1) }
        view.voids[seat]?.let { parts += Component.translatable("tukuai.mahjong.void_badge", Component.translatable(MjArt.suitKey(it))) }
        if ((view.phase == ScPhase.SWAP || view.phase == ScPhase.VOID) && view.picked[seat] && seat != view.seat) parts += Component.literal("✔")
        if (seat == view.dealer) parts += Component.translatable("tukuai.mahjong.dealer_short")
        if (parts.isEmpty()) return null
        val out = Component.empty()
        parts.forEachIndexed { i, p -> if (i > 0) out.append(" "); out.append(p) }
        return out
    }

    override fun score(view: ScView, seat: Seat): String = view.scores[seat].let { if (it > 0) "+$it" else "$it" }

    override fun info(table: ClientTable, view: ScView): List<Component> = listOfNotNull(
        when (view.phase) {
            ScPhase.SWAP -> Component.translatable("tukuai.mahjong.swapping")
            ScPhase.VOID -> Component.translatable("tukuai.mahjong.picking_void")
            else -> Component.translatable("tukuai.preset.mahjong.blood")
        },
        Component.translatable("tukuai.mahjong.wall", view.wallLeft),
        if (view.wins.isNotEmpty()) Component.translatable("tukuai.mahjong.winners", view.wins.size) else null,
    )

    override fun waitHints(view: ScView, concealed: List<Tile>): List<WaitHint> =
        ScScoring.waits(concealed, view.myMelds, view.myVoid).map { tile ->
            val fan = ScScoring.total(view.rules, ScScoring.fans(view.rules, concealed + tile, view.myMelds, WinContext.PLAIN))
            WaitHint(tile, view.unseen(tile), true, Component.translatable("tukuai.guobiao.fan_count", fan))
        }

    // ---- The result ----------------------------------------------------------------------------

    private fun resultBottom(): Int = minOf(height - 4, RESULT_Y + 24 + 4 * RESULT_ROW + 30)

    override fun resultButtons(table: ClientTable, view: ScView) {
        if (table.snapshot.status == TableStatus.FINISHED) screen.finishButtons(table, width / 2, resultBottom() - 24)
    }

    private fun fanText(win: ScWin): Component {
        val text = Component.empty()
        text.append(Component.translatable(if (win.selfDrawn) "tukuai.mahjong.self_drawn" else "tukuai.mahjong.on_discard"))
        for ((fan, n) in win.fans.groupingBy { it }.eachCount()) {
            text.append(" · ").append(Component.translatable("tukuai.mahjong.fan.${fan.name.lowercase()}"))
            if (n > 1) text.append(" ×$n")
        }
        if (win.fans.isEmpty()) text.append(" · ").append(Component.translatable("tukuai.mahjong.fan.plain"))
        text.append(" ").append(Component.translatable("tukuai.mahjong.fan_count", win.fan))
        return text
    }

    /** Each player: how the hand went for them, their tiles, and the points they won or lost. */
    override fun renderResult(g: GuiGraphicsExtractor, table: ClientTable, view: ScView) {
        val w = minOf(380, width - 16)
        val x = width / 2 - w / 2
        val top = RESULT_Y
        g.fill(x, top, x + w, resultBottom(), 0xF0101010.toInt())
        val title = if (view.wins.isEmpty()) Component.translatable("tukuai.mahjong.nobody_won") else Component.translatable("tukuai.mahjong.over")
        g.centeredText(font, title, width / 2, top + 6, TableScreen.YELLOW)
        val me = table.snapshot.you
        for (seat in 0 until SichuanMahjong.SEATS) {
            val y = top + 22 + seat * RESULT_ROW
            g.text(font, font.plainSubstrByWidth(PanelText.seatLabel(table, seat).string, 68), x + 8, y, if (seat == me) TableScreen.YELLOW else TableScreen.WHITE, false)
            val win = view.winOf(seat)
            val how: Component = when {
                win != null -> fanText(win)
                view.log.any { it.kind == ScEventKind.PIG && it.seat == seat } -> Component.translatable("tukuai.mahjong.pig")
                view.log.any { it.kind == ScEventKind.NOT_READY && it.seat == seat } -> Component.translatable("tukuai.mahjong.not_ready")
                else -> Component.translatable("tukuai.mahjong.ready_or_out")
            }
            font.split(how, w - 130).firstOrNull()?.let { g.text(font, it, x + 80, y, TableScreen.GRAY, false) }
            val points = view.scores[seat]
            val text = Component.literal(if (points > 0) "+$points" else points.toString())
            g.text(font, text, x + w - 8 - font.width(text), y, if (points >= 0) TableScreen.GREEN else TableScreen.RED, false)
            // The hand as it ended, the winning tile last and lit, then the melds.
            val tiles = view.shown[seat].let { t -> if (win != null) t.toMutableList().also { it.remove(win.tile); it += win.tile } else t.sorted() }
            var tx = x + 80
            tiles.forEachIndexed { i, tile ->
                MjArt.draw(g, tile, tx, y + 10, SMALL_SCALE, tint = if (win != null && i == tiles.size - 1) 0x50FFC020 else 0)
                tx += MjArt.width(SMALL_SCALE)
            }
            renderMelds(g, view.melds[seat], tx + 4, y + 10, SMALL_SCALE)
        }
    }

    private companion object {
        const val VOID_MARK = 0xFFE04040.toInt()
        const val RESULT_Y = 62
        const val RESULT_ROW = 28
    }
}
