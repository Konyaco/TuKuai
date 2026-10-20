package io.github.konyaco.tukuai.engine.uno

import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.GameDefinition
import io.github.konyaco.tukuai.engine.core.GameResult
import io.github.konyaco.tukuai.engine.core.GuideSection
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.RulesPreset
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.uno.bot.UnoBot
import kotlin.random.Random

/**
 * UNO for 2 to 4 players: match the top card by colour, number or symbol, or play a wild
 * card and name the colour. The first to empty their hand wins what is left in everyone
 * else's.
 *
 * A few things are simplified for a game played through a screen:
 * - The first card turned up is always a number card; others go back under the deck.
 * - A wild draw four is checked when it is played instead of being challenged
 *   ([UnoRules.strictWildFour]).
 * - Calling UNO is part of playing the second last card, and forgetting it always costs
 *   the penalty, since there is no moment for the others to catch it.
 * - A drawn card that can be played may be played at once; one that cannot ends the turn.
 */
object Uno : GameDefinition<UnoRules, UnoState, UnoAction, UnoView> {
    /** After this many turns in a row in which no card could be played or drawn, the hand ends. */
    const val STALL_TURNS = 12

    override val id = "uno"
    override val rulesCodec: WireCodec<UnoRules> = UnoRules.CODEC
    override val actionCodec: WireCodec<UnoAction> = UnoCodecs.ACTION
    override val viewCodec: WireCodec<UnoView> = UnoCodecs.VIEW
    override val presets: List<RulesPreset<UnoRules>> = UnoRules.PRESETS

    override fun seatCount(rules: UnoRules) = rules.players

    override fun start(rules: UnoRules, seed: Long): UnoState {
        val random = Random(seed)
        val deck = UnoDeck.ALL.shuffled(random).toMutableList()
        val hands = List(rules.players) { ArrayList<UnoCard>(rules.handSize) }
        repeat(rules.handSize) { hands.forEach { hand -> hand += deck.removeLast() } }
        // Turn up cards until a number comes up; the others go back under the deck.
        var first = deck.removeLast()
        while (first.face.kind != UnoKind.NUMBER) {
            deck.add(0, first)
            first = deck.removeLast()
        }
        return UnoState(
            rules = rules,
            seed = seed,
            drawPile = deck,
            discard = listOf(first),
            hands = hands,
            color = first.face.color!!,
            turn = random.nextInt(rules.players),
            direction = 1,
            pendingDraw = 0,
            drawn = null,
            reshuffles = 0,
            idleTurns = 0,
            log = emptyList(),
            settlement = null,
        )
    }

    override fun toAct(state: UnoState): Seat? = if (state.settlement != null) null else state.turn

    /**
     * Whether [card] may be played now by a seat holding [hand]. Shared with [UnoView] so
     * the screen, the computer and the rules always agree.
     */
    fun playable(
        rules: UnoRules,
        hand: List<UnoCard>,
        top: UnoCard,
        color: UnoColor,
        pendingDraw: Int,
        drawn: UnoCard?,
        card: UnoCard,
    ): Boolean {
        if (drawn != null && card != drawn) return false
        if (card !in hand) return false
        val face = card.face
        val topFace = top.face
        if (pendingDraw > 0) {
            if (!rules.stacking) return false
            return when (face.kind) {
                UnoKind.DRAW_TWO -> topFace.kind == UnoKind.DRAW_TWO
                UnoKind.WILD_DRAW_FOUR -> true
                else -> false
            }
        }
        return when (face.kind) {
            UnoKind.WILD -> true
            UnoKind.WILD_DRAW_FOUR -> !rules.strictWildFour || hand.none { it.face.color == color }
            UnoKind.NUMBER -> face.color == color || (topFace.kind == UnoKind.NUMBER && face.number == topFace.number)
            else -> face.color == color || face.kind == topFace.kind
        }
    }

