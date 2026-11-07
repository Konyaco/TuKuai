package io.github.konyaco.tukuai.client.mahjong

import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.PanelText
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcAction
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcCodecs
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcHandResult
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcLimit
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcPhase
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcScore
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcScoring
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcView
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcWinRecord
import io.github.konyaco.tukuai.engine.mahjong.riichi.Riichi
import io.github.konyaco.tukuai.engine.mahjong.riichi.bot.RcBot
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.network.chat.Component

/**
 * The riichi mahjong part of the table screen, on the shared mahjong layout of [MjPanelBase].
 * Riichi is declared by pressing its button and then picking the tile to discard sideways;
 * the tiles that would leave the hand ready are the only ones that may be picked then.
 */
class RcPanel(host: PanelHost) : MjPanelBase<RcView>(host) {
    /** The player pressed riichi and is picking the tile to declare it with. */
    private var riichiMode = false
    private var modeStep = -1

    private fun send(action: RcAction) {
        riichiMode = false
        screen.send(TableOp.ACT, data = RcCodecs.ACTION.encode(action))
    }

    override fun view(table: ClientTable): RcView? = (table.view as? RcView)?.also {
        if (table.snapshot.step != modeStep) {
            modeStep = table.snapshot.step
            riichiMode = false
        }
    }

    override fun isMyTurn(view: RcView): Boolean = view.isMyTurn

    override fun discarding(view: RcView): Boolean = view.phase == RcPhase.PLAY && view.isMyTurn

    override fun canDiscard(view: RcView, tile: Tile): Boolean = if (riichiMode) tile in view.riichiTiles else tile in view.discardable

    override fun discard(view: RcView, tile: Tile) = send(if (riichiMode && tile in view.riichiTiles) RcAction.Riichi(tile) else RcAction.Discard(tile))

    override fun hintTile(view: RcView): Tile? = RcBot(BotLevel.NORMAL, 0).discard(view)

    override fun showingResult(view: RcView): Boolean = view.handOver

    override fun onEnter(view: RcView) {
        if (view.phase == RcPhase.CLAIM || view.phase == RcPhase.ROB) send(RcAction.Pass)
    }

    override fun cannotWin(): Component = Component.translatable("tukuai.riichi.no_yaku_waits")

    /** "3 番 40 符" or "满贯", what a score is worth in words. */
    private fun worth(han: Int, fu: Int, yakuman: Int, limit: RcLimit): Component = when (limit) {
        RcLimit.NONE -> Component.translatable("tukuai.riichi.han_fu", han, fu)
        RcLimit.YAKUMAN -> if (yakuman > 1) Component.translatable("tukuai.riichi.multiple_yakuman", yakuman) else Component.translatable("tukuai.riichi.limit.yakuman")
        else -> Component.translatable("tukuai.riichi.limit.${limit.name.lowercase()}")
    }

    private fun worth(s: RcScore): Component = worth(s.han, s.fu, s.yakuman, s.limit)

    private fun claimScore(view: RcView): RcScore? {
        val tile = view.claimTile ?: return null
        val win = view.winOn(tile, view.hand, tsumo = false)
        return RcScoring.score(if (view.phase == RcPhase.ROB) win.copy(chankan = true) else win)?.takeIf { it.yaku.isNotEmpty() }
    }

    override fun buttons(table: ClientTable, view: RcView): List<Btn> {
        val out = ArrayList<Btn>()
        when (view.phase) {
            RcPhase.PLAY -> {
                view.tsumo?.let { out += Btn(Component.translatable("tukuai.riichi.tsumo_with", worth(it))) { send(RcAction.Win) } }
                if (view.riichiTiles.isNotEmpty()) {
                    val text = Component.translatable(if (riichiMode) "tukuai.riichi.riichi_cancel" else "tukuai.riichi.riichi")
                    out += Btn(text) {
                        riichiMode = !riichiMode
                        selected.clear()
                        screen.refresh()
                    }
                }
                for (tile in view.kongs) out += Btn(Component.translatable("tukuai.mahjong.kong_tile", MjText.tile(tile))) { send(RcAction.Kong(tile)) }
                if (view.canAbort) out += Btn(Component.translatable("tukuai.riichi.abort")) { send(RcAction.Abort) }
                out += Btn(Component.translatable("tukuai.ddz.hint")) { hint(view) }
                val pick = pickedTiles(view).singleOrNull()
                out += Btn(Component.translatable(if (riichiMode) "tukuai.riichi.riichi_discard" else "tukuai.mahjong.discard"), pick != null && canDiscard(view, pick)) {
                    pick?.let { discard(view, it) }
                }
            }
            RcPhase.CLAIM, RcPhase.ROB -> {
                val tile = view.claimTile ?: return out
                val o = view.options
                if (o.win) {
                    val score = claimScore(view)
                    out += Btn(if (score != null) Component.translatable("tukuai.riichi.ron_with", worth(score)) else Component.translatable("tukuai.riichi.ron")) { send(RcAction.Win) }
                }
                if (o.kong) out += Btn(Component.translatable("tukuai.mahjong.kong")) { send(RcAction.Kong(tile)) }
                if (o.pung) out += Btn(Component.translatable("tukuai.mahjong.pung")) { send(RcAction.Pung) }
                for (start in o.chows) out += Btn(Component.translatable("tukuai.mahjong.chow_run", MjText.run(start))) { send(RcAction.Chow(start)) }
                out += Btn(Component.translatable("tukuai.ddz.pass")) { send(RcAction.Pass) }
            }
            else -> {}
        }
        return out
    }

