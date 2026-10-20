package io.github.konyaco.tukuai.engine.guandan

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.GameDefinition
import io.github.konyaco.tukuai.engine.core.GameResult
import io.github.konyaco.tukuai.engine.core.GuideSection
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.RulesPreset
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.guandan.bot.GdBot
import kotlin.random.Random

/**
 * Guandan, for four in two teams: partners sit opposite each other, seats 0 and 2
 * against 1 and 3. Two decks are dealt out, 27 cards each, and a team wins the round
 * when one of its players goes out first. How far the team goes up depends on where
 * the partner finishes, and the next round is played at the winners' level, with the
 * hearts of that rank wild.
 */
object Guandan : GameDefinition<GdRules, GdState, GdAction, GdView> {
    const val SEATS = 4
    const val HAND_SIZE = 27

    override val id = "guandan"
    override val rulesCodec: WireCodec<GdRules> = GdRules.CODEC
    override val actionCodec: WireCodec<GdAction> = GdCodecs.ACTION
    override val viewCodec: WireCodec<GdView> = GdCodecs.VIEW
    override val presets: List<RulesPreset<GdRules>> = GdRules.PRESETS

    override fun seatCount(rules: GdRules) = SEATS

    fun team(seat: Seat): Int = seat % 2

    fun partner(seat: Seat): Seat = (seat + 2) % SEATS

    /** Levels gained by the winners when the partner finished in [partnerPlace], counting from 0. */
    fun gain(partnerPlace: Int): Int = when (partnerPlace) {
        1 -> 3
        2 -> 2
        else -> 1
    }

    /** The cards that may be given back for a tribute: 10 or lower, or anything when there are none. */
    fun returnable(hand: List<PlayingCard>): List<PlayingCard> {
        val low = hand.filter { it is PlayingCard.Regular && GdValue.natural(it.rank) <= GdValue.TEN }
        return low.ifEmpty { hand }
    }

    /** The tribute a seat pays: its strongest card that is not wild. */
    fun tributeCard(hand: List<PlayingCard>, level: Rank): PlayingCard? =
        hand.filterNot { it.isWild(level) }.maxWithOrNull(GdCards.ascending(level))

    private fun deal(seed: Long, round: Int, level: Rank): List<List<PlayingCard>> {
        val deck = GdCards.DECK.shuffled(Random(seed * 1_000_003L + round))
        return List(SEATS) { seat -> deck.subList(seat * HAND_SIZE, (seat + 1) * HAND_SIZE).sortedWith(GdCards.ascending(level)) }
    }

    override fun start(rules: GdRules, seed: Long): GdState {
        val first = Random(seed).nextInt(SEATS)
        return GdState(
            rules = rules,
            seed = seed,
            round = 0,
            levels = listOf(0, 0),
            levelTeam = -1,
            level = 0,
            phase = GdPhase.PLAYING,
            hands = deal(seed, 0, GdValue.LADDER[0]),
            turn = first,
            lead = null,
            passes = 0,
            order = emptyList(),
            tributes = emptyList(),
            leader = first,
            log = emptyList(),
            rounds = emptyList(),
            scores = List(SEATS) { 0 },
            winners = null,
        )
    }

    override fun toAct(state: GdState): Seat? = if (state.finished) null else state.turn

    /** The next seat after [seat] still holding cards. */
    fun nextActive(state: GdState, seat: Seat): Seat {
        var s = seat
        repeat(SEATS) {
            s = (s + 1) % SEATS
            if (s !in state.order) return s
        }
        return seat
    }

    fun turnOf(state: GdState): GdTurn = GdTurn(state.hands[state.turn], state.lead?.pattern, state.levelRank)

