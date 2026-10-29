package io.github.konyaco.tukuai.engine.bluff

import io.github.konyaco.tukuai.engine.bluff.bot.BluffBot
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
import kotlin.random.Random

/**
 * Creeper bluff (吹牛苦力怕), for 2 to 8 players: a bluffing game in the family of Cheat.
 * Each round deals a few cards to everyone and calls for one rank. In turn players lay one
 * to three cards face down, saying they are all of that rank, and jokers count as it. The
 * next player either lays down cards of their own or calls the last play a lie and turns
 * it over. Whoever was wrong, the liar or the one who called an honest play a lie, lights
 * the fuse of their creeper: it blows on one of six lightings, nobody knows which. Every
 * call ends the round, and the last player whose creeper has not blown wins.
 */
object Bluff : GameDefinition<BluffRules, BluffState, BluffAction, BluffView> {
    override val id = "bluff"
    override val rulesCodec: WireCodec<BluffRules> = BluffRules.CODEC
    override val actionCodec: WireCodec<BluffAction> = BluffCodecs.ACTION
    override val viewCodec: WireCodec<BluffView> = BluffCodecs.VIEW
    override val presets: List<RulesPreset<BluffRules>> = BluffRules.PRESETS

    override fun seatCount(rules: BluffRules) = rules.players

    /** Ranks from the top down, the deck taking as many as it needs. */
    private val RANKS = listOf(
        Rank.ACE, Rank.KING, Rank.QUEEN, Rank.JACK, Rank.TEN, Rank.NINE, Rank.EIGHT,
        Rank.SEVEN, Rank.SIX, Rank.FIVE, Rank.FOUR, Rank.THREE, Rank.TWO,
    )

    /**
     * The ranks in play: from the ace down, at least three and enough that every hand can be
     * dealt with all four suits of each, so a few cards are always left out unseen.
     */
    fun ranks(rules: BluffRules): List<Rank> {
        val needed = rules.players * rules.handSize - jokerCount(rules)
        return RANKS.take(((needed + 3) / 4).coerceIn(3, RANKS.size))
    }

    fun jokerCount(rules: BluffRules) = if (rules.jokers) 2 else 0

    fun deck(rules: BluffRules): List<PlayingCard> =
        ranks(rules).flatMap { rank -> Suit.entries.map { PlayingCard.Regular(rank, it) } } +
            if (rules.jokers) listOf(PlayingCard.BlackJoker, PlayingCard.RedJoker) else emptyList()

    /** Whether [card] is what a play claims when [target] is called for: that rank, or a joker. */
    fun matches(card: PlayingCard, target: Rank): Boolean = card !is PlayingCard.Regular || card.rank == target

    /** How many cards of the deck count as [target]: its four suits and the jokers. */
    fun matchCount(rules: BluffRules): Int = 4 + jokerCount(rules)

    override fun start(rules: BluffRules, seed: Long): BluffState {
        val random = Random(seed)
        val fuses = List(rules.players) {
            if (rules.penalty == BluffPenalty.FUSE) random.nextInt(rules.chances) + 1 else rules.chances
        }
        val empty = BluffState(
            rules = rules, seed = seed, round = 0, target = Rank.ACE,
            hands = List(rules.players) { emptyList() }, fuses = fuses, lit = List(rules.players) { 0 },
            phase = BluffPhase.PLAY, turn = 0, claim = null, pile = emptyList(), reveal = null, log = emptyList(),
        )
        return deal(empty, random.nextInt(rules.players))
    }

    /** Deals a new round to everyone still in, calling for a random rank; [first] plays first. */
    private fun deal(state: BluffState, first: Seat): BluffState {
        val round = state.round + 1
        val random = Random(state.seed xor round * -0x61c8864680b583ebL)
        val deck = deck(state.rules).shuffled(random)
        val target = ranks(state.rules).random(random)
        var next = 0
        val hands = state.hands.indices.map { seat ->
            if (state.isOut(seat)) emptyList() else deck.subList(next, next + state.rules.handSize).also { next += it.size }
        }
        return state.copy(
            round = round, target = target, hands = hands, phase = BluffPhase.PLAY, turn = first,
            claim = null, pile = emptyList(),
            log = state.log + BluffEvent(first, BluffEventKind.DEAL, rank = target),
        )
    }

    override fun toAct(state: BluffState): Seat? = when (state.phase) {
        BluffPhase.PLAY -> state.turn
        BluffPhase.PENALTY -> state.reveal!!.loser
        BluffPhase.OVER -> null
    }