    fun playable(state: UnoState, card: UnoCard): Boolean =
        playable(state.rules, state.hands[state.turn], state.top, state.color, state.pendingDraw, state.drawn, card)

    override fun apply(state: UnoState, seat: Seat, action: UnoAction): UnoState {
        if (state.settlement != null) throw IllegalActionException("The game is over")
        if (seat != state.turn) throw IllegalActionException("It is seat ${state.turn}'s turn, not $seat's")
        return when (action) {
            is UnoAction.Play -> play(state, action)
            UnoAction.Draw -> draw(state)
            UnoAction.Pass -> pass(state)
        }
    }

    private fun next(state: UnoState, seat: Seat, direction: Int = state.direction): Seat =
        (seat + direction).mod(state.rules.players)

    // ---- Playing a card ------------------------------------------------------------------------

    private fun play(state: UnoState, action: UnoAction.Play): UnoState {
        val card = action.card
        val face = card.face
        if (card !in state.hands[state.turn]) throw IllegalActionException("$card is not in hand")
        if (!playable(state, card)) throw IllegalActionException("$card cannot be played on ${state.top} now")
        if (face.isWild && action.color == null) throw IllegalActionException("Name a colour for $card")
        val seat = state.turn
        val color = if (face.isWild) action.color!! else face.color!!
        val log = ArrayList(state.log)
        log += UnoEvent(seat, UnoEventKind.PLAY, card = card, color = if (face.isWild) color else null)
        var s = state.copy(
            hands = state.hands.replace(seat, state.hands[seat] - card),
            discard = state.discard + card,
            color = color,
            drawn = null,
            idleTurns = 0,
        )

        // Down to one card: call UNO, or draw for forgetting.
        if (s.hands[seat].size == 1) {
            if (action.uno) {
                log += UnoEvent(seat, UnoEventKind.UNO)
            } else if (state.rules.unoPenalty > 0) {
                val (after, drew) = drawCards(s, seat, state.rules.unoPenalty, log)
                s = after
                log += UnoEvent(seat, UnoEventKind.PENALTY, count = drew)
            }
        }

        var direction = s.direction
        var turn: Seat
        var pending = s.pendingDraw
        when (face.kind) {
            UnoKind.SKIP -> {
                val skipped = next(s, seat)
                log += UnoEvent(skipped, UnoEventKind.SKIPPED)
                turn = next(s, skipped)
            }
            UnoKind.REVERSE -> {
                direction = -direction
                if (state.rules.players == 2) {
                    // Between two players a reverse works like a skip.
                    log += UnoEvent(next(s, seat), UnoEventKind.SKIPPED)
                    turn = seat
                } else {
                    turn = next(s, seat, direction)
                }
            }
            UnoKind.DRAW_TWO, UnoKind.WILD_DRAW_FOUR -> {
                val victim = next(s, seat)
                if (state.rules.stacking) {
                    pending += face.drawPenalty
                    turn = victim
                } else {
                    val (after, drew) = drawCards(s, victim, face.drawPenalty, log)
                    s = after
                    log += UnoEvent(victim, UnoEventKind.DRAW, count = drew, forced = true)
                    log += UnoEvent(victim, UnoEventKind.SKIPPED)
                    turn = next(s, victim)
                }
            }
            UnoKind.NUMBER, UnoKind.WILD -> turn = next(s, seat)
        }

        if (s.hands[seat].isEmpty()) {
            // Cards still owed by the next player are drawn first, so they count against them.
            if (pending > 0) {
                val victim = next(s, seat, direction)
                val (after, drew) = drawCards(s, victim, pending, log)
                s = after
                log += UnoEvent(victim, UnoEventKind.DRAW, count = drew, forced = true)
                pending = 0
            }
            return finish(s.copy(direction = direction, pendingDraw = 0, log = log), seat)
        }
        return s.copy(turn = turn, direction = direction, pendingDraw = pending, log = log)
    }

    // ---- Drawing -------------------------------------------------------------------------------

