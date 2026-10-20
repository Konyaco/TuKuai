package io.github.konyaco.tukuai.engine.doudizhu

import io.github.konyaco.tukuai.engine.card.Decks
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.GameDefinition
import io.github.konyaco.tukuai.engine.core.GameResult
import io.github.konyaco.tukuai.engine.core.GuideSection
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.RulesPreset
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.doudizhu.bot.DdzBot
import kotlin.random.Random

/** Three-player Dou Dizhu: one landlord with the three kitty cards against two farmers. */
object DouDizhu : GameDefinition<DdzRules, DdzState, DdzAction, DdzView> {
    const val SEATS = 3
    const val HAND_SIZE = 17

    override val id = "doudizhu"
    override val rulesCodec: WireCodec<DdzRules> = DdzRules.CODEC
    override val actionCodec: WireCodec<DdzAction> = DdzCodecs.ACTION
    override val viewCodec: WireCodec<DdzView> = DdzCodecs.VIEW
    override val presets: List<RulesPreset<DdzRules>> = DdzRules.PRESETS

    override fun seatCount(rules: DdzRules) = SEATS

    override fun start(rules: DdzRules, seed: Long): DdzState {
        val first = Random(seed).nextInt(SEATS)
        return dealt(rules, seed, redeals = 0, firstBidder = first, log = emptyList())
    }

    private fun dealt(rules: DdzRules, seed: Long, redeals: Int, firstBidder: Seat, log: List<DdzEvent>): DdzState {
        val (hands, kitty) = deal(rules, seed, redeals)
        return DdzState(
            rules = rules,
            seed = seed,
            redeals = redeals,
            hands = hands,
            kitty = kitty,
            phase = DdzPhase.BIDDING,
            turn = firstBidder,
            firstBidder = firstBidder,
            bids = emptyList(),
            landlord = null,
            bidMultiplier = 1,
            doubles = List(SEATS) { null },
            lead = null,
            bombs = 0,
            playCounts = List(SEATS) { 0 },
            log = log,
            settlement = null,
        )
    }

    /** Deals 17 cards to each seat and leaves 3 for the kitty. */
    fun deal(rules: DdzRules, seed: Long, redeals: Int): Pair<List<List<PlayingCard>>, List<PlayingCard>> {
        val random = Random(seed xor (redeals + 1) * -0x61c8864680b583ebL)
        val deck = when (rules.deal) {
            DealMode.SHUFFLED -> Decks.STANDARD_54.shuffled(random)
            DealMode.CLUMPED -> clumpedDeck(random)
        }
        val hands = List(SEATS) { ArrayList<PlayingCard>(HAND_SIZE + 3) }
        val chunk = if (rules.deal == DealMode.CLUMPED) 3 else 1
        var next = 0
        while (hands.any { it.size < HAND_SIZE }) {
            for (hand in hands) {
                repeat(minOf(chunk, HAND_SIZE - hand.size)) { hand += deck[next++] }
            }
        }
        return hands.map { it.sortedWith(DDZ_ORDER) } to deck.subList(next, deck.size).sortedWith(DDZ_ORDER)
    }

    /** Groups of equal values in random order, lightly disturbed, like a deck that was not shuffled after a game. */
    private fun clumpedDeck(random: Random): List<PlayingCard> {
        val deck = Decks.STANDARD_54.groupBy { it.ddzValue }.values.shuffled(random).flatten().toMutableList()
        repeat(12) {
            val a = random.nextInt(deck.size)
            val b = random.nextInt(deck.size)
            deck[a] = deck[b].also { deck[b] = deck[a] }
        }
        val cut = random.nextInt(deck.size)
        return deck.subList(cut, deck.size) + deck.subList(0, cut)
    }

    override fun toAct(state: DdzState): Seat? = if (state.phase == DdzPhase.FINISHED) null else state.turn

    override fun apply(state: DdzState, seat: Seat, action: DdzAction): DdzState {
        if (state.phase == DdzPhase.FINISHED) throw IllegalActionException("The game is over")
        if (seat != state.turn) throw IllegalActionException("It is seat ${state.turn}'s turn, not $seat's")
        return when (state.phase) {
            DdzPhase.BIDDING -> when (state.rules.bidding) {
                BiddingMode.SCORE -> scoreBid(state, action as? DdzAction.Bid ?: wrongAction(action))
                BiddingMode.ROB -> robBid(state, action as? DdzAction.Call ?: wrongAction(action))
            }
            DdzPhase.DOUBLING -> double(state, action as? DdzAction.Double ?: wrongAction(action))
            DdzPhase.PLAYING -> when (action) {
                is DdzAction.PlayCards -> play(state, action.cards)
                DdzAction.Pass -> pass(state)
                else -> wrongAction(action)
            }
            DdzPhase.FINISHED -> error("unreachable")
        }
    }