    /** The next seat round from [seat] that is still in, other than [seat] itself. */
    fun nextAlive(state: BluffState, seat: Seat): Seat {
        val n = state.hands.size
        return (1 until n).map { (seat + it) % n }.first { !state.isOut(it) }
    }

    /**
     * Who answers a play by [seat]: the next player still in with cards left. When nobody
     * else has any, the next player still in has to call it.
     */
    fun answerer(state: BluffState, seat: Seat): Seat {
        val n = state.hands.size
        val others = (1 until n).map { (seat + it) % n }.filter { !state.isOut(it) }
        return others.firstOrNull { state.hands[it].isNotEmpty() } ?: others.first()
    }

    override fun apply(state: BluffState, seat: Seat, action: BluffAction): BluffState {
        val toAct = toAct(state) ?: throw IllegalActionException("The game is over")
        if (seat != toAct) throw IllegalActionException("It is seat $toAct's turn, not $seat's")
        return when (action) {
            is BluffAction.Play -> play(state, action.cards)
            BluffAction.Call -> call(state)
            BluffAction.Light -> light(state)
        }
    }

    /** Why [cards] may not be laid down now by the seat to play, or null if they may. */
    fun problem(rules: BluffRules, hand: List<PlayingCard>, cards: List<PlayingCard>): String? = when {
        cards.isEmpty() -> "Lay down at least one card"
        cards.size > rules.maxPlay -> "Lay down at most ${rules.maxPlay} cards"
        cards.toSet().size != cards.size -> "The same card twice"
        !hand.containsAll(cards) -> "You do not hold those cards"
        else -> null
    }

    private fun play(state: BluffState, cards: List<PlayingCard>): BluffState {
        if (state.phase != BluffPhase.PLAY) throw IllegalActionException("Take your penalty first")
        problem(state.rules, state.hands[state.turn], cards)?.let { throw IllegalActionException(it) }
        val seat = state.turn
        val after = state.copy(
            hands = state.hands.mapIndexed { s, h -> if (s == seat) h - cards.toSet() else h },
            claim = BluffClaim(seat, cards),
            pile = state.pile + cards,
            log = state.log + BluffEvent(seat, BluffEventKind.PLAY, cards),
        )
        return after.copy(turn = answerer(after, seat))
    }

    private fun call(state: BluffState): BluffState {
        if (state.phase != BluffPhase.PLAY) throw IllegalActionException("Take your penalty first")
        val claim = state.claim ?: throw IllegalActionException("There is nothing to call yet")
        if (claim.seat == state.turn) throw IllegalActionException("That is your own play")
        val lied = claim.cards.any { !matches(it, state.target) }
        val reveal = BluffReveal(state.turn, claim.seat, claim.cards, lied)
        return state.copy(
            phase = BluffPhase.PENALTY,
            reveal = reveal,
            log = state.log + BluffEvent(state.turn, BluffEventKind.CALL, claim.cards, other = claim.seat, flag = lied),
        )
    }

    private fun light(state: BluffState): BluffState {
        if (state.phase != BluffPhase.PENALTY) throw IllegalActionException("Nobody has been caught out")
        val loser = state.reveal!!.loser
        val lit = state.lit.mapIndexed { s, n -> if (s == loser) n + 1 else n }
        val after = state.copy(lit = lit)
        val out = after.isOut(loser)
        val logged = after.copy(log = state.log + BluffEvent(loser, BluffEventKind.LIGHT, count = lit[loser], flag = out))
        if (logged.alive.size <= 1) return logged.copy(phase = BluffPhase.OVER, claim = null)
        // The loser starts the next round, or with them out, whoever sits after them.
        return deal(logged, if (out) nextAlive(logged, loser) else loser)
    }

    override fun result(state: BluffState): GameResult? = state.result

    override val mayHarm: Boolean get() = true

    /** Whoever's creeper just blew. */
    override fun harmed(before: BluffState, after: BluffState): List<Seat> =
        after.hands.indices.filter { after.isOut(it) && !before.isOut(it) }

    override fun view(state: BluffState, seat: Seat?): BluffView = BluffView.of(state, seat, open = false)

    override fun openView(state: BluffState, seat: Seat?): BluffView = BluffView.of(state, seat, open = true)

    override fun createBot(level: BotLevel, seed: Long): Bot<BluffView, BluffAction> = BluffBot(level, seed)

    override fun guide(rules: BluffRules?): List<GuideSection> = BluffGuide.sections(rules)
}
