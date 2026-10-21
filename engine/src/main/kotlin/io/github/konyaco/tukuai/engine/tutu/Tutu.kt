package io.github.konyaco.tukuai.engine.tutu

import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.GameDefinition
import io.github.konyaco.tukuai.engine.core.GameResult
import io.github.konyaco.tukuai.engine.core.GuideSection
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.RulesPreset
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.tutu.bot.TutuBot
import kotlin.random.Random

/**
 * Tutu cards (土土牌) for 2 to 4 players: a shedding game in the family of Crazy Eights,
 * with Minecraft things for its specials. Match the top card by colour, number or symbol;
 * the first to empty their hand wins. Snowballs freeze, tridents turn play around, shovels
 * dig up two cards and lightning makes the next player draw until it finds its colour.
 * The wild cards are where it gets lively: an egg hatches into a random card, TNT burns
 * down a hidden fuse, and a cactus hides in the draw pile for someone to draw.
 *
 * The draw pile is never reshuffled. When it runs out the hand ends, and whoever holds the
 * fewest cards wins. Every card left in a hand is worth [CARD_POINTS].
 */
object Tutu : GameDefinition<TutuRules, TutuState, TutuAction, TutuView> {
    const val CARD_POINTS = 10
    const val SHOVEL_CARDS = 2
    const val CACTUS_CARDS = 3
    const val TNT_CARDS = 4

    override val id = "tutu"
    override val rulesCodec: WireCodec<TutuRules> = TutuRules.CODEC
    override val actionCodec: WireCodec<TutuAction> = TutuCodecs.ACTION
    override val viewCodec: WireCodec<TutuView> = TutuCodecs.VIEW
    override val presets: List<RulesPreset<TutuRules>> = TutuRules.PRESETS

    override fun seatCount(rules: TutuRules) = rules.players

    override fun start(rules: TutuRules, seed: Long): TutuState {
        val random = Random(seed)
        val deck = TutuDeck.cards(rules).shuffled(random).toMutableList()
        val hands = List(rules.players) { ArrayList<TutuCard>(rules.handSize) }
        repeat(rules.handSize) { hands.forEach { hand -> hand += deck.removeLast() } }
        // Turn up cards until a number comes up; the others go back under the deck.
        var first = deck.removeLast()
        while (first.face.kind != TutuKind.NUMBER) {
            deck.add(0, first)
            first = deck.removeLast()
        }
        return TutuState(
            rules = rules,
            seed = seed,
            drawPile = deck,
            discard = listOf(first),
            hands = hands,
            top = first.face,
            color = first.face.color!!,
            turn = random.nextInt(rules.players),
            direction = 1,
            drawn = null,
            cacti = emptyList(),
            fuses = emptyList(),
            rolls = 0,
            log = emptyList(),
            settlement = null,
        )
    }

    override fun toAct(state: TutuState): Seat? = if (state.settlement != null) null else state.turn

    /**
     * Whether [card] may be played now by a seat holding [hand]. Shared with [TutuView] so
     * the screen, the computer and the rules always agree.
     */
    fun playable(hand: List<TutuCard>, top: TutuFace, color: TutuColor, drawn: TutuCard?, card: TutuCard): Boolean {
        if (drawn != null && card != drawn) return false
        if (card !in hand) return false
        val face = card.face
        return when {
            face.isWild -> true
            face.color == color -> true
            face.kind == TutuKind.NUMBER -> top.kind == TutuKind.NUMBER && top.number == face.number
            else -> face.kind == top.kind
        }
    }

    fun playable(state: TutuState, card: TutuCard): Boolean =
        playable(state.hands[state.turn], state.top, state.color, state.drawn, card)

    override fun apply(state: TutuState, seat: Seat, action: TutuAction): TutuState {
        if (state.settlement != null) throw IllegalActionException("The game is over")
        if (seat != state.turn) throw IllegalActionException("It is seat ${state.turn}'s turn, not $seat's")
        val log = ArrayList(state.log)
        val after = when (action) {
            is TutuAction.Play -> play(state, action, log)
            TutuAction.Draw -> draw(state, log)
            TutuAction.Pass -> pass(state, log)
        }
        return after.copy(log = log)
    }

