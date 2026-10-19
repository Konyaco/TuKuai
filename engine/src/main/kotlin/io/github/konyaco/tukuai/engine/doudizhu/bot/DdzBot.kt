package io.github.konyaco.tukuai.engine.doudizhu.bot

import io.github.konyaco.tukuai.engine.card.Decks
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.doudizhu.BiddingMode
import io.github.konyaco.tukuai.engine.doudizhu.DdzAction
import io.github.konyaco.tukuai.engine.doudizhu.DdzCards
import io.github.konyaco.tukuai.engine.doudizhu.DdzPhase
import io.github.konyaco.tukuai.engine.doudizhu.DdzValue
import io.github.konyaco.tukuai.engine.doudizhu.DdzView
import io.github.konyaco.tukuai.engine.doudizhu.DouDizhu
import io.github.konyaco.tukuai.engine.doudizhu.Pattern
import io.github.konyaco.tukuai.engine.doudizhu.PatternType
import io.github.konyaco.tukuai.engine.doudizhu.Patterns
import io.github.konyaco.tukuai.engine.doudizhu.Play
import io.github.konyaco.tukuai.engine.doudizhu.ddzValue
import kotlin.random.Random

/**
 * Computer player for Dou Dizhu.
 *
 * - EASY plays the smallest card that works and bids at random.
 * - NORMAL plans its hand with [HandAnalysis], keeps strong cards for later and
 *   stops an opponent who is about to run out.
 * - HARD also counts the cards that have been played, so it knows which of its
 *   plays nobody can beat, and helps its teammate finish. Then it tries its best few
 *   plays against many guesses of the other hands and keeps the one that does best,
 *   see [PlayoutSearch].
 *
 * It only reads the [DdzView] for its own seat, so it never sees hidden cards.
 */
class DdzBot(private val level: BotLevel, seed: Long, private val playouts: Int = PlayoutSearch.PLAYOUTS) : Bot<DdzView, DdzAction> {
    private val random = Random(seed)
    private val search by lazy { PlayoutSearch(random, playouts) }

    override fun act(view: DdzView): DdzAction {
        val seat = requireNotNull(view.seat) { "A bot needs a seat" }
        return when (view.phase) {
            DdzPhase.BIDDING -> bid(view)
            DdzPhase.DOUBLING -> DdzAction.Double(strength(view.hand) + noise() >= if (view.isLandlord(seat)) 10 else 8)
            DdzPhase.PLAYING -> play(view, seat)
            DdzPhase.FINISHED -> error("The game is over")
        }
    }

    private fun noise(): Int = when (level) {
        BotLevel.EASY -> random.nextInt(-4, 5)
        BotLevel.NORMAL -> random.nextInt(-1, 2)
        BotLevel.HARD -> 0
    }

    // ---- Bidding -------------------------------------------------------------------------------

    /** Rough strength of a hand for bidding: big cards and bombs, and how few turns it needs. */
    fun strength(hand: List<PlayingCard>): Int {
        val counts = DdzCards.counts(hand)
        var s = 0
        if (counts[DdzValue.RED_JOKER] == 1 && counts[DdzValue.BLACK_JOKER] == 1) {
            s += 8
        } else {
            s += 4 * counts[DdzValue.RED_JOKER] + 3 * counts[DdzValue.BLACK_JOKER]
        }
        s += 2 * counts[DdzValue.TWO]
        s += 6 * DdzValue.ALL.count { it <= DdzValue.TWO && counts[it] == 4 }
        s += counts[DdzValue.ACE]
        val turns = HandAnalysis.split(hand).plays.size
        s += (7 - turns).coerceIn(-3, 3)
        return s
    }

    private fun bid(view: DdzView): DdzAction {
        val s = strength(view.hand) + noise()
        return when (view.rules.bidding) {
            BiddingMode.SCORE -> {
                val wanted = when {
                    s >= 11 -> 3
                    s >= 9 -> 2
                    s >= 7 -> 1
                    else -> 0
                }
                DdzAction.Bid(if (wanted in view.allowedBids) wanted else 0)
            }
            BiddingMode.ROB -> DdzAction.Call(if (view.robbing) s >= 11 else s >= 8)
        }
    }