    override fun apply(state: GdState, seat: Seat, action: GdAction): GdState {
        if (state.finished) throw IllegalActionException("The game is over")
        if (seat != state.turn) throw IllegalActionException("It is seat ${state.turn}'s turn, not $seat's")
        return when (state.phase) {
            GdPhase.PLAYING -> when (action) {
                is GdAction.PlayCards -> play(state, action.cards)
                GdAction.Pass -> pass(state)
                else -> throw IllegalActionException("Play or pass")
            }
            GdPhase.RETURN -> when (action) {
                is GdAction.Return -> giveBack(state, action.card)
                else -> throw IllegalActionException("Give a card back first")
            }
            GdPhase.ROUND_OVER -> when (action) {
                GdAction.Next -> nextRound(state)
                else -> throw IllegalActionException("The round is over")
            }
            GdPhase.FINISHED -> throw IllegalActionException("The game is over")
        }
    }

    private fun play(state: GdState, cards: List<PlayingCard>): GdState {
        val turn = turnOf(state)
        turn.problem(cards)?.let { throw IllegalActionException(it) }
        val pattern = turn.reading(cards)!!
        val seat = state.turn
        val sorted = cards.sortedWith(GdCards.order(state.levelRank))
        val remaining = GdCards.minus(state.hands[seat], cards)!!
        var after = state.copy(
            hands = state.hands.mapIndexed { s, h -> if (s == seat) remaining else h },
            lead = GdTablePlay(seat, sorted, pattern),
            passes = 0,
            log = state.log + GdEvent(seat, GdEventKind.PLAY, sorted, pattern),
        )
        if (remaining.isEmpty()) {
            after = after.copy(order = after.order + seat, log = after.log + GdEvent(seat, GdEventKind.OUT, other = after.order.size))
            if (roundOver(after.order)) return endRound(after)
        }
        return after.copy(turn = nextActive(after, seat))
    }

    private fun pass(state: GdState): GdState {
        val lead = state.lead ?: throw IllegalActionException("You lead, so you have to play")
        val after = state.copy(passes = state.passes + 1, log = state.log + GdEvent(state.turn, GdEventKind.PASS))
        val active = SEATS - state.order.size
        val ownerIn = lead.seat !in state.order
        val needed = if (ownerIn) active - 1 else active
        if (after.passes < needed) return after.copy(turn = nextActive(after, state.turn))
        // Everyone passed: the last to play leads, or their partner if they are out.
        if (ownerIn) return after.copy(lead = null, passes = 0, turn = lead.seat)
        val partner = partner(lead.seat)
        return after.copy(
            lead = null,
            passes = 0,
            turn = partner,
            log = after.log + GdEvent(partner, GdEventKind.PARTNER_LEADS, other = lead.seat),
        )
    }

    /** The round is over once both of a team are out, or three players are. */
    fun roundOver(order: List<Seat>): Boolean = order.size >= 3 || (order.size == 2 && team(order[0]) == team(order[1]))

    private fun endRound(state: GdState): GdState {
        val order = state.order.toMutableList()
        var s = order.last()
        while (order.size < SEATS) {
            s = (s + 1) % SEATS
            if (s !in order) order += s
        }
        val winner = order[0]
        val winners = team(winner)
        val gain = gain(order.indexOf(partner(winner)))
        val levels = state.levels.mapIndexed { t, l -> if (t == winners) minOf(GdValue.LADDER.size - 1, l + gain) else l }
        val scores = state.scores.mapIndexed { seat, points -> points + if (team(seat) == winners) gain else -gain }
        val round = GdRound(state.level, state.levelTeam, order, gain, levels)
        val over = !state.rules.toAce || passedAce(state, winners, order)
        return state.copy(
            phase = if (over) GdPhase.FINISHED else GdPhase.ROUND_OVER,
            order = order,
            levels = levels,
            scores = scores,
            rounds = state.rounds + round,
            lead = null,
            passes = 0,
            turn = winner,
            winners = if (over) setOf(winner, partner(winner)) else null,
        )
    }

    /** A team wins the long game by winning a round at its own A, with the partner not last. */
    private fun passedAce(state: GdState, winners: Int, order: List<Seat>): Boolean =
        state.levelTeam == winners && state.level == GdValue.LADDER.size - 1 && order.last() != partner(order[0])

