package io.github.konyaco.tukuai.client.mahjong

import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.PanelText
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.guobiao.GbAction
import io.github.konyaco.tukuai.engine.mahjong.guobiao.GbCodecs
import io.github.konyaco.tukuai.engine.mahjong.guobiao.GbFan
import io.github.konyaco.tukuai.engine.mahjong.guobiao.GbHandResult
import io.github.konyaco.tukuai.engine.mahjong.guobiao.GbPhase
import io.github.konyaco.tukuai.engine.mahjong.guobiao.GbScore
import io.github.konyaco.tukuai.engine.mahjong.guobiao.GbScoring
import io.github.konyaco.tukuai.engine.mahjong.guobiao.GbView
import io.github.konyaco.tukuai.engine.mahjong.guobiao.Guobiao
import io.github.konyaco.tukuai.engine.mahjong.guobiao.bot.GbBot
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.network.chat.Component

/** The Guobiao mahjong part of the table screen, on the shared mahjong layout of [MjPanelBase]. */
class GbPanel(host: PanelHost) : MjPanelBase<GbView>(host) {
    private companion object {
        /** The result starts below the scoreboard in the top left corner. */
        const val RESULT_Y = 60
    }

    private fun send(action: GbAction) = screen.send(TableOp.ACT, data = GbCodecs.ACTION.encode(action))

    override fun view(table: ClientTable): GbView? = table.view as? GbView

    override fun isMyTurn(view: GbView): Boolean = view.isMyTurn

    override fun discarding(view: GbView): Boolean = view.phase == GbPhase.PLAY && view.isMyTurn

    override fun discard(view: GbView, tile: Tile) = send(GbAction.Discard(tile))

    override fun hintTile(view: GbView): Tile? = GbBot(BotLevel.NORMAL, 0).discard(view)

    override fun showingResult(view: GbView): Boolean = view.handOver

    override fun onEnter(view: GbView) {
        when (view.phase) {
            GbPhase.CLAIM, GbPhase.ROB -> send(GbAction.Pass)
            else -> {}
        }
    }

    override fun cannotWin(): Component = Component.translatable("tukuai.guobiao.short_of_fan")

    private fun fanText(score: GbScore): Component = Component.translatable("tukuai.guobiao.fan_count", score.total)

    /** What [view]'s player would score winning on the tile being answered. */
    private fun claimScore(view: GbView): GbScore? {
        val tile = view.claimTile ?: return null
        val win = view.winOn(tile, view.hand, selfDrawn = false)
        return GbScoring.score(if (view.phase == GbPhase.ROB) win.copy(robKong = true, lastTile = false, lastOfKind = false) else win)
    }

    override fun buttons(table: ClientTable, view: GbView): List<Btn> {
        val out = ArrayList<Btn>()
        when (view.phase) {
            GbPhase.PLAY -> {
                view.selfDraw?.let { out += Btn(Component.translatable("tukuai.guobiao.win_with", fanText(it))) { send(GbAction.Win) } }
                for (tile in view.kongs) out += Btn(Component.translatable("tukuai.mahjong.kong_tile", MjText.tile(tile))) { send(GbAction.Kong(tile)) }
                out += Btn(Component.translatable("tukuai.ddz.hint")) { hint(view) }
                val pick = pickedTiles(view).singleOrNull()
                out += Btn(Component.translatable("tukuai.mahjong.discard"), pick != null) { pick?.let { send(GbAction.Discard(it)) } }
            }
            GbPhase.CLAIM, GbPhase.ROB -> {
                val tile = view.claimTile ?: return out
                val o = view.options
                if (o.win) {
                    val score = claimScore(view)
                    val text = if (score != null) Component.translatable("tukuai.guobiao.win_with", fanText(score)) else Component.translatable("tukuai.mahjong.win")
                    out += Btn(text) { send(GbAction.Win) }
                }
                if (o.kong) out += Btn(Component.translatable("tukuai.mahjong.kong")) { send(GbAction.Kong(tile)) }
                if (o.pung) out += Btn(Component.translatable("tukuai.mahjong.pung")) { send(GbAction.Pung) }
                for (start in o.chows) out += Btn(Component.translatable("tukuai.mahjong.chow_run", MjText.run(start))) { send(GbAction.Chow(start)) }
                out += Btn(Component.translatable("tukuai.ddz.pass")) { send(GbAction.Pass) }
            }
            else -> {}
        }
        return out
    }