    private fun wrongAction(action: DdzAction): Nothing = throw IllegalActionException("$action is not allowed now")

    private fun next(seat: Seat) = (seat + 1) % SEATS

    // ---- Score bidding -------------------------------------------------------------------------

    /** Points a seat may bid now in score mode; 0, meaning pass, is always allowed. */
    fun allowedBids(state: DdzState): List<Int> {
        val highest = state.bids.maxOfOrNull { it.value } ?: 0
        return listOf(0) + (highest + 1..3)
    }

    private fun scoreBid(state: DdzState, bid: DdzAction.Bid): DdzState {
        if (bid.points !in allowedBids(state)) throw IllegalActionException("Cannot bid ${bid.points} now")
        val event = DdzEvent(state.turn, DdzEventKind.BID, bid.points)
        val bids = state.bids + event
        val after = state.copy(bids = bids, log = state.log + event)
        val best = bids.filter { it.value > 0 }.maxByOrNull { it.value }
        return when {
            bid.points == 3 -> crown(after, state.turn, 3)
            bids.size < SEATS -> after.copy(turn = next(state.turn))
            best != null -> crown(after, best.seat, best.value)
            else -> redeal(after)
        }
    }

    // ---- Rob bidding ---------------------------------------------------------------------------

    /** True when the seat to act would be robbing rather than calling. */
    fun isRobbing(state: DdzState): Boolean = state.bids.any { it.kind == DdzEventKind.CALL }

    private fun robBid(state: DdzState, call: DdzAction.Call): DdzState {
        val robbing = isRobbing(state)
        val kind = when {
            !robbing && call.yes -> DdzEventKind.CALL
            !robbing -> DdzEventKind.NO_CALL
            call.yes -> DdzEventKind.ROB
            else -> DdzEventKind.NO_ROB
        }
        val event = DdzEvent(state.turn, kind)
        val bids = state.bids + event
        val after = state.copy(bids = bids, log = state.log + event)
        val caller = bids.firstOrNull { it.kind == DdzEventKind.CALL }?.seat
        val robs = bids.count { it.kind == DdzEventKind.ROB }
        val claimant = bids.lastOrNull { it.kind == DdzEventKind.CALL || it.kind == DdzEventKind.ROB }?.seat
        val multiplier = 1 shl robs
        return when {
            bids.size < SEATS -> after.copy(turn = next(state.turn))
            caller == null -> redeal(after)
            // Everyone has spoken once. If someone robbed, the first caller gets one last chance to rob back.
            bids.size == SEATS && robs > 0 -> after.copy(turn = caller)
            else -> crown(after, claimant!!, multiplier)
        }
    }

    // ---- Shared bidding end --------------------------------------------------------------------

    private fun redeal(state: DdzState): DdzState {
        val log = state.log + DdzEvent(state.firstBidder, DdzEventKind.REDEAL)
        if (state.redeals + 1 > state.rules.redealLimit) {
            // Nobody wants it again and again: the first bidder of the last deal is made landlord.
            return crown(state.copy(log = log), state.firstBidder, 1)
        }
        return dealt(state.rules, state.seed, state.redeals + 1, next(state.firstBidder), log)
    }

    private fun crown(state: DdzState, landlord: Seat, bidMultiplier: Int): DdzState {
        val hands = state.hands.mapIndexed { seat, hand ->
            if (seat == landlord) (hand + state.kitty).sortedWith(DDZ_ORDER) else hand
        }
        val event = DdzEvent(landlord, DdzEventKind.LANDLORD, cards = state.kitty)
        val crowned = state.copy(
            hands = hands,
            landlord = landlord,
            bidMultiplier = bidMultiplier,
            log = state.log + event,
        )
        return if (state.rules.doubling) {
            crowned.copy(phase = DdzPhase.DOUBLING, turn = next(landlord))
        } else {
            crowned.copy(phase = DdzPhase.PLAYING, turn = landlord)
        }
    }

    // ---- Doubling ------------------------------------------------------------------------------