    private fun draw(state: UnoState): UnoState {
        if (state.drawn != null) throw IllegalActionException("Play the card you drew, or keep it")
        val seat = state.turn
        val log = ArrayList(state.log)
        if (state.pendingDraw > 0) {
            val (after, drew) = drawCards(state, seat, state.pendingDraw, log)
            log += UnoEvent(seat, UnoEventKind.DRAW, count = drew, forced = true)
            return after.copy(turn = next(after, seat), pendingDraw = 0, idleTurns = 0, log = log)
        }
        val (after, drew) = drawCards(state, seat, 1, log)
        log += UnoEvent(seat, UnoEventKind.DRAW, count = drew)
        if (drew == 0) {
            // Nothing left to draw: the turn passes, and a hand nobody can move ends after a while.
            val idle = state.idleTurns + 1
            val stalled = after.copy(turn = next(after, seat), idleTurns = idle, log = log)
            return if (idle >= STALL_TURNS) finish(stalled, lowestHand(stalled)) else stalled
        }
        val card = after.hands[seat].last()
        val withCard = after.copy(drawn = card, idleTurns = 0, log = log)
        return if (playable(withCard, card)) withCard else withCard.copy(drawn = null, turn = next(after, seat))
    }

    private fun pass(state: UnoState): UnoState {
        if (state.drawn == null) throw IllegalActionException("Draw a card before passing")
        return state.copy(
            drawn = null,
            turn = next(state, state.turn),
            log = state.log + UnoEvent(state.turn, UnoEventKind.PASS),
        )
    }

    /**
     * Gives [seat] up to [count] cards from the draw pile, shuffling the discard pile back in
     * when it runs out. Returns the new state and how many cards were really drawn.
     */
    private fun drawCards(state: UnoState, seat: Seat, count: Int, log: MutableList<UnoEvent>): Pair<UnoState, Int> {
        var s = state
        val drawn = ArrayList<UnoCard>(count)
        while (drawn.size < count) {
            if (s.drawPile.isEmpty()) {
                if (s.discard.size <= 1) break
                val random = Random(s.seed xor (s.reshuffles + 1) * -0x61c8864680b583ebL)
                s = s.copy(
                    drawPile = s.discard.dropLast(1).shuffled(random),
                    discard = listOf(s.top),
                    reshuffles = s.reshuffles + 1,
                )
                log += UnoEvent(seat, UnoEventKind.RESHUFFLE)
            }
            drawn += s.drawPile.last()
            s = s.copy(drawPile = s.drawPile.dropLast(1))
        }
        return s.copy(hands = s.hands.replace(seat, s.hands[seat] + drawn)) to drawn.size
    }

    // ---- The end -------------------------------------------------------------------------------

    private fun lowestHand(state: UnoState): Seat =
        state.hands.indices.minBy { seat -> state.hands[seat].sumOf { it.face.points } }

    /** Everyone else pays the winner what their cards are worth. */
    private fun finish(state: UnoState, winner: Seat): UnoState {
        val points = state.hands.mapIndexed { seat, hand -> if (seat == winner) 0 else hand.sumOf { it.face.points } }
        val scores = points.mapIndexed { seat, p -> if (seat == winner) points.sum() else -p }
        return state.copy(drawn = null, settlement = UnoSettlement(winner, points, scores))
    }

    override fun result(state: UnoState): GameResult? = state.result

    override fun view(state: UnoState, seat: Seat?): UnoView = UnoView.of(state, seat)

    override fun openView(state: UnoState, seat: Seat?): UnoView = UnoView.of(state, seat).copy(revealedHands = state.hands)

    override fun createBot(level: BotLevel, seed: Long): Bot<UnoView, UnoAction> = UnoBot(level, seed)

    override fun guide(rules: UnoRules?): List<GuideSection> = UnoGuide.sections(rules)

    private fun <T> List<T>.replace(index: Int, value: T): List<T> = mapIndexed { i, old -> if (i == index) value else old }
}