    override fun prompt(table: ClientTable, view: GbView, mine: Boolean): Pair<Component?, Component?> = when (view.phase) {
        GbPhase.PLAY -> null to when {
            !mine -> null
            view.selfDraw != null -> Component.translatable("tukuai.guobiao.note.can_win", fanText(view.selfDraw!!))
            else -> Component.translatable("tukuai.mahjong.note.discard")
        }
        GbPhase.CLAIM, GbPhase.ROB -> {
            val tile = view.claimTile
            val from = view.claimFrom
            if (tile != null && from != null) {
                val key = if (view.phase == GbPhase.ROB) "tukuai.mahjong.note.robbing" else "tukuai.mahjong.note.discarded"
                val line = Component.translatable(key, MjUi.name(table, from), MjText.tile(tile))
                val ask = when {
                    !mine -> null
                    view.options.win -> claimScore(view)?.let { Component.translatable("tukuai.guobiao.note.can_win_claim", fanText(it)) }
                        ?: Component.translatable("tukuai.mahjong.note.claim")
                    else -> Component.translatable("tukuai.mahjong.note.claim")
                }
                line to ask
            } else null to null
        }
        else -> null to null
    }

    override fun badge(view: GbView, seat: Seat): Component? {
        val wind = MjText.wind(view.seatWind(seat)).copy()
        if (seat == view.dealer) wind.append(Component.translatable("tukuai.mahjong.dealer_badge"))
        return wind
    }

    override fun score(view: GbView, seat: Seat): String = view.scores[seat].let { if (it > 0) "+$it" else "$it" }

    override fun info(table: ClientTable, view: GbView): List<Component> = listOf(
        Component.translatable("tukuai.guobiao.hand_title", MjText.wind(view.prevalentWind), view.handIndex % 4 + 1),
        Component.translatable("tukuai.guobiao.progress", view.handIndex + 1, view.rules.hands),
        Component.translatable("tukuai.mahjong.wall", view.wallLeft),
    )

    override fun waitHints(view: GbView, concealed: List<Tile>): List<WaitHint> {
        val waits = GbScoring.waits(concealed, view.myMelds)
        return waits.map { tile ->
            val onDiscard = GbScoring.score(view.winOn(tile, concealed, selfDrawn = false))
            val drawn = GbScoring.score(view.winOn(tile, concealed, selfDrawn = true))
            val best = listOfNotNull(onDiscard, drawn).filter { it.base >= view.rules.minFan }.maxByOrNull { it.total }
            val shown = best ?: listOfNotNull(onDiscard, drawn).maxByOrNull { it.total }
            WaitHint(tile, view.unseen(tile), best != null, shown?.let { fanText(it) })
        }
    }

    // ---- The result ----------------------------------------------------------------------------

    override fun resultButtons(table: ClientTable, view: GbView) {
        val bottom = resultBottom()
        if (view.phase == GbPhase.OVER) {
            if (table.snapshot.status == TableStatus.FINISHED) screen.finishButtons(table, width / 2, bottom - 24)
            return
        }
        val me = view.seat ?: return
        if (live(table) && !view.ready[me]) {
            screen.button(Component.translatable("tukuai.mahjong.next_hand"), width / 2 - 50, bottom - 24, 100) { send(GbAction.Next) }
        }
    }

    private fun resultBottom(): Int = minOf(height - 4, RESULT_Y + 172)