    // ---- Playing -------------------------------------------------------------------------------

    private fun play(view: DdzView, seat: Seat): DdzAction {
        val toBeat = view.toBeat
        val choice = when {
            level == BotLevel.EASY -> easyPlay(view, toBeat)
            toBeat == null -> lead(view, seat)
            else -> follow(view, seat, toBeat)
        }
        val action = if (choice == null) DdzAction.Pass else DdzAction.PlayCards(choice.cards)
        if (level != BotLevel.HARD || playouts <= 0) return action
        return search.choose(view, seat, candidates(view, action))
    }

    /** The plays worth searching: [favourite] first, then passing, then the cheapest other plays. */
    private fun candidates(view: DdzView, favourite: DdzAction): List<DdzAction> {
        val toBeat = view.toBeat
        val options = Patterns.options(view.hand, toBeat, view.rules)
        // Going out ends the game at once; nothing can beat that.
        options.firstOrNull { it.cards.size == view.hand.size }?.let { return listOf(DdzAction.PlayCards(it.cards)) }
        val plain = compareBy<Play>({ it.pattern.isBomb }, { HandAnalysis.value(it.pattern) }, { -it.cards.size })
        val plays = if (toBeat == null) {
            HandAnalysis.split(view.hand).plays.sortedWith(plain) + options.sortedWith(plain)
        } else {
            options.sortedWith(plain)
        }
        val out = mutableListOf(favourite)
        if (toBeat != null && favourite != DdzAction.Pass) out += DdzAction.Pass
        val seen = HashSet<Pattern>()
        (out[0] as? DdzAction.PlayCards)?.let { f -> Patterns.readingFor(f.cards, toBeat, view.rules)?.let { seen += it } }
        for (play in plays) {
            if (out.size >= MAX_CANDIDATES) break
            if (seen.add(play.pattern)) out += DdzAction.PlayCards(play.cards)
        }
        return out
    }

    private companion object {
        const val MAX_CANDIDATES = 8
    }

    private fun easyPlay(view: DdzView, toBeat: Pattern?): Play? {
        val options = Patterns.options(view.hand, toBeat, view.rules)
        if (toBeat == null) {
            // Lead the lowest card value, taking the longest play that starts there.
            return options.filter { !it.pattern.isBomb }.minWithOrNull(
                compareBy<Play> { it.cards.minOf { c -> c.ddzValue } }.thenByDescending { it.cards.size },
            ) ?: options.first()
        }
        val plain = options.filter { !it.pattern.isBomb }
        if (plain.isNotEmpty() && random.nextInt(5) > 0) return plain.minBy { it.pattern.main }
        return if (random.nextInt(4) == 0) options.firstOrNull() else null
    }

    private fun enemies(view: DdzView, seat: Seat): List<Seat> =
        (0 until DouDizhu.SEATS).filter { it != seat && !view.isTeammate(it, seat) }

    private fun teammate(view: DdzView, seat: Seat): Seat? =
        (0 until DouDizhu.SEATS).firstOrNull { it != seat && view.isTeammate(it, seat) }

    /** Fewest cards any opponent holds. */
    private fun enemyMin(view: DdzView, seat: Seat): Int = enemies(view, seat).minOf { view.handCounts[it] }

    /** Cards no one has shown yet, from this seat's point of view. */
    private fun unseen(view: DdzView): IntArray {
        val counts = DdzCards.counts(Decks.STANDARD_54)
        (view.hand + view.playedCards).forEach { counts[it.ddzValue]-- }
        return counts
    }

    /** True when, as far as this seat can tell, nobody else can beat [play] (ignoring bombs they might hold). */
    private fun isControl(play: Play, unseen: IntArray): Boolean {
        val p = play.pattern
        return when (p.type) {
            PatternType.ROCKET -> true
            PatternType.BOMB -> DdzValue.ALL.none { it > p.main && unseen[it] == 4 } &&
                !(unseen[DdzValue.BLACK_JOKER] == 1 && unseen[DdzValue.RED_JOKER] == 1)
            PatternType.SINGLE -> DdzValue.ALL.none { it > p.main && unseen[it] >= 1 }
            PatternType.PAIR -> DdzValue.ALL.none { it > p.main && it < DdzValue.BLACK_JOKER && unseen[it] >= 2 }
            PatternType.TRIO, PatternType.TRIO_SINGLE, PatternType.TRIO_PAIR ->
                DdzValue.ALL.none { it > p.main && it < DdzValue.BLACK_JOKER && unseen[it] >= 3 }
            else -> p.main + p.length - 1 >= DdzValue.HIGHEST_IN_SEQUENCE
        }
    }

