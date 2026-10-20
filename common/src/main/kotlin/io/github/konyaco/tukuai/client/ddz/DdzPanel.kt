package io.github.konyaco.tukuai.client.ddz

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientConfig
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.PanelHost
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
import io.github.konyaco.tukuai.table.Occupant
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
    private val selected = LinkedHashSet<PlayingCard>()
    private var hints: List<List<PlayingCard>> = emptyList()
    private var hintIndex = 0
    private var lastStep = -1
    private var wasMyTurn = false

    private val width get() = screen.panelWidth
    private val height get() = screen.panelHeight
    private val font get() = Minecraft.getInstance().font

    private fun send(action: DdzAction) = screen.send(TableOp.ACT, data = DdzCodecs.ACTION.encode(action))

    // ---- Layout --------------------------------------------------------------------------------

    private fun handY() = height - CardArt.HEIGHT - 8

    private fun buttonY() = handY() - 14 - 22

    override fun controlsY(): Int = buttonY()

    private fun handStep(n: Int): Int = if (n <= 1) 0 else minOf(18, (width - 120 - CardArt.WIDTH) / (n - 1))

    private fun handX(n: Int): Int = (width - (CardArt.WIDTH + handStep(n) * (n - 1))) / 2

    /** Your own hand, or for a watcher who may see every hand, that of seat 0 at the bottom. */
    private fun sortedHand(view: DdzView): List<PlayingCard> =
        (if (view.seat == null) view.revealedHands.getOrNull(0).orEmpty() else view.hand).sortedWith(DDZ_ORDER.reversed())

    // ---- Buttons -------------------------------------------------------------------------------

    override fun rebuild(table: ClientTable) {
        val view = table.view as? DdzView ?: return
        val s = table.snapshot
        if (s.step != lastStep) {
            lastStep = s.step
            selected.retainAll(view.hand.toSet())
            hints = emptyList()
            hintIndex = 0
        }
        if (!screen.interactive) return
        val myTurn = s.status == TableStatus.PLAYING && view.isMyTurn
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
        if (s.status == TableStatus.FINISHED) {
            val ready = me != null && s.seats.all { it.occupant != null }
            screen.button(Component.translatable("tukuai.table.again"), width / 2 - 100, RESULT_TOP + 74, 98, ready) {
                screen.send(TableOp.START)
            }
            screen.button(Component.translatable("tukuai.table.replay"), width / 2 + 2, RESULT_TOP + 74, 98) {
                screen.send(TableOp.REPLAY)
            }
        }
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
        if (hints.isEmpty()) {
            val options = Patterns.options(view.hand, view.toBeat, view.rules)
                .sortedWith(compareBy({ it.pattern.isBomb }, { it.pattern.main }, { -it.cards.size }))
                .map { it.cards }
            val suggestion = (DdzBot(BotLevel.NORMAL, 0).act(view) as? DdzAction.PlayCards)?.cards
            hints = (listOfNotNull(suggestion) + options).distinctBy { cards -> cards.toSet() }
            hintIndex = 0
        }
        if (hints.isEmpty()) {
            send(DdzAction.Pass)
            return
        }
        selected.clear()
        selected += hints[hintIndex % hints.size]
        hintIndex++
        screen.refresh()
    }

    // ---- Input ---------------------------------------------------------------------------------

    override fun mouseClicked(table: ClientTable, x: Double, y: Double, button: Int): Boolean {
        if (!screen.interactive) return false
        val view = table.view as? DdzView ?: return false
        if (view.seat == null) return false
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (selected.isEmpty()) return false
            selected.clear()
            screen.refresh()
            return true
        }
        val card = cardAt(view, x, y) ?: return false
        if (!selected.remove(card)) selected += card
        screen.refresh()
        return true
    }

    private fun cardAt(view: DdzView, mx: Double, my: Double): PlayingCard? {
        val hand = sortedHand(view)
        val n = hand.size
        val step = handStep(n)
        val x0 = handX(n)
        for (i in n - 1 downTo 0) {
            val card = hand[i]
            val x = x0 + i * step
            val y = handY() - if (card in selected) RAISE else 0
            if (mx >= x && mx < x + CardArt.WIDTH && my >= y && my < y + CardArt.HEIGHT) return card
        }
        return null
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
            recent[me]?.let { renderEvent(g, view, it, width / 2, buttonY() - 40, Align.CENTER) }
        }
        renderSelf(g, table, view, me)
        renderHand(g, view, mouseX, mouseY)
        if (finished) renderResult(g, table, view)
    }

    private fun renderTitle(g: GuiGraphicsExtractor, table: ClientTable) {
        val s = table.snapshot
        g.text(font, TableScreen.presetName(s.gameId, s.presetId), 6, 6, TableScreen.GRAY, true)
        if (!screen.interactive) return
        val round = s.rounds + if (s.status == TableStatus.PLAYING) 1 else 0
        g.text(font, Component.translatable("tukuai.table.rounds", round), 6, 17, TableScreen.GRAY, true)
        table.watchingLine()?.let { g.text(font, it, 6, 28, if (s.spectators) TableScreen.YELLOW else TableScreen.GRAY, true) }
    }

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

    private fun seatLabel(table: ClientTable, seat: Seat): Component {
        val info = table.snapshot.seats.getOrNull(seat)
        val name = TableScreen.seatName(info?.occupant)
        return when {
            info?.occupant is Occupant.Human && !info.present -> Component.translatable("tukuai.table.name_away", name)
            info?.auto == true -> Component.translatable("tukuai.table.name_auto", name)
            else -> name
        }
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
        g.text(font, font.plainSubstrByWidth(seatLabel(table, seat).string, boxW - (nameX - x)), nameX, y + 4, TableScreen.WHITE, true)

        CardArt.draw(g, CardArt.back(), x, y + 18, 0.5f)
        g.text(font, Component.literal("× ${view.handCounts[seat]}"), x + 20, y + 26, TableScreen.YELLOW, true)
        if (view.doubles.getOrNull(seat) == true) {
            if (compact) g.text(font, Component.translatable("tukuai.ddz.doubled"), x, y + 42, TableScreen.RED, true)
            else g.text(font, Component.translatable("tukuai.ddz.doubled"), x + 56, y + 26, TableScreen.RED, true)
        }
        if (acting) renderTimer(g, table, x + boxW - 4, y + 42, alignRight = true)

        // What they just did beside the box, or below it when compact. Their whole hand, once
        // it is shown (at the end, or in a replay), goes below the box so the result in the
        // middle does not cover it.
        val align = if (onLeft) Align.LEFT else Align.RIGHT
        val edgeX = if (onLeft) x else x + boxW
        val revealed = view.revealedHands.getOrNull(seat)
        val finished = table.snapshot.status == TableStatus.FINISHED
        if (revealed != null) {
            // A watcher sees hands while play goes on; with the box compact, its last move comes first.
            val below = if (compact && !finished && recent != null) 58 + CardArt.HEIGHT / 2 + 4 else 60
            renderCards(g, revealed.sortedWith(DDZ_ORDER.reversed()), edgeX, y + below, 0.5f, 7, align)
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
        val label = if (!seen) Component.translatable("tukuai.table.watching") else seatLabel(table, me)
        g.text(font, label, x + 20, y, TableScreen.WHITE, true)
        if (view.turn == me && table.snapshot.status == TableStatus.PLAYING && seen) {
            renderTimer(g, table, x + 20, y + 12, alignRight = false)
        }
    }

    private fun renderTimer(g: GuiGraphicsExtractor, table: ClientTable, x: Int, y: Int, alignRight: Boolean) {
        val ticks = table.timerTicks()
        if (ticks < 0) return
        val seconds = (ticks + 19) / 20
        val text = Component.translatable("tukuai.table.seconds", seconds)
        val color = if (seconds <= 5) TableScreen.RED else TableScreen.YELLOW
        g.text(font, text, if (alignRight) x - font.width(text) else x, y, color, true)
    }

    private fun renderHand(g: GuiGraphicsExtractor, view: DdzView, mouseX: Int, mouseY: Int) {
        val hand = sortedHand(view)
        if (hand.isEmpty()) return
        val step = handStep(hand.size)
        val x0 = handX(hand.size)
        val hovered = cardAt(view, mouseX.toDouble(), mouseY.toDouble())
        hand.forEachIndexed { i, card ->
            val raised = card in selected
            val y = handY() - if (raised) RAISE else 0
            val x = x0 + i * step
            CardArt.drawCard(g, card, x, y)
            if (card == hovered) g.fill(x, y, x + if (i == hand.size - 1) CardArt.WIDTH else step, y + CardArt.HEIGHT, 0x30FFFFFF)
        }
    }

    private enum class Align { LEFT, CENTER, RIGHT }

    private fun renderEvent(g: GuiGraphicsExtractor, view: DdzView, event: DdzEvent, x: Int, y: Int, align: Align, scale: Float = 0.75f, step: Int = 13) {
        if (event.kind == DdzEventKind.PLAY) {
            renderCards(g, event.cards.sortedWith(DDZ_ORDER.reversed()), x, y, scale, step, align)
            return
        }
        val text = eventText(event) ?: return
        val w = font.width(text)
        val tx = when (align) {
            Align.LEFT -> x
            Align.CENTER -> x - w / 2
            Align.RIGHT -> x - w
        }
        g.text(font, text, tx, y + 10, TableScreen.YELLOW, true)
    }

    private fun renderCards(g: GuiGraphicsExtractor, cards: List<PlayingCard>, x: Int, y: Int, scale: Float, step: Int, align: Align) {
        if (cards.isEmpty()) return
        val total = (CardArt.WIDTH * scale).toInt() + step * (cards.size - 1)
        val x0 = when (align) {
            Align.LEFT -> x
            Align.CENTER -> x - total / 2
            Align.RIGHT -> x - total
        }
        cards.forEachIndexed { i, card -> CardArt.drawCard(g, card, x0 + i * step, y, scale) }
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
            g.text(font, seatLabel(table, seat), x + 28, y, if (seat == me) TableScreen.YELLOW else TableScreen.WHITE, false)
            val points = settlement.scores.getOrElse(seat) { 0 }
            val text = Component.literal(if (points > 0) "+$points" else points.toString())
            g.text(font, text, x + w - 8 - font.width(text), y, if (points >= 0) TableScreen.GREEN else TableScreen.RED, false)
        }
    }

    companion object {
        private const val RAISE = 10

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
