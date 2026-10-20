package io.github.konyaco.tukuai.client.ddz

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
import io.github.konyaco.tukuai.engine.doudizhu.BiddingMode
import io.github.konyaco.tukuai.engine.doudizhu.DDZ_ORDER
import io.github.konyaco.tukuai.engine.doudizhu.DdzAction
import io.github.konyaco.tukuai.engine.doudizhu.DdzCodecs
import io.github.konyaco.tukuai.engine.doudizhu.DdzEvent
import io.github.konyaco.tukuai.engine.doudizhu.DdzEventKind
import io.github.konyaco.tukuai.engine.doudizhu.DdzPhase
import io.github.konyaco.tukuai.engine.doudizhu.DdzView
import io.github.konyaco.tukuai.engine.doudizhu.DouDizhu
import io.github.konyaco.tukuai.engine.doudizhu.Patterns
import io.github.konyaco.tukuai.engine.doudizhu.bot.DdzBot
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW

/**
 * The Dou Dizhu part of the table screen: your hand along the bottom, the other two
 * players left and right (the next player is on your right, as play goes
 * counterclockwise), the landlord's three cards at the top.
 */
class DdzPanel(private val screen: PanelHost) : GamePanel {
    private val picker = PlayingCardHand(screen)
    private val selected get() = picker.selected
    private var wasMyTurn = false

    private val width get() = screen.panelWidth
    private val height get() = screen.panelHeight
    private val font get() = Minecraft.getInstance().font

    private fun send(action: DdzAction) = screen.send(TableOp.ACT, data = DdzCodecs.ACTION.encode(action))

    // ---- Layout --------------------------------------------------------------------------------

    private fun buttonY() = picker.buttonY()

    override fun controlsY(): Int = buttonY()

    /** Your own hand, or for a watcher who may see every hand, that of seat 0 at the bottom. */
    private fun sortedHand(view: DdzView): List<PlayingCard> =
        (if (view.seat == null) view.revealedHands.getOrNull(0).orEmpty() else view.hand).sortedWith(DDZ_ORDER.reversed())

    // ---- Buttons -------------------------------------------------------------------------------