    override fun renderResult(g: GuiGraphicsExtractor, table: ClientTable, view: GbView) {
        val result = view.result ?: return
        val w = minOf(400, width - 16)
        val x = width / 2 - w / 2
        val top = RESULT_Y
        val bottom = resultBottom()
        g.fill(x, top, x + w, bottom, 0xF0101010.toInt())
        val winner = result.winner
        val title = Component.translatable("tukuai.guobiao.hand_title", MjText.wind(view.prevalentWind), view.handIndex % 4 + 1).append("  ")
            .append(if (winner == null) Component.translatable("tukuai.mahjong.nobody_won") else Component.translatable("tukuai.guobiao.won", MjUi.name(table, winner)))
        g.centeredText(font, title, width / 2, top + 5, TableScreen.YELLOW)
        var y = top + 17
        if (winner != null) {
            val how = when {
                result.from == null -> Component.translatable("tukuai.mahjong.self_drawn")
                GbFan.ROBBING_THE_KONG in result.fans -> Component.translatable("tukuai.guobiao.robbed_from", MjUi.name(table, result.from!!))
                else -> Component.translatable("tukuai.guobiao.dealt_in", MjUi.name(table, result.from!!))
            }
            g.centeredText(font, how, width / 2, y, TableScreen.GRAY)
            y += 11
            // The winning hand, the tile it won on last and lit, then the melds and flowers.
            val tiles = view.shown[winner].let { t -> t.toMutableList().also { it.remove(result.tile); it += result.tile!! } }
            val scale = 0.4f
            val tw = MjArt.width(scale)
            val melds = view.melds[winner]
            val total = tiles.size * tw + 4 + melds.sumOf { it.tiles.size } * tw + melds.size * 3 + view.flowers[winner].size * 8
            var tx = width / 2 - total / 2
            tiles.forEachIndexed { i, tile ->
                if (i == tiles.size - 1) tx += 4
                MjArt.draw(g, tile, tx, y, scale, tint = if (i == tiles.size - 1) 0x50FFC020 else 0)
                tx += tw
            }
            val end = renderMelds(g, melds, tx + 4, y, scale)
            renderFlowers(g, view.flowers[winner], end, y + 6)
            y += MjArt.height(scale) + 5
            renderFans(g, result, x + 10, y, w / 2 - 14)
        } else {
            g.centeredText(font, Component.translatable("tukuai.guobiao.exhausted"), width / 2, y + 4, TableScreen.GRAY)
            y += 20
        }
        renderPoints(g, table, view, result, if (winner != null) x + w / 2 + 4 else x + w / 4, y, if (winner != null) w / 2 - 14 else w / 2)
        if (view.phase == GbPhase.HAND_OVER) {
            val waiting = view.ready.withIndex().filter { !it.value }.map { it.index }
            if (waiting.isNotEmpty() && (view.seat == null || view.ready.getOrElse(view.seat!!) { true })) {
                g.centeredText(font, Component.translatable("tukuai.mahjong.waiting_for", MjUi.name(table, waiting.first())), width / 2, bottom - 18, TableScreen.GRAY)
            }
        }
    }

    /** The fan, one to a line with what each is worth, then the total; two columns when there are many. */
    private fun renderFans(g: GuiGraphicsExtractor, result: GbHandResult, x: Int, y: Int, w: Int) {
        val counts = result.fans.groupingBy { it }.eachCount().entries.toList()
        val perColumn = 8
        val columns = if (counts.size > perColumn) 2 else 1
        val col = w / columns
        counts.forEachIndexed { i, (fan, n) ->
            val cx = x + (i / perColumn) * col
            val cy = y + (i % perColumn) * 10
            val name = Component.translatable(fan.key).copy().also { if (n > 1) it.append(" ×$n") }
            g.text(font, name, cx, cy, TableScreen.WHITE, false)
            val value = Component.translatable("tukuai.guobiao.fan_count", fan.fan * n)
            g.text(font, value, cx + col - 6 - font.width(value), cy, TableScreen.GRAY, false)
        }
        val total = Component.translatable("tukuai.guobiao.total", result.fan)
        g.text(font, total, x + w - 6 - font.width(total), y + minOf(counts.size, perColumn) * 10 + 2, TableScreen.YELLOW, false)
    }

    /** Each seat's points from this hand and in all. */
    private fun renderPoints(g: GuiGraphicsExtractor, table: ClientTable, view: GbView, result: GbHandResult, x: Int, y: Int, w: Int) {
        val me = table.snapshot.you
        g.text(font, Component.translatable("tukuai.guobiao.this_hand"), x + w - 80, y, TableScreen.GRAY, false)
        g.text(font, Component.translatable("tukuai.guobiao.in_all"), x + w - 36, y, TableScreen.GRAY, false)
        for (seat in 0 until Guobiao.SEATS) {
            val ry = y + 11 + seat * 11
            val wind = badge(view, seat) ?: Component.empty()
            g.text(font, wind, x, ry, TableScreen.YELLOW, false)
            val name = font.plainSubstrByWidth(PanelText.seatLabel(table, seat).string, w - 120)
            g.text(font, name, x + 28, ry, if (seat == me) TableScreen.YELLOW else TableScreen.WHITE, false)
            val change = result.payments[seat]
            val c = Component.literal(if (change > 0) "+$change" else "$change")
            g.text(font, c, x + w - 80, ry, if (change > 0) TableScreen.GREEN else if (change < 0) TableScreen.RED else TableScreen.GRAY, false)
            val total = view.scores[seat]
            g.text(font, Component.literal(if (total > 0) "+$total" else "$total"), x + w - 36, ry, TableScreen.WHITE, false)
            if (view.phase == GbPhase.HAND_OVER && view.ready[seat]) g.text(font, Component.literal("✔"), x + w + 2, ry, TableScreen.GREEN, false)
        }
    }
}
