package io.github.konyaco.tukuai.client.gd

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientConfig
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.PanelText
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.guandan.GdAction
import io.github.konyaco.tukuai.engine.guandan.GdCodecs
import io.github.konyaco.tukuai.engine.guandan.GdEvent
import io.github.konyaco.tukuai.engine.guandan.GdEventKind
import io.github.konyaco.tukuai.engine.guandan.GdPhase
import io.github.konyaco.tukuai.engine.guandan.GdTurn
import io.github.konyaco.tukuai.engine.guandan.GdView
import io.github.konyaco.tukuai.engine.guandan.Guandan
import io.github.konyaco.tukuai.engine.guandan.bot.GdBot
import io.github.konyaco.tukuai.engine.guandan.isWild
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW

/**
 * The Guandan part of the table screen: your hand along the bottom, in two rows when it
 * is long, your partner across the top and the other team on either side. Seat boxes are
 * tinted by team, and the level being played sits at the top in the middle.
 */
class GdPanel(private val screen: PanelHost) : GamePanel {
    private val picker = GdHand(screen)
    private val selected get() = picker.selected
    private var wasMyTurn = false

    private val width get() = screen.panelWidth
    private val font get() = Minecraft.getInstance().font

    private fun send(action: GdAction) = screen.send(TableOp.ACT, data = GdCodecs.ACTION.encode(action))

    private fun buttonY() = picker.buttonY()

    override fun controlsY(): Int = buttonY()

    /** Your own hand, or for a watcher who may see every hand, that of seat 0 at the bottom. */
    private fun shownHand(view: GdView): List<PlayingCard> =
        GdUi.sorted(view, if (view.seat == null) view.revealedHands.getOrNull(0).orEmpty() else view.hand)

    private fun live(table: ClientTable) = screen.interactive && table.snapshot.status == TableStatus.PLAYING

    /** What you may play now, when it is your turn to play. */
    private fun turn(table: ClientTable, view: GdView): GdTurn? = if (live(table)) view.turnRules() else null

    private fun owesReturn(table: ClientTable, view: GdView): Boolean = live(table) && view.owedReturn != null

    /** The one picked card, when it may be given back. */
    private fun returnChoice(view: GdView): PlayingCard? = selected.singleOrNull()?.takeIf { it in Guandan.returnable(view.hand) }

    // ---- Buttons -------------------------------------------------------------------------------

