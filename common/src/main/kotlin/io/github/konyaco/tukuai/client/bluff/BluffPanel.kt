package io.github.konyaco.tukuai.client.bluff

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.PanelText
import io.github.konyaco.tukuai.client.PlayingCardHand
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.engine.bluff.Bluff
import io.github.konyaco.tukuai.engine.bluff.BluffAction
import io.github.konyaco.tukuai.engine.bluff.BluffCodecs
import io.github.konyaco.tukuai.engine.bluff.BluffEventKind
import io.github.konyaco.tukuai.engine.bluff.BluffPenalty
import io.github.konyaco.tukuai.engine.bluff.BluffPhase
import io.github.konyaco.tukuai.engine.bluff.BluffView
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW

/**
 * The creeper bluff part of the table screen: your hand along the bottom, the other players
 * around the edges in the order they sit (the next seat number on your right), and in the
 * middle the rank called for, the pile laid down this round and the last play. A play that
 * was called is turned over there, and whoever lost the call lights their creeper's fuse.
 */
class BluffPanel(private val screen: PanelHost) : GamePanel {
    private val picker = PlayingCardHand(screen)
    private val selected get() = picker.selected
    private var wasMyTurn = false

    private val width get() = screen.panelWidth
    private val font get() = Minecraft.getInstance().font

    private fun send(action: BluffAction) = screen.send(TableOp.ACT, data = BluffCodecs.ACTION.encode(action))

    private fun buttonY() = picker.buttonY()

    override fun controlsY(): Int = buttonY()

    /** Your own hand, or for a replay, that of seat 0 at the bottom. */
    private fun sortedHand(view: BluffView): List<PlayingCard> =
        BluffUi.sorted(if (view.seat == null) view.revealedHands.getOrNull(0).orEmpty() else view.hand)

    private fun myTurn(table: ClientTable, view: BluffView) =
        screen.interactive && table.snapshot.status == TableStatus.PLAYING && view.isMyTurn

    private fun canPlay(view: BluffView) =
        view.phase == BluffPhase.PLAY && Bluff.problem(view.rules, view.hand, selected.toList()) == null

    // ---- Buttons -------------------------------------------------------------------------------