    /** Deals the next round at the winners' level, and takes the tribute. */
    private fun nextRound(state: GdState): GdState {
        val last = state.rounds.last()
        val levelTeam = last.winnerTeam
        val level = state.levels[levelTeam]
        val rank = GdValue.LADDER[level]
        val round = state.round + 1
        val hands = deal(state.seed, round, rank).map { it.toMutableList() }
        val order = last.order
        val dealt = state.copy(
            round = round,
            levelTeam = levelTeam,
            level = level,
            phase = GdPhase.PLAYING,
            lead = null,
            passes = 0,
            order = emptyList(),
            tributes = emptyList(),
            log = emptyList(),
        )
        // Both losers pay when they came third and fourth, otherwise whoever came last.
        val payers = if (team(order[2]) == team(order[3]) && team(order[2]) != team(order[0])) listOf(order[2], order[3]) else listOf(order[3])
        if (payers.sumOf { p -> hands[p].count { it == PlayingCard.RedJoker } } >= 2) {
            return dealt.copy(
                hands = hands.map { it.sortedWith(GdCards.ascending(rank)) },
                turn = order[0],
                leader = order[0],
                log = listOf(GdEvent(order[0], GdEventKind.NO_TRIBUTE, other = payers.first())),
            )
        }
        val paid = payers.map { it to tributeCard(hands[it], rank)!! }
        // The bigger tribute goes to the winner, the other to second place; on a tie, third place's goes first.
        val ranked = paid.sortedWith(compareByDescending<Pair<Seat, PlayingCard>> { it.second.gdValue(rank) }.thenBy { order.indexOf(it.first) })
        val tributes = ranked.mapIndexed { i, (from, card) -> GdTribute(from, order[i], card) }
        val log = ArrayList<GdEvent>()
        for (t in tributes) {
            hands[t.from].remove(t.card)
            hands[t.to].add(t.card)
            log += GdEvent(t.from, GdEventKind.TRIBUTE, listOf(t.card), other = t.to)
        }
        return dealt.copy(
            phase = GdPhase.RETURN,
            hands = hands.map { it.sortedWith(GdCards.ascending(rank)) },
            tributes = tributes,
            turn = tributes[0].to,
            leader = tributes[0].from,
            log = log,
        )
    }

    private fun giveBack(state: GdState, card: PlayingCard): GdState {
        val index = state.tributes.indexOfFirst { it.to == state.turn && it.returned == null }
        val tribute = state.tributes.getOrNull(index) ?: throw IllegalActionException("Nothing to give back")
        val hand = state.hands[state.turn]
        if (card !in returnable(hand)) throw IllegalActionException("Give back a card of 10 or lower")
        val rank = state.levelRank
        val hands = state.hands.mapIndexed { s, h ->
            when (s) {
                state.turn -> GdCards.minus(h, listOf(card))!!
                tribute.from -> (h + card).sortedWith(GdCards.ascending(rank))
                else -> h
            }
        }
        val tributes = state.tributes.mapIndexed { i, t -> if (i == index) t.copy(returned = card) else t }
        val after = state.copy(
            hands = hands,
            tributes = tributes,
            log = state.log + GdEvent(state.turn, GdEventKind.RETURN, listOf(card), other = tribute.from),
        )
        val next = tributes.firstOrNull { it.returned == null }
        return if (next != null) after.copy(turn = next.to) else after.copy(phase = GdPhase.PLAYING, turn = state.leader)
    }

    override fun result(state: GdState): GameResult? = state.result

    override fun view(state: GdState, seat: Seat?): GdView = GdView.of(state, seat)

    override fun openView(state: GdState, seat: Seat?): GdView = GdView.of(state, seat).copy(revealedHands = state.hands)

    override fun createBot(level: BotLevel, seed: Long): Bot<GdView, GdAction> = GdBot(level, seed)

    override fun guide(rules: GdRules?): List<GuideSection> = GdGuide.sections(rules)
}