    override fun prompt(table: ClientTable, view: RcView, mine: Boolean): Pair<Component?, Component?> = when (view.phase) {
        RcPhase.PLAY -> {
            val me = view.seat
            null to when {
                !mine -> null
                view.tsumo != null -> Component.translatable("tukuai.riichi.note.can_tsumo", worth(view.tsumo!!))
                riichiMode -> Component.translatable("tukuai.riichi.note.pick_riichi")
                me != null && view.riichi[me] -> Component.translatable("tukuai.riichi.note.after_riichi")
                else -> Component.translatable("tukuai.mahjong.note.discard")
            }
        }
        RcPhase.CLAIM, RcPhase.ROB -> {
            val tile = view.claimTile
            val from = view.claimFrom
            if (tile != null && from != null) {
                val key = if (view.phase == RcPhase.ROB) "tukuai.mahjong.note.robbing" else "tukuai.mahjong.note.discarded"
                val line = Component.translatable(key, MjUi.name(table, from), MjText.tile(tile))
                val ask = when {
                    !mine -> null
                    view.options.win -> claimScore(view)?.let { Component.translatable("tukuai.riichi.note.can_ron", worth(it)) } ?: Component.translatable("tukuai.mahjong.note.claim")
                    else -> Component.translatable("tukuai.mahjong.note.claim")
                }
                line to ask
            } else null to null
        }
        else -> null to null
    }

    override fun badge(view: RcView, seat: Seat): Component? {
        val text = MjText.wind(view.seatWind(seat)).copy()
        if (seat == view.dealer) text.append(Component.translatable("tukuai.mahjong.dealer_badge"))
        if (view.riichi.getOrElse(seat) { false }) text.append(" ").append(Component.translatable("tukuai.riichi.riichi"))
        return text
    }

    override fun score(view: RcView, seat: Seat): String = "${view.points[seat]}"

    override fun info(table: ClientTable, view: RcView): List<Component> = listOf(
        Component.translatable("tukuai.riichi.live_wall", view.liveLeft.coerceAtLeast(0)),
        Component.translatable("tukuai.riichi.round", MjText.wind(view.round), view.kyoku + 1, view.honba, view.sticks),
    )

    override fun waitHints(view: RcView, concealed: List<Tile>): List<WaitHint> {
        val me = view.seat ?: return emptyList()
        val waits = RcScoring.waits(concealed, view.myMelds)
        val furiten = waits.any { w -> view.discarded[me].any { it.id == w.id } }
        return waits.map { tile ->
            val ron = RcScoring.score(view.winOn(tile, concealed, tsumo = false))?.takeIf { it.yaku.isNotEmpty() && !furiten }
            val tsumo = RcScoring.score(view.winOn(tile, concealed, tsumo = true))?.takeIf { it.yaku.isNotEmpty() }
            val best = ron ?: tsumo
            val value = when {
                best != null && furiten -> worth(best).copy().append(" · ").append(Component.translatable("tukuai.riichi.furiten"))
                best != null -> worth(best)
                else -> Component.translatable("tukuai.riichi.no_yaku")
            }
            WaitHint(tile, view.unseen(tile), best != null, value)
        }
    }

    /** The dora indicators, and furiten when the player is, under the buttons in the top right. */
    override fun renderExtra(g: GuiGraphicsExtractor, table: ClientTable, view: RcView) {
        val label = Component.translatable("tukuai.riichi.dora_indicators")
        val w = MjArt.width(SMALL_SCALE)
        val y = if (screen.inWorld) TOP_BOX_Y + BOX_H + 26 else 48
        var x = width - 8 - view.doraIndicators.size * w
        PanelText.text(g, label, x - 4, y + 3, TableScreen.GRAY, PanelText.Align.RIGHT)
        for (t in view.doraIndicators) {
            MjArt.draw(g, t, x, y, SMALL_SCALE)
            x += w
        }
        if (view.furiten && view.seat != null && view.hand.size % 3 == 1) {
            PanelText.text(g, Component.translatable("tukuai.riichi.furiten"), width - 8, y + MjArt.height(SMALL_SCALE) + 2, TableScreen.RED, PanelText.Align.RIGHT)
        }
    }

