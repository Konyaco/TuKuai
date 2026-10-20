package io.github.konyaco.tukuai.engine.paodekuai

import io.github.konyaco.tukuai.engine.card.Decks
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.card.Suit
import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.GameDefinition
import io.github.konyaco.tukuai.engine.core.GameResult
import io.github.konyaco.tukuai.engine.core.GuideSection
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.RulesPreset
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.doudizhu.DDZ_ORDER
import io.github.konyaco.tukuai.engine.paodekuai.bot.PdkBot
import kotlin.random.Random

/**
 * Pao De Kuai ("run fast"), for three: everyone for themselves, and the first to play out
 * their hand wins. Whoever holds the 3 of spades leads, and each play must beat the last
 * one or pass, as in Dou Dizhu, with fewer shapes and no jokers.
 */
object PaoDeKuai : GameDefinition<PdkRules, PdkState, PdkAction, PdkView> {
    const val SEATS = 3

    /** Whoever holds this card leads first, with it. */
    val STARTER: PlayingCard = PlayingCard.Regular(Rank.THREE, Suit.SPADES)

    override val id = "paodekuai"
    override val rulesCodec: WireCodec<PdkRules> = PdkRules.CODEC
    override val actionCodec: WireCodec<PdkAction> = PdkCodecs.ACTION
    override val viewCodec: WireCodec<PdkView> = PdkCodecs.VIEW
    override val presets: List<RulesPreset<PdkRules>> = PdkRules.PRESETS

    override fun seatCount(rules: PdkRules) = SEATS

    /**
     * The cards in play: no jokers, one 2 (spades) and three aces, which deals 16 each.
     * For 15 each, only the king of spades is left of the kings too.
     */
    fun deck(rules: PdkRules): List<PlayingCard> = Decks.STANDARD_54.filter { card ->
        card is PlayingCard.Regular && when (card.rank) {
            Rank.TWO -> card.suit == Suit.SPADES
            Rank.ACE -> card.suit != Suit.DIAMONDS
            Rank.KING -> rules.handSize == 16 || card.suit == Suit.SPADES
            else -> true
        }
    }

    override fun start(rules: PdkRules, seed: Long): PdkState {
        val deck = deck(rules).shuffled(Random(seed))
        val hands = List(SEATS) { seat -> deck.filterIndexed { i, _ -> i % SEATS == seat }.sortedWith(DDZ_ORDER) }
        val starter = hands.indexOfFirst { STARTER in it }
        return PdkState(
            rules = rules,
            seed = seed,
            hands = hands,
            starter = starter,
            turn = starter,
            lead = null,
            playCounts = List(SEATS) { 0 },
            bombs = List(SEATS) { 0 },
            log = emptyList(),
            settlement = null,
        )
    }

    override fun toAct(state: PdkState): Seat? = if (state.finished) null else state.turn

    fun next(seat: Seat) = (seat + 1) % SEATS

    /** The play the seat to act must beat, or null when it leads. */
    fun toBeat(state: PdkState): PdkPattern? = state.lead?.takeIf { it.seat != state.turn }?.pattern

    fun turnOf(rules: PdkRules, hand: List<PlayingCard>, toBeat: PdkPattern?, handCounts: List<Int>, turn: Seat, opening: Boolean) =
        PdkTurn(rules, hand, toBeat, nextHasOne = handCounts[next(turn)] == 1, opening = opening)

    fun turnOf(state: PdkState): PdkTurn =
        turnOf(state.rules, state.hands[state.turn], toBeat(state), state.hands.map { it.size }, state.turn, state.log.none { it.kind == PdkEventKind.PLAY })

    override fun apply(state: PdkState, seat: Seat, action: PdkAction): PdkState {
        if (state.finished) throw IllegalActionException("The game is over")
        if (seat != state.turn) throw IllegalActionException("It is seat ${state.turn}'s turn, not $seat's")
        return when (action) {
            is PdkAction.PlayCards -> play(state, action.cards)
            PdkAction.Pass -> pass(state)
        }
    }