    private fun next(state: TutuState, seat: Seat, direction: Int = state.direction): Seat =
        (seat + direction).mod(state.rules.players)

    /** A random number below [bound] from the game's seed, and the state that has used it up. */
    private fun roll(state: TutuState, bound: Int): Pair<TutuState, Int> {
        val random = Random(state.seed xor (state.rolls + 1) * -0x3d4d51c2d82b14b1L)
        return state.copy(rolls = state.rolls + 1) to random.nextInt(bound)
    }

    // ---- Playing a card ------------------------------------------------------------------------

    private fun play(state: TutuState, action: TutuAction.Play, log: MutableList<TutuEvent>): TutuState {
        val card = action.card
        if (card !in state.hands[state.turn]) throw IllegalActionException("$card is not in hand")
        if (!playable(state, card)) throw IllegalActionException("$card cannot be played on ${state.top} now")
        if (card.face.kind.namesColor && action.color == null) throw IllegalActionException("Name a colour for $card")
        val seat = state.turn
        var s = state.copy(
            hands = state.hands.replace(seat, state.hands[seat] - card),
            discard = state.discard + card,
            drawn = null,
        )
        // An egg hatches into a random coloured card, which then works as if it had been played.
        val face = if (card.face.kind == TutuKind.EGG) {
            val (rolled, index) = roll(s, TutuDeck.HATCHES.size)
            s = rolled
            TutuDeck.HATCHES[index]
        } else {
            card.face
        }
        val named = action.color.takeIf { face.kind.namesColor }
        val color = named ?: face.color!!
        log += TutuEvent(seat, TutuEventKind.PLAY, card = card, face = face, color = named)
        s = s.copy(top = face, color = color)

        var turn = next(s, seat)
        when (face.kind) {
            TutuKind.NUMBER -> {}
            TutuKind.SNOWBALL -> {
                log += TutuEvent(turn, TutuEventKind.SKIPPED)
                turn = next(s, turn)
            }
            TutuKind.TRIDENT -> {
                s = s.copy(direction = -s.direction)
                if (s.rules.players == 2) {
                    // Between two players a trident works like a snowball.
                    log += TutuEvent(turn, TutuEventKind.SKIPPED)
                    turn = seat
                } else {
                    turn = next(s, seat)
                }
            }
            TutuKind.SHOVEL, TutuKind.LIGHTNING -> {
                val victim = turn
                val before = s.hands[victim].size
                s = if (face.kind == TutuKind.SHOVEL) {
                    drawCards(s, victim, SHOVEL_CARDS, log)
                } else {
                    drawUntil(s, victim, log) { it.face.isWild || it.face.color == face.color }
                }
                log += TutuEvent(victim, TutuEventKind.DRAW, count = s.hands[victim].size - before, forced = true)
                log += TutuEvent(victim, TutuEventKind.SKIPPED)
                turn = next(s, victim)
            }
            TutuKind.TNT -> {
                val (rolled, fuse) = roll(s, s.rules.players * 2)
                s = rolled.copy(fuses = rolled.fuses + (fuse + 1))
            }
            TutuKind.CACTUS -> if (s.drawPile.isNotEmpty()) {
                val (rolled, depth) = roll(s, s.drawPile.size)
                s = rolled.copy(cacti = rolled.cacti + (depth + 1))
            }
            TutuKind.EGG -> error("An egg always hatches")
        }

        if (s.hands[seat].isEmpty()) return finish(s, setOf(seat), pileEmpty = false)
        return beginTurn(s, turn, log)
    }

    // ---- Drawing -------------------------------------------------------------------------------

    private fun draw(state: TutuState, log: MutableList<TutuEvent>): TutuState {
        if (state.drawn != null) throw IllegalActionException("Play the card you drew, or keep it")
        val seat = state.turn
        val before = state.hands[seat].size
        val logged = log.size
        val after = drawCards(state, seat, 1, log)
        val drew = after.hands[seat].size - before
        val pricked = log.size > logged
        log += TutuEvent(seat, TutuEventKind.DRAW, count = drew)
        if (after.drawPile.isEmpty()) return finishOnEmptyPile(after)
        // A cactus ends the turn; otherwise the card drawn may be played at once if it fits.
        val card = after.hands[seat].last()
        val withCard = after.copy(drawn = card)
        if (!pricked && playable(withCard, card)) return withCard
        return beginTurn(after, next(after, seat), log)
    }