    // ---- The result ----------------------------------------------------------------------------

    private fun resultBottom(): Int = minOf(height - 4, RESULT_Y + 172)

    override fun resultButtons(table: ClientTable, view: RcView) {
        val bottom = resultBottom()
        if (view.phase == RcPhase.OVER) {
            if (table.snapshot.status == TableStatus.FINISHED) screen.finishButtons(table, width / 2, bottom - 24)
            return
        }
        val me = view.seat ?: return
        if (live(table) && !view.ready[me]) {
            screen.button(Component.translatable("tukuai.mahjong.next_hand"), width / 2 - 50, bottom - 24, 100) { send(RcAction.Next) }
        }
    }

    override fun renderResult(g: GuiGraphicsExtractor, table: ClientTable, view: RcView) {
        val result = view.result ?: return
        val w = minOf(400, width - 16)
        val x = width / 2 - w / 2
        val top = RESULT_Y
        val bottom = resultBottom()
        g.fill(x, top, x + w, bottom, 0xF0101010.toInt())
        val title = Component.translatable("tukuai.riichi.hand_title", MjText.wind(result.round), result.hand + 1, result.honba).append("  ").append(outcome(table, result))
        g.centeredText(font, title, width / 2, top + 5, TableScreen.YELLOW)
        var y = top + 17
        // Dora indicators, and the ura dora once revealed.
        val w2 = MjArt.width(SMALL_SCALE)
        var dx = x + 10
        g.text(font, Component.translatable("tukuai.riichi.dora_indicators"), dx, y + 3, TableScreen.GRAY, false)
        dx += font.width(Component.translatable("tukuai.riichi.dora_indicators")) + 4
        for (t in result.doraIndicators) { MjArt.draw(g, t, dx, y, SMALL_SCALE); dx += w2 }
        if (result.uraIndicators.isNotEmpty()) {
            dx += 8
            g.text(font, Component.translatable("tukuai.riichi.ura_indicators"), dx, y + 3, TableScreen.GRAY, false)
            dx += font.width(Component.translatable("tukuai.riichi.ura_indicators")) + 4
            for (t in result.uraIndicators) { MjArt.draw(g, t, dx, y, SMALL_SCALE); dx += w2 }
        }
        y += MjArt.height(SMALL_SCALE) + 4
        val win = result.wins.firstOrNull()
        if (win != null) {
            // The first winner's hand, the tile it won on last and lit, then the melds.
            val tiles = view.shown[win.seat].let { t -> t.toMutableList().also { it.remove(win.tile); it += win.tile } }
            val scale = 0.4f
            val tw = MjArt.width(scale)
            val melds = view.melds[win.seat]
            val total = tiles.size * tw + 4 + melds.sumOf { it.tiles.size } * tw + melds.size * 3
            var tx = width / 2 - total / 2
            tiles.forEachIndexed { i, tile ->
                if (i == tiles.size - 1) tx += 4
                MjArt.draw(g, tile, tx, y, scale, tint = if (i == tiles.size - 1) 0x50FFC020 else 0)
                tx += tw
            }
            renderMelds(g, melds, tx + 4, y, scale)
            y += MjArt.height(scale) + 4
            renderYaku(g, table, result.wins, x + 10, y, w / 2 - 14)
        } else {
            val lines = drawLines(table, result)
            lines.forEachIndexed { i, line -> g.centeredText(font, line, width / 2, y + 4 + i * 11, TableScreen.GRAY) }
            y += 8 + lines.size * 11
        }
        renderPoints(g, table, view, result, if (win != null) x + w / 2 + 4 else x + w / 4, y, if (win != null) w / 2 - 14 else w / 2)
        if (view.phase == RcPhase.HAND_OVER) {
            val waiting = view.ready.withIndex().filter { !it.value }.map { it.index }
            if (waiting.isNotEmpty() && (view.seat == null || view.ready.getOrElse(view.seat!!) { true })) {
                g.centeredText(font, Component.translatable("tukuai.mahjong.waiting_for", MjUi.name(table, waiting.first())), width / 2, bottom - 18, TableScreen.GRAY)
            }
        }
    }