    override fun rebuild(table: ClientTable) {
        val view = table.view as? GdView ?: return
        val s = table.snapshot
        picker.sync(s.step, shownHand(view))
        if (!screen.interactive) return
        val myTurn = live(table) && view.isMyTurn && view.phase != GdPhase.ROUND_OVER
        if (myTurn && !wasMyTurn) {
            Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f))
        }
        wasMyTurn = myTurn

        val me = s.you
        if (s.status == TableStatus.PLAYING && me != null) {
            val auto = s.seats.getOrNull(me)?.auto == true
            screen.button(Component.translatable(if (auto) "tukuai.table.auto.off" else "tukuai.table.auto.on"), width - 74, 48, 70) {
                screen.send(TableOp.AUTO, if (auto) 0 else 1)
            }
        }
        val resultBottom = RESULT_TOP + resultHeight(view)
        if (view.phase == GdPhase.ROUND_OVER && live(table) && view.isMyTurn) {
            screen.button(Component.translatable("tukuai.guandan.next"), width / 2 - 49, resultBottom - 24, 98) { send(GdAction.Next) }
        }
        if (s.status == TableStatus.FINISHED) {
            val ready = me != null && s.seats.all { it.occupant != null }
            screen.button(Component.translatable("tukuai.table.again"), width / 2 - 100, resultBottom - 24, 98, ready) {
                screen.send(TableOp.START)
            }
            screen.button(Component.translatable("tukuai.table.replay"), width / 2 + 2, resultBottom - 24, 98) {
                screen.send(TableOp.REPLAY)
            }
        }
        if (owesReturn(table, view)) {
            val choice = returnChoice(view)
            screen.button(Component.translatable("tukuai.guandan.give_back"), width / 2 - 40, buttonY(), 80, choice != null) {
                choice?.let { send(GdAction.Return(it)) }
            }
            return
        }
        val turn = turn(table, view) ?: return
        val w = 56
        val gap = 6
        var x = (width - (3 * w + 2 * gap)) / 2
        val y = buttonY()
        screen.button(Component.translatable("tukuai.ddz.pass"), x, y, w, turn.canPass) { send(GdAction.Pass) }
        x += w + gap
        screen.button(Component.translatable("tukuai.ddz.hint"), x, y, w) { hint(view, turn) }
        x += w + gap
        screen.button(Component.translatable("tukuai.ddz.play"), x, y, w, turn.reading(selected) != null) {
            send(GdAction.PlayCards(selected))
        }
    }

    /** Picks cards for the player: first what the computer would play, then every other legal play in turn. */
    private fun hint(view: GdView, turn: GdTurn) {
        val found = picker.hint {
            val suggestion = (GdBot(BotLevel.NORMAL, 0, 0).act(view) as? GdAction.PlayCards)?.cards
            val options = turn.plays.sortedWith(compareBy({ it.pattern.isBomb }, { it.pattern.tier }, { it.pattern.main }, { -it.cards.size })).map { it.cards }
            listOfNotNull(suggestion) + options
        }
        if (!found && turn.canPass) send(GdAction.Pass)
    }

    // ---- Input ---------------------------------------------------------------------------------

    override fun mouseClicked(table: ClientTable, x: Double, y: Double, button: Int): Boolean {
        if (!screen.interactive) return false
        val view = table.view as? GdView ?: return false
        if (view.seat == null) return false
        return picker.click(x, y, button)
    }

    override fun keyPressed(table: ClientTable, key: Int): Boolean {
        val view = table.view as? GdView ?: return false
        if (owesReturn(table, view)) {
            if (key != GLFW.GLFW_KEY_SPACE && key != GLFW.GLFW_KEY_ENTER) return false
            returnChoice(view)?.let { send(GdAction.Return(it)) }
            return true
        }
        val turn = turn(table, view) ?: return false
        return when (key) {
            GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_ENTER -> {
                if (turn.reading(selected) != null) {
                    send(GdAction.PlayCards(selected))
                } else if (selected.isEmpty() && turn.canPass) {
                    send(GdAction.Pass)
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

    private enum class Place { LEFT, TOP, RIGHT }

    override fun render(g: GuiGraphicsExtractor, table: ClientTable, mouseX: Int, mouseY: Int) {
        val view = table.view as? GdView ?: return
        val s = table.snapshot
        val me = s.you ?: 0
        val recent = recentActions(view)
        val over = view.phase == GdPhase.ROUND_OVER || view.phase == GdPhase.FINISHED

        PanelText.title(g, table, screen.interactive, width)
        if (!over) renderLevels(g, view, me)
        renderBox(g, table, view, (me + 3) % 4, recent[(me + 3) % 4], Place.LEFT)
        renderBox(g, table, view, (me + 2) % 4, recent[(me + 2) % 4], Place.TOP)
        renderBox(g, table, view, (me + 1) % 4, recent[(me + 1) % 4], Place.RIGHT)

        if (!over) {
            recent[me]?.let { renderEvent(g, view, it, width / 2, buttonY() - 28, PanelText.Align.CENTER, scale = 0.5f, step = 9) }
            renderTributes(g, table, view)
            renderNote(g, table, view)
        }
        renderSelf(g, table, view, me)
        picker.render(g, mouseX, mouseY) { it.isWild(view.levelRank) }
        if (over) renderResult(g, table, view)
    }

    /** The level being played and whose it is, then both teams' levels. */
    private fun renderLevels(g: GuiGraphicsExtractor, view: GdView, me: Seat) {
        val mine = Guandan.team(me)
        val whose = when (view.levelTeam) {
            -1 -> Component.translatable("tukuai.guandan.level.first")
            mine -> Component.translatable("tukuai.guandan.level.ours")
            else -> Component.translatable("tukuai.guandan.level.theirs")
        }
        val line = Component.translatable("tukuai.guandan.level", GdUi.levelName(view.level)).copy().append(" ").append(whose)
        g.centeredText(font, line, width / 2, 4, TableScreen.YELLOW)
        val levels = Component.translatable(
            "tukuai.guandan.levels",
            Component.literal(GdUi.levelName(view.levels[mine])).withColor(OURS_RGB),
            Component.literal(GdUi.levelName(view.levels[1 - mine])).withColor(THEIRS_RGB),
        )
        g.centeredText(font, levels, width / 2, 14, TableScreen.GRAY)
    }

    private fun teamTint(me: Seat, seat: Seat, acting: Boolean): Int {
        val ours = Guandan.team(seat) == Guandan.team(me)
        return when {
            acting -> 0xA0806020.toInt()
            ours -> 0x80204A80.toInt()
            else -> 0x80802828.toInt()
        }
    }

    private fun renderBox(g: GuiGraphicsExtractor, table: ClientTable, view: GdView, seat: Seat, recent: GdEvent?, place: Place) {
        val compact = ClientConfig.immersive && screen.interactive
        val boxW = if (compact) 74 else 96
        val margin = if (compact) 4 else 8
        val x = when (place) {
            Place.LEFT -> margin
            Place.TOP -> width / 2 - boxW / 2
            Place.RIGHT -> width - margin - boxW
        }
        val y = if (place == Place.TOP) TOP_BOX_Y else SIDE_BOX_Y
        val me = table.snapshot.you ?: 0
        val acting = view.turn == seat && live(table) && (view.phase == GdPhase.PLAYING || view.phase == GdPhase.RETURN)
        g.fill(x - 2, y - 2, x + boxW + 2, y + BOX_H, teamTint(me, seat, acting))
        g.fill(x - 2, y - 2, x + boxW + 2, y - 1, if (Guandan.team(seat) == Guandan.team(me)) 0xFF000000.toInt() or OURS_RGB else 0xFF000000.toInt() or THEIRS_RGB)
        val partnerTag = if (Guandan.team(seat) == Guandan.team(me) && table.snapshot.you != null) Component.translatable("tukuai.guandan.partner") else null
        val nameRoom = boxW - (partnerTag?.let { font.width(it) + 4 } ?: 0)
        g.text(font, font.plainSubstrByWidth(PanelText.seatLabel(table, seat).string, nameRoom), x, y + 2, TableScreen.WHITE, true)

        val count = view.handCounts[seat]
        val place1 = view.order.indexOf(seat)
        if (place1 >= 0) {
            g.text(font, Component.translatable("tukuai.guandan.place.${place1 + 1}"), x, y + 18, TableScreen.YELLOW, true)
        } else {
            CardArt.draw(g, CardArt.back(), x, y + 13, 0.5f)
            g.text(font, Component.literal("× $count"), x + 20, y + 18, if (count <= 5) TableScreen.RED else TableScreen.YELLOW, true)
        }
        if (partnerTag != null) g.text(font, partnerTag, x + boxW - font.width(partnerTag), y + 2, OURS_TEXT, true)
        if (acting) PanelText.timer(g, table, x + boxW - 4, y + 28, alignRight = true)

        // Their whole hand once it is shown, below the box (or beside it, across the table).
        val revealed = view.revealedHands.getOrNull(seat)
        val align = if (place == Place.RIGHT) PanelText.Align.RIGHT else PanelText.Align.LEFT
        val over = view.phase == GdPhase.ROUND_OVER || view.phase == GdPhase.FINISHED
        if (revealed != null && revealed.isNotEmpty()) {
            val cards = GdUi.sorted(view, revealed)
            when (place) {
                // Once the round is over the result covers the top, so the hand goes below it.
                Place.TOP -> if (over) {
                    PanelText.cards(g, cards, width / 2, RESULT_TOP + resultHeight(view) + 4, 0.5f, 6, PanelText.Align.CENTER)
                } else {
                    PanelText.cards(g, cards, x + boxW + 8, y, 0.5f, 6, PanelText.Align.LEFT)
                }
                Place.LEFT -> PanelText.cards(g, cards, x, y + BOX_H + 4, 0.5f, 5, PanelText.Align.LEFT)
                Place.RIGHT -> PanelText.cards(g, cards, x + boxW, y + BOX_H + 4, 0.5f, 5, PanelText.Align.RIGHT)
            }
        }
        if (recent == null || over) return
        when (place) {
            Place.TOP -> renderEvent(g, view, recent, width / 2, y + BOX_H + 4, PanelText.Align.CENTER, scale = 0.5f, step = 9)
            Place.LEFT -> renderEvent(g, view, recent, x + boxW + 8, y + 4, PanelText.Align.LEFT, scale = 0.5f, step = 9)
            Place.RIGHT -> renderEvent(g, view, recent, x - 8, y + 4, PanelText.Align.RIGHT, scale = 0.5f, step = 9)
        }
    }

    private fun renderSelf(g: GuiGraphicsExtractor, table: ClientTable, view: GdView, me: Seat) {
        val y = buttonY() + 2
        val x = 8
        val seen = table.snapshot.you != null || view.revealedHands.isNotEmpty()
        val label = if (!seen) Component.translatable("tukuai.table.watching") else PanelText.seatLabel(table, me)
        g.text(font, label, x, y, TableScreen.WHITE, true)
        if (!seen) return
        val place = view.order.indexOf(me)
        if (place >= 0) {
            g.text(font, Component.translatable("tukuai.guandan.place.${place + 1}"), x, y + 11, TableScreen.YELLOW, true)
        } else if (view.turn == me && live(table)) {
            PanelText.timer(g, table, x, y + 11, alignRight = false)
        }
    }

    private fun renderEvent(g: GuiGraphicsExtractor, view: GdView, event: GdEvent, x: Int, y: Int, align: PanelText.Align, scale: Float, step: Int) {
        when (event.kind) {
            GdEventKind.PLAY -> {
                val cards = GdUi.sorted(view, event.cards)
                PanelText.cards(g, cards, x, y, scale, step, align)
                val total = (CardArt.WIDTH * scale).toInt() + step * (cards.size - 1)
                val x0 = when (align) {
                    PanelText.Align.LEFT -> x
                    PanelText.Align.CENTER -> x - total / 2
                    PanelText.Align.RIGHT -> x - total
                }
                cards.forEachIndexed { i, card -> if (card.isWild(view.levelRank)) GdUi.wildFrame(g, x0 + i * step, y, scale) }
                if (event.pattern?.isBomb == true) {
                    val name = Component.translatable("tukuai.guide.guandan.pattern.${event.pattern.type.name.lowercase()}")
                    PanelText.text(g, name, x0 + total / 2, y + (CardArt.HEIGHT * scale).toInt() + 1, TableScreen.RED, PanelText.Align.CENTER)
                }
            }
            GdEventKind.PASS -> PanelText.text(g, Component.translatable("tukuai.ddz.pass"), x, y + 6, TableScreen.YELLOW, align)
            else -> {}
        }
    }

    /** Tributes before the first play of a round: who paid whom which card, and what came back. */
    private fun renderTributes(g: GuiGraphicsExtractor, table: ClientTable, view: GdView) {
        if (view.log.any { it.kind == GdEventKind.PLAY }) return
        var y = TRIBUTE_Y
        val noTribute = view.log.firstOrNull { it.kind == GdEventKind.NO_TRIBUTE }
        if (noTribute != null) {
            g.centeredText(font, Component.translatable("tukuai.guandan.no_tribute"), width / 2, y, TableScreen.GRAY)
            return
        }
        for (t in view.tributes) {
            val line = Component.translatable("tukuai.guandan.tribute", GdUi.name(table, t.from), GdUi.name(table, t.to))
            val w = font.width(line)
            val x = width / 2 - (w + 4 + CardArt.WIDTH / 2) / 2
            g.text(font, line, x, y + 6, TableScreen.WHITE, true)
            CardArt.drawCard(g, t.card, x + w + 4, y, 0.5f)
            val back = t.returned
            if (back != null) {
                val text = Component.translatable("tukuai.guandan.returned")
                g.text(font, text, x + w + 4 + CardArt.WIDTH / 2 + 6, y + 6, TableScreen.GRAY, true)
                CardArt.drawCard(g, back, x + w + 4 + CardArt.WIDTH / 2 + 10 + font.width(text), y, 0.5f)
            }
            y += CardArt.HEIGHT / 2 + 2
        }
    }

    /** One line over the buttons for what this turn asks of you. */
    private fun renderNote(g: GuiGraphicsExtractor, table: ClientTable, view: GdView) {
        if (!live(table)) return
        val note: Component = when {
            view.owedReturn != null -> Component.translatable("tukuai.guandan.note.give_back", GdUi.name(table, view.owedReturn!!.from))
            view.phase == GdPhase.RETURN -> Component.translatable("tukuai.guandan.giving_back", GdUi.name(table, view.turn))
            view.isMyTurn && view.log.lastOrNull()?.kind == GdEventKind.PARTNER_LEADS -> Component.translatable("tukuai.guandan.note.partner_leads")
            view.isMyTurn && view.toBeat != null && view.turnRules()?.plays?.isEmpty() == true -> Component.translatable("tukuai.guandan.note.nothing")
            else -> return
        }
        val lines = font.split(note, width - 40)
        lines.forEachIndexed { i, line ->
            g.centeredText(font, line, width / 2, buttonY() - 12 - (lines.size - 1 - i) * 10, TableScreen.YELLOW)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun resultHeight(view: GdView): Int = 22 + 4 * ROW + 16 + 28

    private fun renderResult(g: GuiGraphicsExtractor, table: ClientTable, view: GdView) {
        val round = view.rounds.lastOrNull() ?: return
        val w = 220
        val x = width / 2 - w / 2
        val top = RESULT_TOP
        g.fill(x, top, x + w, top + resultHeight(view), 0xF8101010.toInt())
        val me = table.snapshot.you
        val winner = round.order[0]
        val color = if (me == null) TableScreen.WHITE else if (Guandan.team(me) == Guandan.team(winner)) TableScreen.GREEN else TableScreen.RED
        val title = if (view.finished && view.rules.toAce) "tukuai.guandan.won_game" else "tukuai.guandan.won"
        g.centeredText(font, Component.translatable(title, GdUi.name(table, winner), GdUi.name(table, Guandan.partner(winner))), width / 2, top + 6, color)
        // Each place: who, their team's level gain or loss this round, and points so far.
        round.order.forEachIndexed { place, seat ->
            val y = top + 22 + place * ROW
            val nameColor = if (seat == me) TableScreen.YELLOW else TableScreen.WHITE
            g.text(font, Component.translatable("tukuai.guandan.place.${place + 1}"), x + 8, y, TableScreen.GRAY, false)
            g.text(font, font.plainSubstrByWidth(PanelText.seatLabel(table, seat).string, w - 90), x + 36, y, nameColor, false)
            val points = view.scores[seat]
            val text = Component.literal(if (points > 0) "+$points" else points.toString())
            g.text(font, text, x + w - 8 - font.width(text), y, if (points >= 0) TableScreen.GREEN else TableScreen.RED, false)
        }
        val mine = Guandan.team(me ?: 0)
        val levels = Component.translatable(
            "tukuai.guandan.result.levels",
            round.gain,
            Component.literal(GdUi.levelName(round.levelsAfter[mine])).withColor(OURS_RGB),
            Component.literal(GdUi.levelName(round.levelsAfter[1 - mine])).withColor(THEIRS_RGB),
        )
        g.centeredText(font, levels, width / 2, top + 22 + 4 * ROW + 2, TableScreen.GRAY)
        if (view.phase == GdPhase.ROUND_OVER && !(screen.interactive && view.isMyTurn)) {
            g.centeredText(font, Component.translatable("tukuai.guandan.waiting", GdUi.name(table, view.turn)), width / 2, top + resultHeight(view) - 18, TableScreen.GRAY)
        }
    }

    companion object {
        private const val TOP_BOX_Y = 26
        private const val SIDE_BOX_Y = 66
        private const val BOX_H = 36
        private const val TRIBUTE_Y = 84
        private const val RESULT_TOP = 4
        private const val ROW = 12

        const val OURS_RGB = 0x6FA8FF
        const val THEIRS_RGB = 0xFF7A6A
        private const val OURS_TEXT = 0xFF6FA8FF.toInt()

        /** Each seat's latest play or pass since the table was last cleared; the seat to act shows nothing. */
        fun recentActions(view: GdView): Map<Seat, GdEvent> {
            val out = HashMap<Seat, GdEvent>()
            if (view.lead == null && view.phase == GdPhase.PLAYING) return out
            for (e in view.log.asReversed()) {
                if (e.kind == GdEventKind.PARTNER_LEADS) break
                if (e.kind != GdEventKind.PLAY && e.kind != GdEventKind.PASS) continue
                out.putIfAbsent(e.seat, e)
                // Back to the play everyone is answering: anything older belongs to an earlier lead.
                if (view.lead != null && e.kind == GdEventKind.PLAY && e.seat == view.lead.seat && e.cards == view.lead.cards) break
            }
            if (view.phase == GdPhase.PLAYING) out.remove(view.turn)
            return out
        }
    }
}