    override fun rebuild(table: ClientTable) {
        val view = table.view as? DdzView ?: return
        val s = table.snapshot
        picker.sync(s.step, view.hand)
        if (!screen.interactive) return
        val myTurn = s.status == TableStatus.PLAYING && view.isMyTurn
        if (myTurn && !wasMyTurn) {
            Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f))
        }
        wasMyTurn = myTurn

        if (s.status == TableStatus.FINISHED) screen.finishButtons(table, width / 2, RESULT_TOP + 74)
        if (myTurn) addTurnButtons(view)
    }

    private fun addTurnButtons(view: DdzView) {
        val y = buttonY()
        val buttons = ArrayList<Triple<Component, Boolean, () -> Unit>>()
        when (view.phase) {
            DdzPhase.BIDDING -> when (view.rules.bidding) {
                BiddingMode.SCORE -> for (points in 0..3) {
                    val label = if (points == 0) Component.translatable("tukuai.ddz.no_bid") else Component.translatable("tukuai.ddz.bid", points)
                    buttons += Triple(label, points in view.allowedBids) { send(DdzAction.Bid(points)) }
                }
                BiddingMode.ROB -> {
                    val robbing = view.robbing
                    buttons += Triple(Component.translatable(if (robbing) "tukuai.ddz.no_rob" else "tukuai.ddz.no_call"), true) { send(DdzAction.Call(false)) }
                    buttons += Triple(Component.translatable(if (robbing) "tukuai.ddz.rob" else "tukuai.ddz.call"), true) { send(DdzAction.Call(true)) }
                }
            }
            DdzPhase.DOUBLING -> {
                buttons += Triple(Component.translatable("tukuai.ddz.no_double"), true) { send(DdzAction.Double(false)) }
                buttons += Triple(Component.translatable("tukuai.ddz.double"), true) { send(DdzAction.Double(true)) }
            }
            DdzPhase.PLAYING -> {
                val toBeat = view.toBeat
                buttons += Triple(Component.translatable("tukuai.ddz.pass"), toBeat != null) { send(DdzAction.Pass) }
                buttons += Triple(Component.translatable("tukuai.ddz.hint"), true) { hint(view) }
                val valid = selected.isNotEmpty() && Patterns.readingFor(selected, toBeat, view.rules) != null
                buttons += Triple(Component.translatable("tukuai.ddz.play"), valid) { send(DdzAction.PlayCards(selected.toList())) }
            }
            DdzPhase.FINISHED -> {}
        }
        val w = 56
        val gap = 6
        var x = (width - (buttons.size * w + (buttons.size - 1) * gap)) / 2
        for ((label, active, action) in buttons) {
            screen.button(label, x, y, w, active) { action() }
            x += w + gap
        }
    }

    /** Picks cards for the player: first what the computer would play, then every other legal play in turn. */
    private fun hint(view: DdzView) {
        val found = picker.hint {
            val options = Patterns.options(view.hand, view.toBeat, view.rules)
                .sortedWith(compareBy({ it.pattern.isBomb }, { it.pattern.main }, { -it.cards.size }))
                .map { it.cards }
            val suggestion = (DdzBot(BotLevel.NORMAL, 0).act(view) as? DdzAction.PlayCards)?.cards
            listOfNotNull(suggestion) + options
        }
        if (!found) send(DdzAction.Pass)
    }

    // ---- Input ---------------------------------------------------------------------------------

    override fun mouseClicked(table: ClientTable, x: Double, y: Double, button: Int): Boolean {
        if (!screen.interactive) return false
        val view = table.view as? DdzView ?: return false
        if (view.seat == null) return false
        return picker.click(sortedHand(view), x, y, button)
    }

    override fun clickTableCard(table: ClientTable, card: TableCard): Boolean {
        if (!screen.interactive) return false
        val view = table.view as? DdzView ?: return false
        return picker.clickTableCard(view.hand, card)
    }

    override fun isRaised(table: ClientTable, card: TableCard): Boolean = picker.isRaised(card)

    override fun mouseDragged(table: ClientTable, x: Double, y: Double): Boolean {
        val view = table.view as? DdzView ?: return false
        return picker.drag(sortedHand(view), x)
    }

    override fun mouseReleased(table: ClientTable) = picker.release()

    override fun dragTableCard(table: ClientTable, card: TableCard) {
        val view = table.view as? DdzView ?: return
        picker.dragTableCard(view.hand, card)
    }

    override fun keyPressed(table: ClientTable, key: Int): Boolean {
        if (!screen.interactive) return false
        val view = table.view as? DdzView ?: return false
        if (!view.isMyTurn || view.phase != DdzPhase.PLAYING || table.snapshot.status != TableStatus.PLAYING) return false
        return when (key) {
            GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_ENTER -> {
                if (selected.isNotEmpty() && Patterns.readingFor(selected, view.toBeat, view.rules) != null) {
                    send(DdzAction.PlayCards(selected.toList()))
                } else if (view.toBeat != null) {
                    send(DdzAction.Pass)
                }
                true
            }
            GLFW.GLFW_KEY_H -> {
                hint(view)
                true
            }
            else -> false
        }
    }

    // ---- Drawing -------------------------------------------------------------------------------

    override fun render(g: GuiGraphicsExtractor, table: ClientTable, mouseX: Int, mouseY: Int) {
        val view = table.view as? DdzView ?: return
        val s = table.snapshot
        val me = s.you ?: 0
        val right = (me + 1) % DouDizhu.SEATS
        val left = (me + 2) % DouDizhu.SEATS
        val recent = recentActions(view)
        val finished = s.status == TableStatus.FINISHED

        if (!finished) renderTop(g, table, view) else renderTitle(g, table)
        renderOpponent(g, table, view, left, recent[left], onLeft = true)
        renderOpponent(g, table, view, right, recent[right], onLeft = false)

        // Your own last move, above your hand.
        if (!finished) {
            recent[me]?.let { renderEvent(g, view, it, width / 2, buttonY() - 40, PanelText.Align.CENTER) }
        }
        renderSelf(g, table, view, me)
        picker.render(g, sortedHand(view), mouseX, mouseY)
        if (finished) renderResult(g, table, view)
    }

    private fun renderTitle(g: GuiGraphicsExtractor, table: ClientTable) = PanelText.title(g, table, screen.interactive, width)

    private fun renderTop(g: GuiGraphicsExtractor, table: ClientTable, view: DdzView) {
        renderTitle(g, table)

        // The landlord's three cards: face down until someone takes them.
        val scale = 0.75f
        val cw = (CardArt.WIDTH * scale).toInt()
        val x0 = width / 2 - (cw * 3 + 4) / 2
        for (i in 0 until 3) {
            val card = view.kitty.getOrNull(i)
            val x = x0 + i * (cw + 2)
            if (card != null) CardArt.drawCard(g, card, x, 6, scale) else CardArt.draw(g, CardArt.back(), x, 6, scale)
        }
        val line = Component.translatable("tukuai.ddz.stake", view.rules.baseScore, multiplier(view))
        g.centeredText(font, line, width / 2, 6 + (CardArt.HEIGHT * scale).toInt() + 4, TableScreen.WHITE)
    }

    private fun renderOpponent(g: GuiGraphicsExtractor, table: ClientTable, view: DdzView, seat: Seat, recent: DdzEvent?, onLeft: Boolean) {
        // With the world in view, the table's own hands stand in the middle of the screen, so
        // the boxes shrink to the edges and keep everything they show in their own column.
        val compact = ClientConfig.immersive && screen.interactive
        val boxW = if (compact) 74 else 96
        val margin = if (compact) 4 else 8
        val x = if (onLeft) margin else width - margin - boxW
        val y = 70
        val acting = view.turn == seat && table.snapshot.status == TableStatus.PLAYING
        g.fill(x - 2, y - 2, x + boxW + 2, y + 54, if (acting) 0x90806020.toInt() else 0x70000000)
        if (view.landlord != null) CardArt.drawHat(g, view.isLandlord(seat), x, y)
        val nameX = if (view.landlord != null) x + 18 else x
        g.text(font, font.plainSubstrByWidth(PanelText.seatLabel(table, seat).string, boxW - (nameX - x)), nameX, y + 4, TableScreen.WHITE, true)

        CardArt.draw(g, CardArt.back(), x, y + 18, 0.5f)
        g.text(font, Component.literal("× ${view.handCounts[seat]}"), x + 20, y + 26, TableScreen.YELLOW, true)
        if (view.doubles.getOrNull(seat) == true) {
            if (compact) g.text(font, Component.translatable("tukuai.ddz.doubled"), x, y + 42, TableScreen.RED, true)
            else g.text(font, Component.translatable("tukuai.ddz.doubled"), x + 56, y + 26, TableScreen.RED, true)
        }
        if (acting) PanelText.timer(g, table, x + boxW - 4, y + 42, alignRight = true)

        // What they just did beside the box, or below it when compact. Their whole hand, once
        // it is shown (at the end, or in a replay), goes below the box so the result in the
        // middle does not cover it.
        val align = if (onLeft) PanelText.Align.LEFT else PanelText.Align.RIGHT
        val edgeX = if (onLeft) x else x + boxW
        val revealed = view.revealedHands.getOrNull(seat)
        val finished = table.snapshot.status == TableStatus.FINISHED
        if (revealed != null) {
            // A watcher sees hands while play goes on; with the box compact, its last move comes first.
            val below = if (compact && !finished && recent != null) 58 + CardArt.HEIGHT / 2 + 4 else 60
            PanelText.cards(g, revealed.sortedWith(DDZ_ORDER.reversed()), edgeX, y + below, 0.5f, 7, align)
        }
        if (recent == null || (finished && revealed != null)) return
        if (compact) {
            renderEvent(g, view, recent, edgeX, if (recent.kind == DdzEventKind.PLAY) y + 58 else y + 48, align, scale = 0.5f, step = 7)
        } else {
            renderEvent(g, view, recent, if (onLeft) x + boxW + 8 else x - 8, y + 4, align)
        }
    }

    private fun renderSelf(g: GuiGraphicsExtractor, table: ClientTable, view: DdzView, me: Seat) {
        val y = buttonY() + 4
        val x = 8
        // A watcher who sees every hand watches from seat 0; one who does not just watches.
        val seen = table.snapshot.you != null || view.revealedHands.isNotEmpty()
        if (view.landlord != null && seen) CardArt.drawHat(g, view.isLandlord(me), x, y - 4)
        val label = if (!seen) Component.translatable("tukuai.table.watching") else PanelText.seatLabel(table, me)
        g.text(font, label, x + 20, y, TableScreen.WHITE, true)
        if (view.turn == me && table.snapshot.status == TableStatus.PLAYING && seen) {
            PanelText.timer(g, table, x + 20, y + 12, alignRight = false)
        }
    }

    private fun renderEvent(g: GuiGraphicsExtractor, view: DdzView, event: DdzEvent, x: Int, y: Int, align: PanelText.Align, scale: Float = 0.75f, step: Int = 13) {
        if (event.kind == DdzEventKind.PLAY) {
            PanelText.cards(g, event.cards.sortedWith(DDZ_ORDER.reversed()), x, y, scale, step, align)
            return
        }
        val text = eventText(event) ?: return
        PanelText.text(g, text, x, y + 10, TableScreen.YELLOW, align)
    }

    private fun renderResult(g: GuiGraphicsExtractor, table: ClientTable, view: DdzView) {
        val settlement = view.settlement ?: return
        val w = 210
        val x = width / 2 - w / 2
        val top = RESULT_TOP
        g.fill(x, top, x + w, top + 98, 0xE0101010.toInt())
        val me = table.snapshot.you
        val iWon = me != null && settlement.scores.getOrElse(me) { 0 } > 0
        val title = Component.translatable(if (settlement.landlordWon) "tukuai.ddz.landlord_won" else "tukuai.ddz.farmers_won")
        g.centeredText(font, title, width / 2, top + 6, if (me == null) TableScreen.WHITE else if (iWon) TableScreen.GREEN else TableScreen.RED)
        val detail = Component.translatable(
            if (settlement.spring) "tukuai.ddz.result_spring" else "tukuai.ddz.result_detail",
            settlement.bidMultiplier,
            settlement.bombs,
            settlement.multiplier,
        )
        g.centeredText(font, detail, width / 2, top + 18, TableScreen.GRAY)
        for (seat in 0 until DouDizhu.SEATS) {
            val y = top + 32 + seat * 12
            CardArt.drawHat(g, view.isLandlord(seat), x + 8, y - 4)
            g.text(font, PanelText.seatLabel(table, seat), x + 28, y, if (seat == me) TableScreen.YELLOW else TableScreen.WHITE, false)
            val points = settlement.scores.getOrElse(seat) { 0 }
            val text = Component.literal(if (points > 0) "+$points" else points.toString())
            g.text(font, text, x + w - 8 - font.width(text), y, if (points >= 0) TableScreen.GREEN else TableScreen.RED, false)
        }
    }

    companion object {
        /** The multiplier so far: the bid, doubled for each bomb when the rules say so. */
        fun multiplier(view: DdzView): Int {
            var multiplier = view.bidMultiplier
            if (view.rules.bombsDouble) repeat(view.bombs) { multiplier *= 2 }
            return multiplier
        }

        /** The result box sits at the top, over the landlord's cards, clear of both players' boxes. */
        private const val RESULT_TOP = 4

        fun eventText(event: DdzEvent): Component? = when (event.kind) {
            DdzEventKind.BID -> if (event.value == 0) Component.translatable("tukuai.ddz.no_bid") else Component.translatable("tukuai.ddz.bid", event.value)
            DdzEventKind.CALL -> Component.translatable("tukuai.ddz.call")
            DdzEventKind.NO_CALL -> Component.translatable("tukuai.ddz.no_call")
            DdzEventKind.ROB -> Component.translatable("tukuai.ddz.rob")
            DdzEventKind.NO_ROB -> Component.translatable("tukuai.ddz.no_rob")
            DdzEventKind.DOUBLE -> Component.translatable("tukuai.ddz.double")
            DdzEventKind.NO_DOUBLE -> Component.translatable("tukuai.ddz.no_double")
            DdzEventKind.PASS -> Component.translatable("tukuai.ddz.pass")
            DdzEventKind.REDEAL, DdzEventKind.LANDLORD, DdzEventKind.PLAY -> null
        }

        private val BID_KINDS = setOf(DdzEventKind.BID, DdzEventKind.CALL, DdzEventKind.NO_CALL, DdzEventKind.ROB, DdzEventKind.NO_ROB)
        private val PLAY_KINDS = setOf(DdzEventKind.PLAY, DdzEventKind.PASS)

        /** Each seat's latest move in the current round of the current phase; the seat to act shows nothing. */
        fun recentActions(view: DdzView): Map<Seat, DdzEvent> {
            val out = HashMap<Seat, DdzEvent>()
            val events = view.log.asReversed()
            when (view.phase) {
                DdzPhase.BIDDING -> for (e in events) {
                    if (e.kind == DdzEventKind.REDEAL) break
                    if (e.kind in BID_KINDS) out.putIfAbsent(e.seat, e)
                }
                DdzPhase.DOUBLING -> for (e in events) {
                    if (e.kind == DdzEventKind.LANDLORD) break
                    if (e.kind == DdzEventKind.DOUBLE || e.kind == DdzEventKind.NO_DOUBLE) out.putIfAbsent(e.seat, e)
                }
                DdzPhase.PLAYING, DdzPhase.FINISHED -> {
                    val wanted = if (view.phase == DdzPhase.FINISHED) DouDizhu.SEATS else DouDizhu.SEATS - 1
                    var seen = 0
                    for (e in events) {
                        if (e.kind !in PLAY_KINDS) break
                        out.putIfAbsent(e.seat, e)
                        if (++seen >= wanted) break
                    }
                }
            }
            if (view.phase != DdzPhase.FINISHED) out.remove(view.turn)
            return out
        }
    }
}
