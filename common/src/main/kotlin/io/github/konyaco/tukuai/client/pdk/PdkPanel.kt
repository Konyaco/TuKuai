package io.github.konyaco.tukuai.client.pdk

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientConfig
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.PanelText
import io.github.konyaco.tukuai.client.PlayingCardHand
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.doudizhu.DDZ_ORDER
import io.github.konyaco.tukuai.engine.paodekuai.PaoDeKuai
import io.github.konyaco.tukuai.engine.paodekuai.PdkAction
import io.github.konyaco.tukuai.engine.paodekuai.PdkCodecs
import io.github.konyaco.tukuai.engine.paodekuai.PdkEvent
import io.github.konyaco.tukuai.engine.paodekuai.PdkEventKind
import io.github.konyaco.tukuai.engine.paodekuai.PdkTurn
import io.github.konyaco.tukuai.engine.paodekuai.PdkView
import io.github.konyaco.tukuai.engine.paodekuai.bot.PdkBot
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW

/**
 * The Pao De Kuai part of the table screen, laid out like Dou Dizhu's: your hand along the
 * bottom, the next player on your right and the one after on your left. A player down to
 * one card gets a red warning, since the rules change for whoever plays before them.
 */
class PdkPanel(private val screen: PanelHost) : GamePanel {
    private val picker = PlayingCardHand(screen)
    private val selected get() = picker.selected
    private var wasMyTurn = false

    private val width get() = screen.panelWidth
    private val font get() = Minecraft.getInstance().font

    private fun send(action: PdkAction) = screen.send(TableOp.ACT, data = PdkCodecs.ACTION.encode(action))

    private fun buttonY() = picker.buttonY()

    override fun controlsY(): Int = buttonY()

    /** Your own hand, or for a watcher who may see every hand, that of seat 0 at the bottom. */
    private fun sortedHand(view: PdkView): List<PlayingCard> =
        (if (view.seat == null) view.revealedHands.getOrNull(0).orEmpty() else view.hand).sortedWith(DDZ_ORDER.reversed())

    /** What you may do now, when it is your turn at a game that is on. */
    private fun turn(table: ClientTable, view: PdkView): PdkTurn? =
        if (screen.interactive && table.snapshot.status == TableStatus.PLAYING) view.turnRules() else null

    // ---- Buttons -------------------------------------------------------------------------------