    private fun outcome(table: ClientTable, r: RcHandResult): Component = when {
        r.wins.isNotEmpty() -> {
            val w = r.wins.first()
            val names = Component.empty()
            r.wins.forEachIndexed { i, win -> if (i > 0) names.append("、"); names.append(MjUi.name(table, win.seat)) }
            if (w.from == null) Component.translatable("tukuai.riichi.won_tsumo", names) else Component.translatable("tukuai.riichi.won_ron", names, MjUi.name(table, w.from!!))
        }
        r.abort != null -> Component.translatable("tukuai.riichi.abort.${r.abort!!.name.lowercase()}")
        else -> Component.translatable("tukuai.riichi.exhausted")
    }

    /** At a draw: who was ready, and anyone who made a mangan with their discards. */
    private fun drawLines(table: ClientTable, r: RcHandResult): List<Component> {
        if (r.abort != null) return listOf(Component.translatable("tukuai.riichi.abort_note"))
        val out = ArrayList<Component>()
        for (s in r.nagashi) out += Component.translatable("tukuai.riichi.nagashi", MjUi.name(table, s))
        val ready = r.ready.withIndex().filter { it.value }.map { it.index }
        if (ready.isEmpty()) out += Component.translatable("tukuai.riichi.nobody_ready") else {
            val names = Component.empty()
            ready.forEachIndexed { i, s -> if (i > 0) names.append("、"); names.append(MjUi.name(table, s)) }
            out += Component.translatable("tukuai.riichi.ready_list", names)
        }
        return out
    }

    /** Each winner's yaku with their han, dora, and what the hand is worth. */
    private fun renderYaku(g: GuiGraphicsExtractor, table: ClientTable, wins: List<RcWinRecord>, x: Int, y: Int, w: Int) {
        var cy = y
        for (win in wins) {
            if (wins.size > 1) {
                g.text(font, MjUi.name(table, win.seat), x, cy, TableScreen.YELLOW, false)
                cy += 10
            }
            val lines = ArrayList<Pair<Component, String>>()
            for ((yaku, han) in win.yaku) lines += Component.translatable(yaku.key) to if (yaku.yakuman) "" else "$han"
            if (win.yakuman == 0) {
                if (win.dora > 0) lines += Component.translatable("tukuai.riichi.dora") to "${win.dora}"
                if (win.aka > 0) lines += Component.translatable("tukuai.riichi.aka") to "${win.aka}"
                if (win.ura > 0) lines += Component.translatable("tukuai.riichi.ura") to "${win.ura}"
            }
            for ((name, han) in lines.take(if (wins.size > 1) 4 else 9)) {
                g.text(font, name, x, cy, TableScreen.WHITE, false)
                if (han.isNotEmpty()) {
                    val v = Component.translatable("tukuai.riichi.han", han)
                    g.text(font, v, x + w - font.width(v), cy, TableScreen.GRAY, false)
                }
                cy += 10
            }
            val total = worth(win.han, win.fu, win.yakuman, win.limit).copy().append("  ").append(Component.translatable("tukuai.riichi.points", win.value))
            g.text(font, total, x + w - font.width(total), cy + 1, TableScreen.YELLOW, false)
            cy += 13
        }
    }

    /** Each seat's points from this hand and in all. */
    private fun renderPoints(g: GuiGraphicsExtractor, table: ClientTable, view: RcView, result: RcHandResult, x: Int, y: Int, w: Int) {
        val me = table.snapshot.you
        g.text(font, Component.translatable("tukuai.guobiao.this_hand"), x + w - 92, y, TableScreen.GRAY, false)
        g.text(font, Component.translatable("tukuai.riichi.points_now"), x + w - 40, y, TableScreen.GRAY, false)
        for (seat in 0 until Riichi.SEATS) {
            val ry = y + 11 + seat * 11
            g.text(font, MjText.wind((seat - result.dealer + 4) % 4), x, ry, TableScreen.YELLOW, false)
            val name = font.plainSubstrByWidth(PanelText.seatLabel(table, seat).string, w - 120)
            g.text(font, name, x + 16, ry, if (seat == me) TableScreen.YELLOW else TableScreen.WHITE, false)
            val change = result.payments[seat]
            val c = Component.literal(if (change > 0) "+$change" else "$change")
            g.text(font, c, x + w - 92, ry, if (change > 0) TableScreen.GREEN else if (change < 0) TableScreen.RED else TableScreen.GRAY, false)
            g.text(font, Component.literal("${view.points[seat]}"), x + w - 40, ry, TableScreen.WHITE, false)
            if (view.phase == RcPhase.HAND_OVER && view.ready[seat]) g.text(font, Component.literal("✔"), x + w + 2, ry, TableScreen.GREEN, false)
        }
    }

    private companion object {
        const val RESULT_Y = 60
    }
}
