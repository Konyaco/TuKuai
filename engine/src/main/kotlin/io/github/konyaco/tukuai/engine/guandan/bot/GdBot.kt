package io.github.konyaco.tukuai.engine.guandan.bot

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.guandan.GdAction
import io.github.konyaco.tukuai.engine.guandan.GdCards
import io.github.konyaco.tukuai.engine.guandan.GdPatternType
import io.github.konyaco.tukuai.engine.guandan.GdPhase
import io.github.konyaco.tukuai.engine.guandan.GdPlay
import io.github.konyaco.tukuai.engine.guandan.GdTurn
import io.github.konyaco.tukuai.engine.guandan.GdValue
import io.github.konyaco.tukuai.engine.guandan.GdView
import io.github.konyaco.tukuai.engine.guandan.Guandan
import io.github.konyaco.tukuai.engine.guandan.gdValue
import io.github.konyaco.tukuai.engine.guandan.isWild
import kotlin.random.Random

/**
 * Computer player for Guandan.
 *
 * - EASY plays the smallest thing that works and pays no attention to its partner.
 * - NORMAL plans its hand with [GdHandAnalysis] and plays for its team: it lets its
 *   partner's plays stand unless it can go out, feeds a partner who is nearly out with
 *   small cards, and spends bombs only when the other side is getting close.
 * - HARD also tries its best few plays against guesses of the other hands, played out by
 *   NORMAL players, and keeps the one that does best for its team, see [GdSearch].
 */
class GdBot(private val level: BotLevel, seed: Long, private val playouts: Int = GdSearch.PLAYOUTS) : Bot<GdView, GdAction> {
    private val random = Random(seed)
    private val search by lazy { GdSearch(random, playouts) }

    override fun act(view: GdView): GdAction {
        val seat = requireNotNull(view.seat) { "A bot needs a seat" }
        return when (view.phase) {
            GdPhase.ROUND_OVER -> GdAction.Next
            GdPhase.RETURN -> GdAction.Return(giveBack(view))
            GdPhase.PLAYING -> play(view, seat)
            GdPhase.FINISHED -> error("The game is over")
        }
    }

    /** The card to give back for a tribute: the one the hand misses least, or for EASY just the lowest. */
    private fun giveBack(view: GdView): PlayingCard {
        val choices = Guandan.returnable(view.hand).filterNot { it.isWild(view.levelRank) }.ifEmpty { Guandan.returnable(view.hand) }
        if (level == BotLevel.EASY) return choices.minWith(GdCards.ascending(view.levelRank))
        return choices.distinct().maxWith(
            compareBy<PlayingCard> { GdHandAnalysis.score(GdCards.minus(view.hand, listOf(it))!!, view.levelRank) }
                .thenBy { -it.gdValue(view.levelRank) },
        )
    }

    private fun play(view: GdView, seat: Seat): GdAction {
        val turn = checkNotNull(view.turnRules()) { "It is not this bot's turn" }
        val choice = when {
            level == BotLevel.EASY -> easyPlay(turn)
            turn.toBeat == null -> lead(view, seat, turn)
            else -> follow(view, seat, turn)
        }
        val action = legal(turn, choice)
        if (level != BotLevel.HARD || playouts <= 0) return action
        return search.choose(view, seat, candidates(view, turn, action))
    }

    private fun legal(turn: GdTurn, choice: GdPlay?): GdAction = when {
        choice != null && turn.reading(choice.cards) != null -> GdAction.PlayCards(choice.cards)
        turn.canPass -> GdAction.Pass
        else -> GdAction.PlayCards(turn.plays.first().cards)
    }

    private fun wilds(play: GdPlay, view: GdView) = play.cards.count { it.isWild(view.levelRank) }

    private val cheap = compareBy<GdPlay>({ it.pattern.isBomb }, { it.pattern.tier }, { it.pattern.main }, { -it.cards.size })

    private fun easyPlay(turn: GdTurn): GdPlay? {
        val options = turn.plays
        val calm = options.filter { !it.pattern.isBomb }
        if (turn.toBeat == null) {
            // Lead the lowest card there is, taking the longest play that starts there.
            return calm.minWithOrNull(
                compareBy<GdPlay> { p -> p.cards.minOf { it.gdValue(turn.level) } }.thenByDescending { it.cards.size },
            ) ?: options.first()
        }
        if (random.nextInt(4) == 0) return null
        return calm.minByOrNull { it.pattern.main }
    }

    private fun others(seat: Seat) = (0 until Guandan.SEATS).filter { Guandan.team(it) != Guandan.team(seat) }

    /** Fewest cards a player on the other side still holds, of those not out. */
    private fun danger(view: GdView, seat: Seat): Int =
        others(seat).filter { it !in view.order }.minOfOrNull { view.handCounts[it] } ?: Guandan.HAND_SIZE