    private fun lead(view: DdzView, seat: Seat): Play {
        val plays = HandAnalysis.split(view.hand).plays
        if (plays.size == 1) return plays[0]

        val unseen = unseen(view)
        val counting = level == BotLevel.HARD
        // With two plays left and one of them unbeatable, play that one and then win with the other.
        if (plays.size == 2 && counting) {
            plays.firstOrNull { isControl(it, unseen) }?.let { return it }
        }

        val danger = enemyMin(view, seat)
        val mate = teammate(view, seat)
        // A teammate about to win gets the lowest single, which they can surely beat.
        if (mate != null && view.handCounts[mate] == 1 && (mate == (seat + 1) % DouDizhu.SEATS || counting)) {
            val single = view.hand.minBy { it.ddzValue }
            return Play(listOf(single), Pattern(PatternType.SINGLE, single.ddzValue))
        }

        val candidates = plays.filter { !it.pattern.isBomb }.ifEmpty { plays }
        val safe = candidates.filter { !(danger <= 2 && it.cards.size == danger && it.cards.size <= 2) }
        val pool = safe.ifEmpty { candidates }
        if (safe.isEmpty() && danger <= 2) {
            // Every option is the size the opponent wants: give them the biggest one.
            return pool.maxBy { it.pattern.main }
        }
        // Lead the weakest play; long plays first because they shed more cards for one turn.
        val ordered = pool.sortedWith(
            compareBy<Play> { counting && isControl(it, unseen) }
                .thenBy { HandAnalysis.value(it.pattern) - it.cards.size }
                .thenBy { it.pattern.main },
        )
        return ordered.first()
    }

    private fun follow(view: DdzView, seat: Seat, toBeat: Pattern): Play? {
        val options = Patterns.options(view.hand, toBeat, view.rules)
        if (options.isEmpty()) return null
        options.firstOrNull { it.cards.size == view.hand.size }?.let { return it }

        val lead = view.lead!!
        val fromTeammate = view.isTeammate(lead.seat, seat)
        val danger = enemyMin(view, seat)
        val before = HandAnalysis.score(view.hand)

        data class Scored(val play: Play, val gain: Int, val turnsAfter: Int)
        val scored = options.map { option ->
            val after = HandAnalysis.split(view.hand - option.cards.toSet())
            Scored(option, after.score - before, after.plays.size)
        }

        if (fromTeammate) {
            // Do not fight a teammate unless that play finishes us next turn or costs nothing.
            val winning = scored.filter { !it.play.pattern.isBomb && it.turnsAfter <= 1 }
            if (winning.isNotEmpty()) return winning.maxBy { it.gain }.play
            if (view.handCounts[lead.seat] <= 2) return null
            return scored.filter { !it.play.pattern.isBomb && it.gain >= HandAnalysis.TURN_COST && it.play.pattern.main < DdzValue.ACE }
                .maxByOrNull { it.gain }?.play
        }

        val urgent = danger <= 2 || view.handCounts[lead.seat] <= 3
        val plain = scored.filter { !it.play.pattern.isBomb }.maxByOrNull { it.gain }
        val bomb = scored.filter { it.play.pattern.isBomb }.maxByOrNull { it.gain }
        val turnsNow = HandAnalysis.split(view.hand).plays.size
        // Early on, a 2 or a joker is worth more kept than spent on an ordinary play.
        val bar = if (!urgent && turnsNow > 4 && plain != null && plain.play.pattern.main >= DdzValue.TWO) 3 else -2
        return when {
            urgent -> (plain ?: bomb)?.play
            plain != null && plain.gain >= bar -> plain.play
            bomb != null && (danger <= 4 || turnsNow <= 3) -> bomb.play
            else -> null
        }
    }
}