    override fun rebuild(table: ClientTable) {
        val view = table.view as? PdkView ?: return
        val s = table.snapshot
        picker.sync(s.step, view.hand)
        if (!screen.interactive) return
        val turn = turn(table, view)
        if (turn != null && !wasMyTurn) {
            Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f))
        }
        wasMyTurn = turn != null

        val me = s.you
        if (s.status == TableStatus.PLAYING && me != null) {
            val auto = s.seats.getOrNull(me)?.auto == true
            screen.button(Component.translatable(if (auto) "tukuai.table.auto.off" else "tukuai.table.auto.on"), width - 74, 48, 70) {
                screen.send(TableOp.AUTO, if (auto) 0 else 1)
            }
        }
        if (s.status == TableStatus.FINISHED) {
            val ready = me != null && s.seats.all { it.occupant != null }
            screen.button(Component.translatable("tukuai.table.again"), width / 2 - 100, RESULT_TOP + RESULT_HEIGHT - 24, 98, ready) {
                screen.send(TableOp.START)
            }
            screen.button(Component.translatable("tukuai.table.replay"), width / 2 + 2, RESULT_TOP + RESULT_HEIGHT - 24, 98) {
                screen.send(TableOp.REPLAY)
            }
        }
        if (turn == null) return
        val w = 56
        val gap = 6
        var x = (width - (3 * w + 2 * gap)) / 2
        val y = buttonY()
        screen.button(Component.translatable("tukuai.ddz.pass"), x, y, w, turn.canPass) { send(PdkAction.Pass) }
        x += w + gap
        screen.button(Component.translatable("tukuai.ddz.hint"), x, y, w) { hint(view, turn) }
        x += w + gap
        screen.button(Component.translatable("tukuai.ddz.play"), x, y, w, turn.reading(selected) != null) {
            send(PdkAction.PlayCards(selected.toList()))
        }
    }

    /** Picks cards for the player: first what the computer would play, then every other legal play in turn. */
    private fun hint(view: PdkView, turn: PdkTurn) {
        val found = picker.hint {
            val suggestion = (PdkBot(BotLevel.NORMAL, 0).act(view) as? PdkAction.PlayCards)?.cards
            val options = turn.plays.sortedWith(compareBy({ it.pattern.isBomb }, { it.pattern.main }, { -it.cards.size })).map { it.cards }
            listOfNotNull(suggestion) + options
        }
        if (!found && turn.canPass) send(PdkAction.Pass)
    }

    // ---- Input ---------------------------------------------------------------------------------

    override fun mouseClicked(table: ClientTable, x: Double, y: Double, button: Int): Boolean {
        if (!screen.interactive) return false
        val view = table.view as? PdkView ?: return false
        if (view.seat == null) return false
        return picker.click(sortedHand(view), x, y, button)
    }

    override fun clickTableCard(table: ClientTable, card: TableCard): Boolean {
        if (!screen.interactive) return false
        val view = table.view as? PdkView ?: return false
        return picker.clickTableCard(view.hand, card)
    }

    override fun isRaised(table: ClientTable, card: TableCard): Boolean = picker.isRaised(card)

    override fun keyPressed(table: ClientTable, key: Int): Boolean {
        val view = table.view as? PdkView ?: return false
        val turn = turn(table, view) ?: return false
        return when (key) {
            GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_ENTER -> {
                if (turn.reading(selected) != null) {
                    send(PdkAction.PlayCards(selected.toList()))
                } else if (selected.isEmpty() && turn.canPass) {
                    send(PdkAction.Pass)
                }
                true
            }
            GLFW.GLFW_KEY_H -> {
                hint(view, turn)
                true
            }
            else -> false
        }
    }

    // ---- Drawing -------------------------------------------------------------------------------

    override fun render(g: GuiGraphicsExtractor, table: ClientTable, mouseX: Int, mouseY: Int) {
        val view = table.view as? PdkView ?: return
        val s = table.snapshot
        val me = s.you ?: 0
        val right = (me + 1) % PaoDeKuai.SEATS
        val left = (me + 2) % PaoDeKuai.SEATS
        val recent = recentActions(view)
        val finished = s.status == TableStatus.FINISHED

        PanelText.title(g, table, screen.interactive, width)
        renderOpponent(g, table, view, left, recent[left], onLeft = true)
        renderOpponent(g, table, view, right, recent[right], onLeft = false)

        if (!finished) {
            // Your own last move above your hand, or on your turn, what the rules ask of you.
            recent[me]?.let { renderEvent(g, it, width / 2, buttonY() - 40, PanelText.Align.CENTER) }
            turn(table, view)?.let { renderTurnNote(g, view, it) }
        }
        renderSelf(g, table, view, me)
        picker.render(g, sortedHand(view), mouseX, mouseY)
        if (finished) renderResult(g, table, view)
    }

    /** One line over the buttons when the rules limit what you may do this turn. */
    private fun renderTurnNote(g: GuiGraphicsExtractor, view: PdkView, turn: PdkTurn) {
        val note = when {
            turn.opening -> "tukuai.pdk.note.opening"
            turn.toBeat != null && turn.plays.isEmpty() -> "tukuai.pdk.note.nothing"
            turn.toBeat != null && !turn.canPass -> "tukuai.pdk.note.must_beat"
            turn.nextHasOne -> "tukuai.pdk.note.highest"
            else -> return
        }
        val color = if (note == "tukuai.pdk.note.highest") TableScreen.RED else TableScreen.YELLOW
        val lines = font.split(Component.translatable(note), width - 40)
        lines.forEachIndexed { i, line ->
            g.centeredText(font, line, width / 2, buttonY() - 12 - (lines.size - 1 - i) * 10, color)
        }
    }

    private fun renderOpponent(g: GuiGraphicsExtractor, table: ClientTable, view: PdkView, seat: Seat, recent: PdkEvent?, onLeft: Boolean) {
        // With the world in view, the boxes shrink to the edges, as in Dou Dizhu.
        val compact = ClientConfig.immersive && screen.interactive
        val boxW = if (compact) 74 else 96
        val margin = if (compact) 4 else 8
        val x = if (onLeft) margin else width - margin - boxW
        val y = 70
        val acting = view.turn == seat && table.snapshot.status == TableStatus.PLAYING
        g.fill(x - 2, y - 2, x + boxW + 2, y + 54, if (acting) 0x90806020.toInt() else 0x70000000)
        g.text(font, font.plainSubstrByWidth(PanelText.seatLabel(table, seat).string, boxW), x, y + 4, TableScreen.WHITE, true)

        val count = view.handCounts[seat]
        CardArt.draw(g, CardArt.back(), x, y + 18, 0.5f)
        g.text(font, Component.literal("× $count"), x + 20, y + 26, if (count == 1) TableScreen.RED else TableScreen.YELLOW, true)
        if (count == 1 && !view.finished) {
            g.text(font, Component.translatable("tukuai.pdk.last_card"), x, y + 42, TableScreen.RED, true)
        } else if (view.bombs[seat] > 0) {
            g.text(font, Component.translatable("tukuai.pdk.bombs", view.bombs[seat]), x, y + 42, TableScreen.GRAY, true)
        }
        if (acting) PanelText.timer(g, table, x + boxW - 4, y + 42, alignRight = true)

        // What they just did beside the box, or below it when compact; their whole hand, once
        // it is shown, goes below the box so the result in the middle does not cover it.
        val align = if (onLeft) PanelText.Align.LEFT else PanelText.Align.RIGHT
        val edgeX = if (onLeft) x else x + boxW
        val revealed = view.revealedHands.getOrNull(seat)
        val finished = table.snapshot.status == TableStatus.FINISHED
        if (revealed != null) {
            val below = if (compact && !finished && recent != null) 58 + CardArt.HEIGHT / 2 + 4 else 60
            PanelText.cards(g, revealed.sortedWith(DDZ_ORDER.reversed()), edgeX, y + below, 0.5f, 7, align)
        }
        if (recent == null || (finished && revealed != null)) return
        if (compact) {
            renderEvent(g, recent, edgeX, if (recent.kind == PdkEventKind.PLAY) y + 58 else y + 48, align, scale = 0.5f, step = 7)
        } else {
            renderEvent(g, recent, if (onLeft) x + boxW + 8 else x - 8, y + 4, align)
        }
    }

    private fun renderSelf(g: GuiGraphicsExtractor, table: ClientTable, view: PdkView, me: Seat) {
        val y = buttonY() + 4
        val x = 8
        // A watcher who sees every hand watches from seat 0; one who does not just watches.
        val seen = table.snapshot.you != null || view.revealedHands.isNotEmpty()
        val label = if (!seen) Component.translatable("tukuai.table.watching") else PanelText.seatLabel(table, me)
        g.text(font, label, x, y, TableScreen.WHITE, true)
        if (!seen) return
        val playing = table.snapshot.status == TableStatus.PLAYING
        if (view.turn == me && playing) PanelText.timer(g, table, x, y + 12, alignRight = false)
        else if (view.handCounts[me] == 1 && playing) g.text(font, Component.translatable("tukuai.pdk.last_card"), x, y + 12, TableScreen.RED, true)
    }

    private fun renderEvent(g: GuiGraphicsExtractor, event: PdkEvent, x: Int, y: Int, align: PanelText.Align, scale: Float = 0.75f, step: Int = 13) {
        when (event.kind) {
            PdkEventKind.PLAY -> PanelText.cards(g, event.cards.sortedWith(DDZ_ORDER.reversed()), x, y, scale, step, align)
            PdkEventKind.PASS -> PanelText.text(g, Component.translatable("tukuai.ddz.pass"), x, y + 10, TableScreen.YELLOW, align)
            PdkEventKind.BOMB_WON -> {}
        }
    }

    private fun renderResult(g: GuiGraphicsExtractor, table: ClientTable, view: PdkView) {
        val settlement = view.settlement ?: return
        val w = 210
        val x = width / 2 - w / 2
        val top = RESULT_TOP
        g.fill(x, top, x + w, top + RESULT_HEIGHT, 0xE0101010.toInt())
        val me = table.snapshot.you
        val winner = PanelText.seatLabel(table, settlement.winner)
        val color = if (me == null) TableScreen.WHITE else if (me == settlement.winner) TableScreen.GREEN else TableScreen.RED
        g.centeredText(font, Component.translatable("tukuai.pdk.won", winner), width / 2, top + 6, color)
        // Each seat on two lines: name and points, then what they paid for.
        for (seat in 0 until PaoDeKuai.SEATS) {
            val y = top + 22 + seat * ROW
            val points = settlement.scores.getOrElse(seat) { 0 }
            val text = Component.literal(if (points > 0) "+$points" else points.toString())
            val nameColor = if (seat == me) TableScreen.YELLOW else TableScreen.WHITE
            g.text(font, font.plainSubstrByWidth(PanelText.seatLabel(table, seat).string, w - 40), x + 8, y, nameColor, false)
            g.text(font, text, x + w - 8 - font.width(text), y, if (points >= 0) TableScreen.GREEN else TableScreen.RED, false)
            val detail = Component.empty()
            if (seat != settlement.winner) {
                detail.append(Component.translatable("tukuai.pdk.result.left", settlement.cardsLeft[seat]))
                if (settlement.shutOut[seat]) detail.append(" ").append(Component.translatable("tukuai.pdk.result.shut_out"))
            }
            if (settlement.bombs[seat] > 0) {
                if (seat != settlement.winner) detail.append("   ")
                detail.append(Component.translatable("tukuai.pdk.bombs", settlement.bombs[seat]))
            }
            g.text(font, detail, x + 16, y + 10, TableScreen.GRAY, false)
        }
    }

    companion object {
        /** The result box sits at the top, clear of both players' boxes. */
        private const val RESULT_TOP = 4
        private const val ROW = 22
        private const val RESULT_HEIGHT = 22 + 3 * ROW + 28

        /** Each seat's latest play or pass in the current round; the seat to act shows nothing. */
        fun recentActions(view: PdkView): Map<Seat, PdkEvent> {
            val out = HashMap<Seat, PdkEvent>()
            val wanted = if (view.finished) PaoDeKuai.SEATS else PaoDeKuai.SEATS - 1
            var seen = 0
            for (e in view.log.asReversed()) {
                if (e.kind == PdkEventKind.BOMB_WON) continue
                out.putIfAbsent(e.seat, e)
                if (++seen >= wanted) break
            }
            if (!view.finished) out.remove(view.turn)
            return out
        }
    }
}