    private fun pass(state: TutuState, log: MutableList<TutuEvent>): TutuState {
        if (state.drawn == null) throw IllegalActionException("Draw a card before passing")
        log += TutuEvent(state.turn, TutuEventKind.PASS)
        return beginTurn(state.copy(drawn = null), next(state, state.turn), log)
    }

    /**
     * Gives [seat] [count] cards from the draw pile, and three more for every cactus on the
     * way. Stops early if the pile runs out.
     */
    private fun drawCards(state: TutuState, seat: Seat, count: Int, log: MutableList<TutuEvent>): TutuState {
        val pile = state.drawPile.toMutableList()
        val cacti = state.cacti.toMutableList()
        val drawn = ArrayList<TutuCard>(count)
        var owed = count
        while (owed > 0 && pile.isNotEmpty()) {
            while (cacti.remove(pile.size)) {
                owed += CACTUS_CARDS
                log += TutuEvent(seat, TutuEventKind.CACTUS, count = CACTUS_CARDS)
            }
            drawn += pile.removeLast()
            owed--
        }
        return state.copy(drawPile = pile, cacti = cacti, hands = state.hands.replace(seat, state.hands[seat] + drawn))
    }

    /** Draws one card at a time, with any cactus on the way, until one that [stop] likes comes up. */
    private fun drawUntil(state: TutuState, seat: Seat, log: MutableList<TutuEvent>, stop: (TutuCard) -> Boolean): TutuState {
        var s = state
        while (s.drawPile.isNotEmpty()) {
            val before = s.hands[seat].size
            s = drawCards(s, seat, 1, log)
            if (s.hands[seat].subList(before, s.hands[seat].size).any(stop)) break
        }
        return s
    }

    // ---- Turns and the end ---------------------------------------------------------------------

    /** Hands the turn to [seat]; lit TNT burns down a turn and may blow up in their face. */
    private fun beginTurn(state: TutuState, seat: Seat, log: MutableList<TutuEvent>): TutuState {
        var s = state.copy(turn = seat, drawn = null)
        if (s.fuses.isNotEmpty()) {
            val burnt = s.fuses.map { it - 1 }
            val booms = burnt.count { it <= 0 }
            s = s.copy(fuses = burnt.filter { it > 0 })
            if (booms > 0) {
                log += TutuEvent(seat, TutuEventKind.BOOM, count = booms)
                val before = s.hands[seat].size
                s = drawCards(s, seat, TNT_CARDS * booms, log)
                log += TutuEvent(seat, TutuEventKind.DRAW, count = s.hands[seat].size - before, forced = true)
            }
        }
        return if (s.drawPile.isEmpty()) finishOnEmptyPile(s) else s
    }

    private fun finishOnEmptyPile(state: TutuState): TutuState {
        val fewest = state.hands.minOf { it.size }
        return finish(state, state.hands.indices.filterTo(LinkedHashSet()) { state.hands[it].size == fewest }, pileEmpty = true)
    }

    /** Everyone else pays each winner what their cards are worth. */
    private fun finish(state: TutuState, winners: Set<Seat>, pileEmpty: Boolean): TutuState {
        val points = state.hands.mapIndexed { seat, hand -> if (seat in winners) 0 else hand.size * CARD_POINTS }
        val pot = points.sum()
        val scores = points.mapIndexed { seat, p -> if (seat in winners) pot else -p * winners.size }
        return state.copy(drawn = null, settlement = TutuSettlement(winners, pileEmpty, points, scores))
    }

    override fun result(state: TutuState): GameResult? = state.result

    override fun view(state: TutuState, seat: Seat?): TutuView = TutuView.of(state, seat)

    override fun openView(state: TutuState, seat: Seat?): TutuView =
        TutuView.of(state, seat).copy(revealedHands = state.hands, fuses = state.fuses)

    override fun createBot(level: BotLevel, seed: Long): Bot<TutuView, TutuAction> = TutuBot(level, seed)

    override fun guide(rules: TutuRules?): List<GuideSection> = TutuGuide.sections(rules)

    private fun <T> List<T>.replace(index: Int, value: T): List<T> = mapIndexed { i, old -> if (i == index) value else old }
}