    private fun lead(view: GdView, seat: Seat, turn: GdTurn): GdPlay {
        val options = turn.plays
        options.firstOrNull { it.cards.size == turn.hand.size }?.let { return it }
        val level = view.levelRank

        // A partner about to go out gets something small of the size they can answer.
        val partner = Guandan.partner(seat)
        val partnerLeft = view.handCounts[partner]
        if (partner !in view.order && partnerLeft <= 3) {
            val feed = options.filter {
                !it.pattern.isBomb && it.cards.size <= partnerLeft && wilds(it, view) == 0 &&
                    (it.pattern.type == GdPatternType.SINGLE || it.pattern.type == GdPatternType.PAIR)
            }.sortedWith(compareByDescending<GdPlay> { it.cards.size == partnerLeft }.thenBy { it.pattern.main })
            feed.firstOrNull()?.let { return it }
        }

        val split = GdHandAnalysis.split(turn.hand, level).plays.filter { turn.reading(it.cards) != null }
        if (split.isEmpty()) return options.minWith(cheap)
        val danger = danger(view, seat)
        val calm = split.filter { !it.pattern.isBomb }
        if (calm.isEmpty()) return split.minWith(cheap)
        // Nothing the size of a hand that is about to go out, if there's anything else.
        val safe = calm.filter { !(danger <= 3 && it.cards.size == danger) }
        if (safe.isEmpty() && danger <= 3) return calm.maxBy { it.pattern.main }
        return safe.ifEmpty { calm }.sortedWith(
            compareBy<GdPlay> { GdHandAnalysis.value(it.pattern) - it.cards.size }.thenBy { it.pattern.main },
        ).first()
    }

    private fun follow(view: GdView, seat: Seat, turn: GdTurn): GdPlay? {
        val options = turn.plays
        if (options.isEmpty()) return null
        options.firstOrNull { it.cards.size == turn.hand.size }?.let { return it }
        val lead = view.lead!!
        val level = view.levelRank
        val split = GdHandAnalysis.split(turn.hand, level)

        // The partner's play stands, unless beating it leaves one play to go out with.
        if (Guandan.team(lead.seat) == Guandan.team(seat)) {
            val finisher = options.filter { !it.pattern.isBomb }.firstOrNull { p ->
                val rest = GdCards.minus(turn.hand, p.cards)!!
                GdHandAnalysis.split(rest, level).plays.size <= 1
            }
            return finisher
        }

        val before = split.score
        data class Scored(val play: GdPlay, val gain: Int)
        // Score the cheapest few of each kind rather than every play: two decks give many.
        val shortlist = options.filter { !it.pattern.isBomb }.sortedWith(cheap).take(SHORTLIST) +
            options.filter { it.pattern.isBomb }.sortedWith(cheap).take(3)
        val scored = shortlist.map { Scored(it, GdHandAnalysis.score(GdCards.minus(turn.hand, it.cards)!!, level) - before) }
            .sortedWith(compareByDescending<Scored> { it.gain }.thenBy { it.play.pattern.main })
        val danger = danger(view, seat)
        val urgent = danger <= 6 || view.handCounts[lead.seat] <= 6
        val calm = scored.firstOrNull { !it.play.pattern.isBomb }
        val bomb = scored.firstOrNull { it.play.pattern.isBomb }
        val turnsLeft = split.plays.size
        // Strong cards and wild cards are worth more kept than spent early on.
        val costly = calm != null && (calm.play.pattern.main >= GdValue.LEVEL || wilds(calm.play, view) > 0)
        val bar = if (!urgent && turnsLeft > 5 && costly) 2 else -4
        return when {
            calm != null && (calm.gain >= bar || (urgent && calm.gain >= -12)) -> calm.play
            bomb != null && (danger <= 6 || turnsLeft <= 3 || view.handCounts[lead.seat] <= 8) -> bomb.play
            else -> null
        }
    }

    /** The plays worth searching: [favourite] first, then passing when allowed, then the cheapest other plays. */
    private fun candidates(view: GdView, turn: GdTurn, favourite: GdAction): List<GdAction> {
        turn.plays.firstOrNull { it.cards.size == turn.hand.size }?.let { return listOf(GdAction.PlayCards(it.cards)) }
        val split = if (turn.toBeat == null) GdHandAnalysis.split(turn.hand, view.levelRank).plays.filter { turn.reading(it.cards) != null } else emptyList()
        val out = mutableListOf(favourite)
        if (turn.canPass && favourite != GdAction.Pass) out += GdAction.Pass
        val seen = HashSet<Any>()
        (favourite as? GdAction.PlayCards)?.let { f -> turn.reading(f.cards)?.let { seen += it } }
        for (play in split.sortedWith(cheap) + turn.plays.sortedWith(cheap)) {
            if (out.size >= MAX_CANDIDATES) break
            if (seen.add(play.pattern)) out += GdAction.PlayCards(play.cards)
        }
        return out
    }

    private companion object {
        const val SHORTLIST = 8
        const val MAX_CANDIDATES = 5
    }
}