    private fun double(state: DdzState, action: DdzAction.Double): DdzState {
        val event = DdzEvent(state.turn, if (action.yes) DdzEventKind.DOUBLE else DdzEventKind.NO_DOUBLE)
        val doubles = state.doubles.toMutableList().also { it[state.turn] = action.yes }
        val after = state.copy(doubles = doubles, log = state.log + event)
        // Farmers decide first, then the landlord, who can answer their choice.
        return if (state.turn == state.landlord) {
            after.copy(phase = DdzPhase.PLAYING, turn = state.landlord)
        } else {
            after.copy(turn = next(state.turn))
        }
    }

    // ---- Playing -------------------------------------------------------------------------------

    /** The play the seat to act must beat, or null when it may lead anything. */
    fun toBeat(state: DdzState): Pattern? = state.lead?.takeIf { it.seat != state.turn }?.pattern

    private fun play(state: DdzState, cards: List<PlayingCard>): DdzState {
        val hand = state.hands[state.turn]
        if (cards.isEmpty()) throw IllegalActionException("Play at least one card")
        if (cards.toSet().size != cards.size || !hand.containsAll(cards)) {
            throw IllegalActionException("Those cards are not all in hand")
        }
        val pattern = Patterns.readingFor(cards, toBeat(state), state.rules)
            ?: throw IllegalActionException("${DdzCards.describe(cards)} does not make a play that is allowed here")

        val sorted = cards.sortedWith(DDZ_ORDER)
        val event = DdzEvent(state.turn, DdzEventKind.PLAY, cards = sorted, pattern = pattern)
        val remaining = hand - cards.toSet()
        val after = state.copy(
            hands = state.hands.mapIndexed { seat, h -> if (seat == state.turn) remaining else h },
            lead = TablePlay(state.turn, sorted, pattern),
            bombs = state.bombs + if (pattern.isBomb) 1 else 0,
            playCounts = state.playCounts.mapIndexed { seat, n -> if (seat == state.turn) n + 1 else n },
            log = state.log + event,
        )
        return if (remaining.isEmpty()) finish(after, state.turn) else after.copy(turn = next(state.turn))
    }

    private fun pass(state: DdzState): DdzState {
        if (toBeat(state) == null) throw IllegalActionException("You lead this round and cannot pass")
        return state.copy(turn = next(state.turn), log = state.log + DdzEvent(state.turn, DdzEventKind.PASS))
    }

    private fun finish(state: DdzState, winner: Seat): DdzState {
        val landlord = state.landlord!!
        val rules = state.rules
        val landlordWon = winner == landlord
        val farmers = (0 until SEATS).filter { it != landlord }
        // Spring: the losing side never got to play (the landlord's opening lead does not count for them).
        val spring = if (landlordWon) farmers.all { state.playCounts[it] == 0 } else state.playCounts[landlord] == 1

        var multiplier = state.bidMultiplier
        if (rules.bombsDouble) multiplier = multiplier shl minOf(state.bombs, 20)
        if (spring && rules.springDoubles) multiplier *= 2

        val scores = IntArray(SEATS)
        val landlordFactor = if (state.doubles[landlord] == true) 2 else 1
        for (farmer in farmers) {
            var stake = multiplier * landlordFactor * (if (state.doubles[farmer] == true) 2 else 1)
            if (rules.maxMultiplier > 0) stake = minOf(stake, rules.maxMultiplier)
            val points = rules.baseScore * stake
            if (landlordWon) {
                scores[landlord] += points
                scores[farmer] -= points
            } else {
                scores[landlord] -= points
                scores[farmer] += points
            }
        }
        val settlement = Settlement(landlordWon, state.bidMultiplier, state.bombs, spring, multiplier, scores.toList())
        return state.copy(phase = DdzPhase.FINISHED, settlement = settlement)
    }

    override fun result(state: DdzState): GameResult? = state.result

    override fun view(state: DdzState, seat: Seat?): DdzView = DdzView.of(state, seat)

    /** Every hand is shown, and the landlord's three cards before anyone takes them. */
    override fun openView(state: DdzState, seat: Seat?): DdzView =
        DdzView.of(state, seat).copy(revealedHands = state.hands, kitty = state.kitty)

    override fun createBot(level: BotLevel, seed: Long): Bot<DdzView, DdzAction> = DdzBot(level, seed)

    override fun guide(rules: DdzRules?): List<GuideSection> = DdzGuide.sections(rules)
}