    override fun rebuild(table: ClientTable) {
        val view = table.view as? BluffView ?: return
        val s = table.snapshot
        picker.sync(s.step, view.hand)
        if (!screen.interactive) return
        val mine = myTurn(table, view)
        if (mine && !wasMyTurn) {
            Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f))
        }
        wasMyTurn = mine

        if (s.status == TableStatus.FINISHED) screen.finishButtons(table, width / 2, resultTop() + resultHeight(view) - 26)
        if (!mine) return
        val y = buttonY()
        if (view.phase == BluffPhase.PENALTY) {
            val w = 120
            screen.button(Component.translatable(lightKey(view)), (width - w) / 2, y, w) { send(BluffAction.Light) }
            return
        }
        val w = 76
        val gap = 6
        var x = (width - (2 * w + gap)) / 2
        screen.button(Component.translatable("tukuai.bluff.call"), x, y, w, view.canCall) { send(BluffAction.Call) }
        x += w + gap
        screen.button(Component.translatable("tukuai.bluff.play"), x, y, w, canPlay(view)) { send(BluffAction.Play(selected.toList())) }
    }

    private fun lightKey(view: BluffView) = if (view.rules.penalty == BluffPenalty.FUSE) "tukuai.bluff.light" else "tukuai.bluff.lose_heart"

    // ---- Input ---------------------------------------------------------------------------------

    override fun mouseClicked(table: ClientTable, x: Double, y: Double, button: Int): Boolean {
        if (!screen.interactive) return false
        val view = table.view as? BluffView ?: return false
        if (view.seat == null) return false
        return picker.click(sortedHand(view), x, y, button)
    }

    override fun clickTableCard(table: ClientTable, card: TableCard): Boolean {
        if (!screen.interactive) return false
        val view = table.view as? BluffView ?: return false
        return picker.clickTableCard(view.hand, card)
    }

    override fun isRaised(table: ClientTable, card: TableCard): Boolean = picker.isRaised(card)

    override fun mouseDragged(table: ClientTable, x: Double, y: Double): Boolean {
        val view = table.view as? BluffView ?: return false
        return picker.drag(sortedHand(view), x)
    }

    override fun mouseReleased(table: ClientTable) = picker.release()

    override fun dragTableCard(table: ClientTable, card: TableCard) {
        val view = table.view as? BluffView ?: return
        picker.dragTableCard(view.hand, card)
    }

    /** Space or Enter plays the picked cards, or takes the penalty; C calls the last play a lie. */
    override fun keyPressed(table: ClientTable, key: Int): Boolean {
        val view = table.view as? BluffView ?: return false
        if (!myTurn(table, view)) return false
        return when (key) {
            GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_ENTER -> {
                if (view.phase == BluffPhase.PENALTY) send(BluffAction.Light) else if (canPlay(view)) send(BluffAction.Play(selected.toList()))
                true
            }
            GLFW.GLFW_KEY_C -> {
                if (view.canCall) send(BluffAction.Call)
                true
            }
            else -> false
        }
    }

    // ---- Drawing -------------------------------------------------------------------------------

    override fun render(g: GuiGraphicsExtractor, table: ClientTable, mouseX: Int, mouseY: Int) {
        val view = table.view as? BluffView ?: return
        val s = table.snapshot
        val me = s.you ?: 0
        PanelText.title(g, table, screen.interactive, width)
        for (seat in 0 until view.players) {
            if (seat == me) continue
            val (x, y) = slotOf(view, me, seat)
            renderOpponent(g, table, view, seat, x, y)
        }
        if (!view.finished) renderMiddle(g, table, view)
        renderSelf(g, table, view, me)
        picker.render(g, sortedHand(view), mouseX, mouseY)
        if (view.finished) renderResult(g, table, view)
    }

    /**
     * Where the box of each other player goes. Up to four at the table: on your right, across
     * and on your left; with more, from your right up the right side, across the top and down
     * the left, clear of the middle.
     */
    private fun slotOf(view: BluffView, me: Seat, seat: Seat): Pair<Int, Int> {
        val rel = (seat - me).mod(view.players)
        val right = width - 8 - BOX
        val across = width / 2 - BOX / 2
        if (view.players <= 4) {
            val place = when {
                view.players == 2 -> 1
                view.players == 3 -> if (rel == 1) 0 else 2
                else -> rel - 1
            }
            return listOf(right to 76, across to 4, 8 to 76)[place]
        }
        val slots = listOf(right to 124, right to 76, width / 2 + 6 to 4, width / 2 - 6 - BOX to 4, 8 to 76, 8 to 124, right to 28)
        val used = when (view.players - 1) {
            4 -> listOf(0, 1, 4, 5)
            5 -> listOf(0, 1, 2, 4, 5)
            6 -> listOf(0, 1, 2, 3, 4, 5)
            else -> listOf(0, 1, 6, 2, 3, 4, 5)
        }
        return slots[used[(rel - 1).coerceIn(0, used.size - 1)]]
    }

    /** A player's name, cards left, creeper or hearts, and what they laid down last this round. */
    private fun renderOpponent(g: GuiGraphicsExtractor, table: ClientTable, view: BluffView, seat: Seat, x: Int, y: Int) {
        val acting = view.toAct == seat && table.snapshot.status == TableStatus.PLAYING
        val out = view.out[seat]
        val fill = when {
            acting -> 0x90806020.toInt()
            out -> 0x50000000
            else -> 0x70000000
        }
        g.fill(x - 2, y - 2, x + BOX + 2, y + 46, fill)
        g.text(font, font.plainSubstrByWidth(PanelText.seatLabel(table, seat).string, BOX), x, y + 1, if (out) TableScreen.GRAY else TableScreen.WHITE, true)
        if (out) {
            g.text(font, Component.translatable("tukuai.bluff.out"), x, y + 14, TableScreen.RED, true)
            return
        }
        CardArt.draw(g, CardArt.back(), x, y + 12, 0.5f)
        g.text(font, Component.literal("× ${view.handCounts[seat]}"), x + 18, y + 18, TableScreen.YELLOW, true)
        renderChances(g, view, seat, x + BOX, y + 18, alignRight = true)
        // The last play this round, or the call it made.
        val news = news(view, seat)
        if (news != null) g.text(font, font.plainSubstrByWidth(news.string, BOX), x, y + 36, TableScreen.GRAY, true)
        if (acting) PanelText.timer(g, table, x + BOX, y + 36, alignRight = true)
    }

    /** "🔥 2/6" for a creeper lit twice of a six-long fuse, or a row of hearts, ending at or starting from [x]. */
    private fun renderChances(g: GuiGraphicsExtractor, view: BluffView, seat: Seat, x: Int, y: Int, alignRight: Boolean) {
        if (view.rules.penalty == BluffPenalty.HEARTS) {
            val left = view.heartsLeft(seat)
            val text = Component.literal("❤".repeat(left)).withColor(HEART_RGB)
                .append(Component.literal("❤".repeat(view.rules.chances - left)).withColor(EMPTY_RGB))
            PanelText.text(g, text, x, y, TableScreen.WHITE, if (alignRight) PanelText.Align.RIGHT else PanelText.Align.LEFT)
            return
        }
        val text = Component.literal("${view.lit[seat]}/${view.rules.chances}")
        val w = font.width(text) + 10
        val x0 = if (alignRight) x - w else x
        CardArt.drawCreeper(g, x0, y - 1, 8)
        g.text(font, text, x0 + 10, y, if (view.lit[seat] > 0) TableScreen.RED else TableScreen.GREEN, true)
    }

    /** What [seat] last did this round: laid down cards, or called a play. */
    private fun news(view: BluffView, seat: Seat): Component? {
        val event = view.roundLog.lastOrNull { it.seat == seat && (it.kind == BluffEventKind.PLAY || it.kind == BluffEventKind.CALL) } ?: return null
        return when (event.kind) {
            BluffEventKind.PLAY -> Component.translatable("tukuai.bluff.laid", event.count)
            BluffEventKind.CALL -> Component.translatable("tukuai.bluff.called")
            else -> null
        }
    }

    private fun name(table: ClientTable, seat: Seat): Component = PanelText.seatLabel(table, seat)

    /** The rank called for and the pile; under them the last play, the call turned over, or how the last fuse went. */
    private fun renderMiddle(g: GuiGraphicsExtractor, table: ClientTable, view: BluffView) {
        val cy = MIDDLE_Y
        val cx = width / 2
        val w = CardArt.WIDTH
        val h = CardArt.HEIGHT
        // Called for: the card, its name above.
        g.centeredText(font, Component.translatable("tukuai.bluff.calling", BluffUi.rankName(view.target), view.round), cx, cy - 12, TableScreen.GOLD)
        CardArt.drawCard(g, BluffUi.called(view.target), cx - w - 6, cy)
        // The pile, face down.
        if (view.pileCount > 0) {
            CardArt.draw(g, CardArt.back(), cx + 6, cy)
            g.text(font, Component.literal("× ${view.pileCount}"), cx + 6 + w + 4, cy + h / 2 - 4, TableScreen.YELLOW, true)
        }
        var y = cy + h + 6
        val light = BluffUi.recentLight(table)
        val reveal = view.reveal
        when {
            view.phase == BluffPhase.PENALTY && reveal != null -> {
                val caller = name(table, reveal.caller)
                val claimer = name(table, reveal.claimer)
                g.centeredText(font, Component.translatable("tukuai.bluff.calls", caller, claimer), cx, y, TableScreen.WHITE)
                PanelText.cards(g, BluffUi.sorted(reveal.cards), cx, y + 11, 0.75f, 18, PanelText.Align.CENTER)
                y += 11 + (h * 0.75f).toInt() + 3
                val verdict = if (reveal.lied) Component.translatable("tukuai.bluff.lied", claimer) else Component.translatable("tukuai.bluff.honest", claimer)
                g.centeredText(font, verdict, cx, y, if (reveal.lied) TableScreen.RED else TableScreen.GREEN)
                y += 11
                g.centeredText(font, penaltyLine(table, view, reveal.loser), cx, y, TableScreen.YELLOW)
            }
            light != null -> {
                val (event, age) = light
                val who = name(table, event.seat)
                val text = when {
                    age < BluffUi.HISS_TICKS -> Component.translatable(if (view.rules.penalty == BluffPenalty.FUSE) "tukuai.bluff.hiss" else "tukuai.bluff.heart_lost", who)
                    event.flag -> Component.translatable(if (view.rules.penalty == BluffPenalty.FUSE) "tukuai.bluff.boom" else "tukuai.bluff.hearts_gone", who)
                    else -> Component.translatable(if (view.rules.penalty == BluffPenalty.FUSE) "tukuai.bluff.fizzle" else "tukuai.bluff.heart_lost", who)
                }
                val color = if (age >= BluffUi.HISS_TICKS && event.flag) TableScreen.RED else TableScreen.YELLOW
                g.centeredText(font, text, cx, y, color)
            }
            view.claimSeat != null -> {
                val line = Component.translatable("tukuai.bluff.claims", name(table, view.claimSeat!!), view.claimCount, BluffUi.rankName(view.target))
                g.centeredText(font, line, cx, y, TableScreen.WHITE)
                // You know what you laid down, and a replay shows everyone's.
                if (view.claimCards.isNotEmpty()) {
                    PanelText.cards(g, BluffUi.sorted(view.claimCards), cx, y + 11, 0.5f, 12, PanelText.Align.CENTER)
                }
            }
        }
    }

    /** "Alex lights the fuse: 1 in 4 it blows", or with hearts, "Alex loses a heart". */
    private fun penaltyLine(table: ClientTable, view: BluffView, seat: Seat): Component {
        val who = name(table, seat)
        return if (view.rules.penalty == BluffPenalty.FUSE) {
            Component.translatable("tukuai.bluff.odds", who, view.rules.chances - view.lit[seat])
        } else {
            Component.translatable(BluffUi.penaltyKey(view), who)
        }
    }

    /** Your name, creeper and timer by the buttons; on your turn what the rules ask of you over them. */
    private fun renderSelf(g: GuiGraphicsExtractor, table: ClientTable, view: BluffView, me: Seat) {
        val y = buttonY() + 4
        val x = 8
        val seen = table.snapshot.you != null || view.revealedHands.isNotEmpty()
        g.text(font, if (!seen) Component.translatable("tukuai.table.watching") else PanelText.seatLabel(table, me), x, y, TableScreen.WHITE, true)
        if (!seen) return
        if (view.out[me]) {
            g.text(font, Component.translatable("tukuai.bluff.out"), x, y + 12, TableScreen.RED, true)
            return
        }
        renderChances(g, view, me, x, y + 12, alignRight = false)
        if (view.toAct == me && table.snapshot.status == TableStatus.PLAYING) PanelText.timer(g, table, x + 50, y + 12, alignRight = false)
        if (!myTurn(table, view)) return
        val note = when {
            view.phase == BluffPhase.PENALTY -> null
            view.mustCall -> Component.translatable("tukuai.bluff.note.must_call")
            view.claimSeat == null -> Component.translatable("tukuai.bluff.note.lead", view.rules.maxPlay, BluffUi.rankName(view.target))
            else -> Component.translatable("tukuai.bluff.note.answer", view.rules.maxPlay, BluffUi.rankName(view.target))
        }
        if (note != null) g.centeredText(font, note, width / 2, buttonY() - 12, TableScreen.YELLOW)
        if (selected.isNotEmpty() && view.phase == BluffPhase.PLAY) {
            val problem = selected.size > view.rules.maxPlay
            val honest = selected.all { Bluff.matches(it, view.target) }
            val text = when {
                problem -> Component.translatable("tukuai.bluff.picked.too_many", view.rules.maxPlay)
                honest -> Component.translatable("tukuai.bluff.picked.honest", selected.size)
                else -> Component.translatable("tukuai.bluff.picked.lie", selected.size)
            }
            PanelText.text(g, text, width / 2 + 88, buttonY() + 6, if (problem) TableScreen.RED else if (honest) TableScreen.GREEN else TableScreen.YELLOW, PanelText.Align.LEFT)
        }
    }

    private fun resultTop() = 56

    private fun resultHeight(view: BluffView) = 34 + view.players * 11 + 26

    /** The winner, then each player with how often they lit the fuse and their points. */
    private fun renderResult(g: GuiGraphicsExtractor, table: ClientTable, view: BluffView) {
        val winner = view.winner ?: return
        val w = 212
        val x = width / 2 - w / 2
        val top = resultTop()
        g.fill(x, top, x + w, top + resultHeight(view), 0xE0101010.toInt())
        val me = table.snapshot.you
        val color = if (me == null) TableScreen.WHITE else if (me == winner) TableScreen.GREEN else TableScreen.RED
        g.centeredText(font, Component.translatable("tukuai.bluff.won", name(table, winner)), width / 2, top + 6, color)
        g.centeredText(font, Component.translatable("tukuai.bluff.result_detail", view.round), width / 2, top + 18, TableScreen.GRAY)
        val result = view.players - 1
        for (seat in 0 until view.players) {
            val y = top + 32 + seat * 11
            val points = if (seat == winner) result else -1
            val score = Component.literal(if (points > 0) "+$points" else points.toString())
            val lit = Component.translatable(if (view.rules.penalty == BluffPenalty.FUSE) "tukuai.bluff.result.lit" else "tukuai.bluff.result.hearts", view.lit[seat])
            val litX = x + w - 40 - font.width(lit)
            g.text(font, font.plainSubstrByWidth(name(table, seat).string, litX - x - 14), x + 8, y, if (seat == me) TableScreen.YELLOW else TableScreen.WHITE, false)
            g.text(font, lit, litX, y, TableScreen.GRAY, false)
            g.text(font, score, x + w - 8 - font.width(score), y, if (points >= 0) TableScreen.GREEN else TableScreen.RED, false)
        }
    }

    companion object {
        /** Width of a player's box. */
        private const val BOX = 92

        /** Top of the cards in the middle, under the player across the table. */
        private const val MIDDLE_Y = 64

        private const val HEART_RGB = 0xFF5555
        private const val EMPTY_RGB = 0x555555
    }
}