    private fun play(state: PdkState, cards: List<PlayingCard>): PdkState {
        val turn = turnOf(state)
        turn.problem(cards)?.let { throw IllegalActionException(it) }
        val pattern = turn.reading(cards)!!
        val sorted = cards.sortedWith(DDZ_ORDER)
        val remaining = state.hands[state.turn] - cards.toSet()
        val after = state.copy(
            hands = state.hands.mapIndexed { seat, h -> if (seat == state.turn) remaining else h },
            lead = PdkTablePlay(state.turn, sorted, pattern),
            playCounts = state.playCounts.mapIndexed { seat, n -> if (seat == state.turn) n + 1 else n },
            log = state.log + PdkEvent(state.turn, PdkEventKind.PLAY, sorted, pattern),
        )
        if (remaining.isNotEmpty()) return after.copy(turn = next(state.turn))
        // Going out with a bomb wins its round too: nobody gets to answer it.
        return finish(if (pattern.isBomb) bombWon(after, state.turn) else after, state.turn)
    }

    private fun pass(state: PdkState): PdkState {
        val turn = turnOf(state)
        if (turn.toBeat == null) throw IllegalActionException("You lead this round and cannot pass")
        if (!turn.canPass) throw IllegalActionException("You can beat that, so you must")
        val next = next(state.turn)
        val after = state.copy(turn = next, log = state.log + PdkEvent(state.turn, PdkEventKind.PASS))
        val lead = state.lead!!
        // Everyone passed: the round goes to whoever played last, and a bomb collects.
        return if (next == lead.seat && lead.pattern.isBomb) bombWon(after, lead.seat) else after
    }

    private fun bombWon(state: PdkState, seat: Seat): PdkState = state.copy(
        bombs = state.bombs.mapIndexed { s, n -> if (s == seat) n + 1 else n },
        log = state.log + PdkEvent(seat, PdkEventKind.BOMB_WON),
    )

    /**
     * Each other player pays the winner a point a card left in hand, double if they never
     * played; one card left pays nothing. Each bomb that won a round takes its points from
     * each other player.
     */
    private fun finish(state: PdkState, winner: Seat): PdkState {
        val cardScores = IntArray(SEATS)
        val cardsLeft = state.hands.map { it.size }
        val shutOut = state.playCounts.map { it == 0 }
        for (seat in 0 until SEATS) {
            if (seat == winner) continue
            val left = cardsLeft[seat]
            val pay = if (left == 1) 0 else left * if (shutOut[seat]) 2 else 1
            cardScores[seat] -= pay
            cardScores[winner] += pay
        }
        val bombScores = IntArray(SEATS)
        for (seat in 0 until SEATS) {
            val points = state.bombs[seat] * state.rules.bombPoints
            for (other in 0 until SEATS) {
                if (other == seat) continue
                bombScores[other] -= points
                bombScores[seat] += points
            }
        }
        val settlement = PdkSettlement(
            winner = winner,
            cardsLeft = cardsLeft,
            shutOut = shutOut,
            bombs = state.bombs,
            cardScores = cardScores.toList(),
            bombScores = bombScores.toList(),
            scores = List(SEATS) { cardScores[it] + bombScores[it] },
        )
        return state.copy(settlement = settlement)
    }

    override fun result(state: PdkState): GameResult? = state.result

    override fun view(state: PdkState, seat: Seat?): PdkView = PdkView.of(state, seat)

    override fun openView(state: PdkState, seat: Seat?): PdkView = PdkView.of(state, seat).copy(revealedHands = state.hands)

    override fun createBot(level: BotLevel, seed: Long): Bot<PdkView, PdkAction> = PdkBot(level, seed)

    override fun guide(rules: PdkRules?): List<GuideSection> = PdkGuide.sections(rules)
}
