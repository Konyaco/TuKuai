package io.github.konyaco.tukuai.engine.paodekuai.bot

import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.doudizhu.DdzValue
import io.github.konyaco.tukuai.engine.doudizhu.ddzValue
import io.github.konyaco.tukuai.engine.paodekuai.PaoDeKuai
import io.github.konyaco.tukuai.engine.paodekuai.PdkAction
import io.github.konyaco.tukuai.engine.paodekuai.PdkPattern
import io.github.konyaco.tukuai.engine.paodekuai.PdkPatternType
import io.github.konyaco.tukuai.engine.paodekuai.PdkPlay
import io.github.konyaco.tukuai.engine.paodekuai.PdkTurn
import io.github.konyaco.tukuai.engine.paodekuai.PdkView
import kotlin.random.Random

/**
 * Computer player for Pao De Kuai, built like the Dou Dizhu one.
 *
 * - EASY plays the smallest thing that works.
 * - NORMAL plans its hand with [PdkHandAnalysis], keeps strong cards for later and
 *   stops a player who is about to run out.
 * - HARD tries its best few plays against many guesses of the other hands and keeps the
 *   one that does best, see [PdkSearch].
 *
 * It only picks from the plays [PdkTurn] allows, so it keeps to "must beat" and to
 * playing its highest single when the next player has one card left.
 */
class PdkBot(private val level: BotLevel, seed: Long, private val playouts: Int = PdkSearch.PLAYOUTS) : Bot<PdkView, PdkAction> {
    private val random = Random(seed)
    private val search by lazy { PdkSearch(random, playouts) }

    override fun act(view: PdkView): PdkAction {
        val seat = requireNotNull(view.seat) { "A bot needs a seat" }
        val turn = checkNotNull(view.turnRules()) { "It is not this bot's turn" }
        val choice = when {
            level == BotLevel.EASY -> easyPlay(turn)
            turn.toBeat == null -> lead(view, seat, turn)
            else -> follow(view, seat, turn)
        }
        val action = legal(turn, choice)
        if (level != BotLevel.HARD || playouts <= 0) return action
        return search.choose(view, seat, candidates(turn, action))
    }

    /** [choice] when it may be played, otherwise passing, or the first legal play when passing isn't allowed. */
    private fun legal(turn: PdkTurn, choice: PdkPlay?): PdkAction = when {
        choice != null && turn.reading(choice.cards) != null -> PdkAction.PlayCards(choice.cards)
        turn.canPass -> PdkAction.Pass
        else -> PdkAction.PlayCards(turn.plays.first().cards)
    }

    private val plain = compareBy<PdkPlay>({ it.pattern.isBomb }, { PdkHandAnalysis.value(it.pattern) }, { -it.cards.size })

    /** The plays worth searching: [favourite] first, then passing when allowed, then the cheapest other plays. */
    private fun candidates(turn: PdkTurn, favourite: PdkAction): List<PdkAction> {
        // Going out ends the game at once; nothing can beat that.
        turn.plays.firstOrNull { it.cards.size == turn.hand.size }?.let { return listOf(PdkAction.PlayCards(it.cards)) }
        val split = if (turn.toBeat == null) PdkHandAnalysis.split(turn.hand).plays.filter { turn.reading(it.cards) != null } else emptyList()
        val plays = split.sortedWith(plain) + turn.plays.sortedWith(plain)
        val out = mutableListOf(favourite)
        if (turn.canPass && favourite != PdkAction.Pass) out += PdkAction.Pass
        val seen = HashSet<PdkPattern>()
        (favourite as? PdkAction.PlayCards)?.let { f -> turn.reading(f.cards)?.let { seen += it } }
        for (play in plays) {
            if (out.size >= MAX_CANDIDATES) break
            if (seen.add(play.pattern)) out += PdkAction.PlayCards(play.cards)
        }
        return out
    }

    private fun easyPlay(turn: PdkTurn): PdkPlay? {
        val options = turn.plays
        if (turn.toBeat == null) {
            // Lead the lowest card value, taking the longest play that starts there.
            return options.filter { !it.pattern.isBomb }.minWithOrNull(
                compareBy<PdkPlay> { it.cards.minOf { c -> c.ddzValue } }.thenByDescending { it.cards.size },
            ) ?: options.first()
        }
        val calm = options.filter { !it.pattern.isBomb }
        if (turn.canPass && random.nextInt(4) == 0) return null
        return calm.minByOrNull { it.pattern.main } ?: options.firstOrNull()
    }

    private fun opponents(seat: Seat) = (0 until PaoDeKuai.SEATS).filter { it != seat }

    /** Fewest cards any other player holds. */
    private fun danger(view: PdkView, seat: Seat): Int = opponents(seat).minOf { view.handCounts[it] }

    private fun lead(view: PdkView, seat: Seat, turn: PdkTurn): PdkPlay {
        val options = turn.plays
        options.firstOrNull { it.cards.size == turn.hand.size }?.let { return it }

        val split = PdkHandAnalysis.split(turn.hand).plays.filter { turn.reading(it.cards) != null }
        if (split.isEmpty()) {
            // The plan doesn't fit the rules right now (the opening 3 of spades, or a single
            // that isn't the highest): take the legal play that leaves the best hand.
            return options.maxWith(compareBy<PdkPlay> { PdkHandAnalysis.score(turn.hand - it.cards.toSet()) }.thenBy { -it.pattern.main })
        }
        val danger = danger(view, seat)
        val candidates = split.filter { !it.pattern.isBomb }.ifEmpty { split }
        // Someone about to go out with one or two cards gets nothing of that size to beat.
        val safe = candidates.filter { !(danger <= 2 && it.cards.size == danger) }
        if (safe.isEmpty() && danger <= 2) return candidates.maxBy { it.pattern.main }
        return safe.ifEmpty { candidates }.sortedWith(
            compareBy<PdkPlay> { PdkHandAnalysis.value(it.pattern) - it.cards.size }.thenBy { it.pattern.main },
        ).first()
    }

    private fun follow(view: PdkView, seat: Seat, turn: PdkTurn): PdkPlay? {
        val options = turn.plays
        if (options.isEmpty()) return null
        options.firstOrNull { it.cards.size == turn.hand.size }?.let { return it }

        val before = PdkHandAnalysis.score(turn.hand)
        data class Scored(val play: PdkPlay, val gain: Int)
        val scored = options.map { Scored(it, PdkHandAnalysis.score(turn.hand - it.cards.toSet()) - before) }
            .sortedWith(compareByDescending<Scored> { it.gain }.thenBy { it.play.pattern.main })
        // Made to beat it: at least pick the play that hurts the hand least.
        if (!turn.canPass) return scored.first().play

        val lead = view.lead!!
        val danger = danger(view, seat)
        val urgent = danger <= 2 || view.handCounts[lead.seat] <= 3
        val calm = scored.firstOrNull { !it.play.pattern.isBomb }
        val bomb = scored.firstOrNull { it.play.pattern.isBomb }
        val turnsNow = PdkHandAnalysis.split(turn.hand).plays.size
        // Early on, a 2 or an ace is worth more kept than spent on an ordinary play.
        val bar = if (!urgent && turnsNow > 4 && calm != null && calm.play.pattern.main >= DdzValue.ACE) 3 else -2
        return when {
            urgent -> (calm ?: bomb)?.play
            calm != null && calm.gain >= bar -> calm.play
            bomb != null && (danger <= 5 || turnsNow <= 3) -> bomb.play
            else -> null
        }
    }

    private companion object {
        const val MAX_CANDIDATES = 8
    }
}
