package io.github.konyaco.tukuai.engine.mahjong.riichi

import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.GameDefinition
import io.github.konyaco.tukuai.engine.core.GameResult
import io.github.konyaco.tukuai.engine.core.GuideSection
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.RulesPreset
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.mahjong.Claims
import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit
import io.github.konyaco.tukuai.engine.mahjong.Tiles
import io.github.konyaco.tukuai.engine.mahjong.Wall
import io.github.konyaco.tukuai.engine.mahjong.riichi.bot.RcBot
import kotlin.random.Random

/** What a player may answer to a discard. */
data class RcOptions(val win: Boolean = false, val kong: Boolean = false, val pung: Boolean = false, val chows: List<Tile> = emptyList()) {
    val any: Boolean get() = win || kong || pung || chows.isNotEmpty()

    companion object {
        val NONE = RcOptions()
    }
}

/**
 * Riichi mahjong (立直麻雀), the Japanese game. Four players, 136 tiles, a dead wall of 14
 * with the dora indicators on it. A hand needs a yaku to win; riichi, declared with a closed
 * ready hand, is one. A player may not win on a discard while furiten. The dealer keeps the
 * deal while winning or ready at the end, and the game is the east round, or east and south.
 * Scores follow the usual table of han and fu, with honba and riichi sticks.
 */
object Riichi : GameDefinition<RcRules, RcState, RcAction, RcView> {
    override val id = "riichi"
    override val rulesCodec: WireCodec<RcRules> = RcRules.CODEC
    override val actionCodec: WireCodec<RcAction> = RcCodecs.ACTION
    override val viewCodec: WireCodec<RcView> = RcCodecs.VIEW
    override val presets: List<RulesPreset<RcRules>> = RcRules.PRESETS

    const val SEATS = 4
    const val HAND = 13
    const val DEAD_WALL = 14
    const val STICK = 1000

    override fun seatCount(rules: RcRules) = SEATS

    /** The [i]th dora indicator: on the dead wall, every other tile from the fifth from the back. */
    fun doraIndicator(wall: Wall, i: Int): Tile = wall.tiles[wall.tiles.size - 5 - 2 * i]

    fun uraIndicator(wall: Wall, i: Int): Tile = wall.tiles[wall.tiles.size - 6 - 2 * i]

    override fun start(rules: RcRules, seed: Long): RcState {
        val firstDealer = Random(seed).nextInt(SEATS)
        val empty = RcState(
            rules = rules, seed = seed, firstDealer = firstDealer, round = 0, kyoku = 0, honba = 0, sticks = 0,
            points = List(SEATS) { rules.startPoints }, dealt = 0, wall = Wall(emptyList()), kongs = emptyList(),
            hands = List(SEATS) { emptyList() }, melds = List(SEATS) { emptyList() }, ponds = List(SEATS) { emptyList() },
            discarded = List(SEATS) { emptyList() }, sideways = List(SEATS) { -1 }, sidewaysNext = List(SEATS) { false },
            riichi = List(SEATS) { false }, doubleRiichi = List(SEATS) { false }, ippatsu = List(SEATS) { false },
            missed = List(SEATS) { false }, riichiMissed = List(SEATS) { false }, firstGo = List(SEATS) { true },
            interrupted = false, taken = List(SEATS) { false }, phase = RcPhase.PLAY, turn = firstDealer, drawn = null,
            rinshan = false, forbidden = emptyList(), declaring = false, claimTile = null, pending = emptyList(),
            answers = emptyMap(), result = null, history = emptyList(), ready = List(SEATS) { false }, log = emptyList(),
        )
        return deal(empty)
    }

    /** Deals the hand [state] is set up for: 13 tiles each, and the dealer draws. */
    private fun deal(state: RcState): RcState {
        val random = Random(state.seed * 1_000_003L + state.dealt * 7919L + 29)
        var wall = Wall(Tiles.set(TileSuit.HAND, state.rules.reds).shuffled(random))
        val dealer = state.dealer
        val hands = MutableList(SEATS) { emptyList<Tile>() }
        for (i in 0 until SEATS) {
            val (tiles, rest) = wall.deal(HAND)
            hands[(dealer + i) % SEATS] = tiles.sorted()
            wall = rest
        }
        val s = state.copy(
            dealt = state.dealt + 1, wall = wall, kongs = emptyList(), hands = hands,
            melds = List(SEATS) { emptyList() }, ponds = List(SEATS) { emptyList() }, discarded = List(SEATS) { emptyList() },
            sideways = List(SEATS) { -1 }, sidewaysNext = List(SEATS) { false }, riichi = List(SEATS) { false },
            doubleRiichi = List(SEATS) { false }, ippatsu = List(SEATS) { false }, missed = List(SEATS) { false },
            riichiMissed = List(SEATS) { false }, firstGo = List(SEATS) { true }, interrupted = false, taken = List(SEATS) { false },
            phase = RcPhase.PLAY, turn = dealer, drawn = null, rinshan = false, forbidden = emptyList(), declaring = false,
            claimTile = null, pending = emptyList(), answers = emptyMap(), result = null, ready = List(SEATS) { false },
            log = listOf(RcEvent(dealer, RcEventKind.DEAL), RcEvent(dealer, RcEventKind.DORA, doraIndicator(wall, 0))),
        )
        return draw(s, dealer, rinshan = false)
    }

    override fun toAct(state: RcState): Seat? = actors(state).firstOrNull()

    override fun actors(state: RcState): List<Seat> = when (state.phase) {
        RcPhase.PLAY -> listOf(state.turn)
        RcPhase.CLAIM, RcPhase.ROB -> state.pending
        RcPhase.HAND_OVER -> (0 until SEATS).map { (state.dealer + it) % SEATS }.filter { !state.ready[it] }
        RcPhase.OVER -> emptyList()
    }

    override fun apply(state: RcState, seat: Seat, action: RcAction): RcState {
        if (seat !in actors(state)) throw IllegalActionException(if (state.finished) "The game is over" else "It is not your turn")
        return when (state.phase) {
            RcPhase.PLAY -> when (action) {
                is RcAction.Discard -> discard(state, seat, action.tile, riichi = false)
                is RcAction.Riichi -> discard(state, seat, action.tile, riichi = true)
                is RcAction.Kong -> kong(state, seat, action.tile)
                RcAction.Win -> tsumo(state, seat)
                RcAction.Abort -> abortNine(state, seat)
                else -> throw IllegalActionException("Discard, kong or win")
            }
            RcPhase.CLAIM, RcPhase.ROB -> answer(state, seat, action)
            RcPhase.HAND_OVER -> if (action == RcAction.Next) next(state, seat) else throw IllegalActionException("The hand is over")
            RcPhase.OVER -> throw IllegalActionException("The game is over")
        }
    }

    // ---- Reading the hand ----------------------------------------------------------------------

    /** Whether [seat] is furiten: one of the tiles they wait on is among their own discards, or they let a winning tile go. */
    fun furiten(state: RcState, seat: Seat): Boolean {
        if (state.missed[seat] || state.riichiMissed[seat]) return true
        val waits = RcScoring.waits(state.hands[seat], state.melds[seat])
        return waits.any { w -> state.discarded[seat].any { it.id == w.id } }
    }

    /** [seat]'s hand won on [tile] as the scorer reads it. */
    fun winFor(state: RcState, seat: Seat, tile: Tile, tsumo: Boolean, chankan: Boolean = false): RcWin {
        val concealed = if (tsumo) state.hands[seat] else state.hands[seat] + tile
        val first = state.firstGo[seat] && !state.interrupted
        return RcWin(
            concealed = concealed, melds = state.melds[seat], tile = tile, tsumo = tsumo,
            seatWind = state.seatWind(seat), roundWind = state.round,
            riichi = state.riichi[seat], doubleRiichi = state.doubleRiichi[seat], ippatsu = state.ippatsu[seat],
            haitei = tsumo && state.liveLeft == 0 && !state.rinshan, houtei = !tsumo && !chankan && state.liveLeft == 0,
            rinshan = tsumo && state.rinshan, chankan = chankan,
            tenhou = tsumo && first && seat == state.dealer, chiihou = tsumo && first && seat != state.dealer,
            doraIndicators = state.doraIndicators, uraIndicators = state.uraIndicators, kuitan = state.rules.kuitan,
        )
    }

    /** The score of [win] when it may win: a winning hand with a yaku. */
    fun winning(win: RcWin): RcScore? = RcScoring.score(win)?.takeIf { it.yaku.isNotEmpty() }

    fun tsumoScore(state: RcState, seat: Seat): RcScore? {
        if (state.phase != RcPhase.PLAY || state.turn != seat) return null
        val tile = state.drawn ?: return null
        return winning(winFor(state, seat, tile, tsumo = true))
    }

    /** What [seat] may answer to [tile], discarded by [from]. */
    fun claimOptions(state: RcState, seat: Seat, tile: Tile, from: Seat): RcOptions {
        if (seat == from) return RcOptions.NONE
        val win = !furiten(state, seat) && winning(winFor(state, seat, tile, tsumo = false)) != null
        if (state.liveLeft == 0 || state.riichi[seat]) return RcOptions(win = win)
        val hand = state.hands[seat]
        val counts = Tiles.counts(hand)
        val kong = counts[tile.id] >= 3 && state.kongs.size < 4
        val pung = counts[tile.id] >= 2 && hand.any { it.id != tile.id }
        val chows = if (seat == (from + 1) % SEATS) chowStarts(hand, tile) else emptyList()
        return RcOptions(win = win, kong = kong, pung = pung, chows = chows)
    }

    /** The runs [hand] can make with [tile] that leave something it may discard afterwards. */
    fun chowStarts(hand: List<Tile>, tile: Tile): List<Tile> {
        if (!tile.suit.numbered) return emptyList()
        val counts = Tiles.counts(hand)
        return (tile.rank - 2..tile.rank).filter { it >= 1 && it + 2 <= 9 }.map { Tile.of(tile.suit, it) }.filter { start ->
            val others = (0..2).map { Tile.of(start.suit, start.rank + it) }.filter { it.id != tile.id }
            if (!others.all { counts[it.id] > 0 }) return@filter false
            var rest = hand
            for (t in others) rest = Tiles.minusKind(rest, t, 1)
            val banned = swapBan(start, tile)
            rest.any { r -> banned.none { it.id == r.id } }
        }
    }

    /** The tiles a player may not discard right after a run from [start] made with [tile]: that tile, and the one at the other end of the line. */
    fun swapBan(start: Tile, tile: Tile): List<Tile> {
        val out = arrayListOf(tile.plain)
        if (tile.rank == start.rank && start.rank + 3 <= 9) out += Tile.of(start.suit, start.rank + 3)
        if (tile.rank == start.rank + 2 && start.rank - 1 >= 1) out += Tile.of(start.suit, start.rank - 1)
        return out
    }

    /** The kongs [seat] may make now. After riichi only a closed kong of the tile just drawn that leaves the waits as they were. */
    fun kongs(state: RcState, seat: Seat): List<Tile> {
        if (state.phase != RcPhase.PLAY || state.turn != seat || state.liveLeft == 0 || state.kongs.size >= 4 || state.drawn == null) return emptyList()
        val hand = state.hands[seat]
        val counts = Tiles.counts(hand)
        val closed = hand.distinct().filter { counts[it.id] == 4 }.map { it.plain }.distinct()
        if (state.riichi[seat]) {
            val drawn = state.drawn
            return closed.filter { it.id == drawn.id }.filter { tile ->
                val before = RcScoring.waits(Tiles.minus(hand, listOf(drawn)), state.melds[seat]).map { it.id }.toSet()
                val after = RcScoring.waits(hand.filter { it.id != tile.id }, state.melds[seat] + Meld(MeldKind.CONCEALED_KONG, tile)).map { it.id }.toSet()
                before.isNotEmpty() && before == after
            }
        }
        val added = state.melds[seat].filter { it.kind == MeldKind.PUNG && counts[it.tile.id] > 0 }.map { it.tile }
        return (closed + added).distinct().sorted()
    }

    /** The tiles [seat] may discard declaring riichi: those that leave a closed hand ready. */
    fun riichiTiles(state: RcState, seat: Seat): List<Tile> {
        if (state.phase != RcPhase.PLAY || state.turn != seat || state.riichi[seat] || state.drawn == null) return emptyList()
        if (state.melds[seat].any { it.kind != MeldKind.CONCEALED_KONG }) return emptyList()
        if (state.points[seat] < STICK || state.liveLeft < 4) return emptyList()
        val hand = state.hands[seat]
        return hand.distinct().filter { RcScoring.waits(Tiles.minus(hand, listOf(it)), state.melds[seat]).isNotEmpty() }
    }

    /** Whether [seat] may call the hand off: their first draw, nobody has called, and nine kinds of terminals and honours. */
    fun canAbort(state: RcState, seat: Seat): Boolean =
        state.phase == RcPhase.PLAY && state.turn == seat && state.firstGo[seat] && !state.interrupted && state.drawn != null &&
            state.hands[seat].filter { it.isTerminalOrHonour }.map { it.id }.distinct().size >= 9

    /** The tiles [seat] may discard now. */
    fun discardable(state: RcState, seat: Seat): List<Tile> {
        if (state.phase != RcPhase.PLAY || state.turn != seat) return emptyList()
        val hand = state.hands[seat]
        if (state.riichi[seat]) return listOfNotNull(state.drawn)
        return hand.distinct().filter { t -> state.forbidden.none { it.id == t.id } }
    }

    // ---- A turn --------------------------------------------------------------------------------

    private fun discard(state: RcState, seat: Seat, tile: Tile, riichi: Boolean): RcState {
        if (tile !in state.hands[seat]) throw IllegalActionException("You do not hold $tile")
        if (riichi) {
            if (tile !in riichiTiles(state, seat)) throw IllegalActionException("You cannot declare riichi with $tile")
        } else if (tile !in discardable(state, seat)) {
            throw IllegalActionException(if (state.riichi[seat]) "After riichi, discard the tile you drew" else "You may not discard $tile now")
        }
        val pond = state.ponds[seat] + tile
        val sideways = if (riichi || state.sidewaysNext[seat]) state.sideways.replace(seat, pond.size - 1) else state.sideways
        var log = state.log
        if (riichi) log = log + RcEvent(seat, RcEventKind.RIICHI)
        val after = state.copy(
            hands = state.hands.replace(seat, Tiles.minus(state.hands[seat], listOf(tile))),
            ponds = state.ponds.replace(seat, pond), discarded = state.discarded.replace(seat, state.discarded[seat] + tile),
            sideways = sideways, sidewaysNext = state.sidewaysNext.replace(seat, false),
            missed = state.missed.replace(seat, false),
            // A riichi player's one-go-round chance ends with their next discard.
            ippatsu = if (state.riichi[seat]) state.ippatsu.replace(seat, false) else state.ippatsu,
            firstGo = state.firstGo.replace(seat, false), drawn = null, rinshan = false, forbidden = emptyList(),
            declaring = riichi, log = log + RcEvent(seat, RcEventKind.DISCARD, tile),
        )
        val claimers = Claims.after(seat, SEATS).filter { claimOptions(after, it, tile, seat).any }
        if (claimers.isEmpty()) return discardStands(after.copy(claimTile = tile), seat)
        return after.copy(phase = RcPhase.CLAIM, turn = seat, claimTile = tile, pending = claimers, answers = emptyMap())
    }

    /** A riichi declaration goes through once its discard is not won on: the stick goes down. */
    private fun settleRiichi(state: RcState, seat: Seat): RcState {
        if (!state.declaring) return state
        val double = state.discarded[seat].size == 1 && !state.interrupted
        return state.copy(
            declaring = false,
            riichi = state.riichi.replace(seat, true), doubleRiichi = state.doubleRiichi.replace(seat, double),
            ippatsu = state.ippatsu.replace(seat, true),
            points = state.points.replace(seat, state.points[seat] - STICK), sticks = state.sticks + 1,
        )
    }

    /** Nobody took [seat]'s discard: riichi goes through, the hand may be called off, or the next player draws. */
    private fun discardStands(state: RcState, seat: Seat): RcState {
        val tile = state.claimTile!!
        var s = settleRiichi(state, seat).copy(claimTile = null, pending = emptyList(), answers = emptyMap())
        if (s.riichi.all { it }) return abort(s, RcAbort.FOUR_RIICHI)
        val discards = s.log.filter { it.kind == RcEventKind.DISCARD }
        if (!s.interrupted && discards.size == 4 && discards.all { it.tile?.suit == TileSuit.WINDS && it.tile.id == tile.id }) return abort(s, RcAbort.FOUR_WINDS)
        if (s.kongs.size == 4 && s.kongs.distinct().size > 1) return abort(s, RcAbort.FOUR_KONGS)
        if (s.liveLeft == 0) return exhausted(s)
        s = s.copy(phase = RcPhase.PLAY)
        return draw(s, (seat + 1) % SEATS, rinshan = false)
    }

    private fun draw(state: RcState, seat: Seat, rinshan: Boolean): RcState {
        val (tile, wall) = if (rinshan) state.wall.drawBack() else state.wall.draw()
        return state.copy(
            wall = wall, hands = state.hands.replace(seat, (state.hands[seat] + tile).sorted()),
            phase = RcPhase.PLAY, turn = seat, drawn = tile, rinshan = rinshan, pending = emptyList(), answers = emptyMap(),
            log = state.log + RcEvent(seat, RcEventKind.DRAW, tile, if (rinshan) 1 else -1),
        )
    }

    private fun kong(state: RcState, seat: Seat, tile: Tile): RcState {
        if (tile.plain !in kongs(state, seat)) throw IllegalActionException("You cannot kong $tile")
        val hand = state.hands[seat]
        val counts = Tiles.counts(hand)
        if (counts[tile.id] == 4) {
            val reds = hand.count { it.id == tile.id && it.red }
            val after = state.copy(
                hands = state.hands.replace(seat, hand.filter { it.id != tile.id }),
                melds = state.melds.replace(seat, state.melds[seat] + Meld(MeldKind.CONCEALED_KONG, tile.plain, reds = reds)),
                drawn = null, interrupted = true, ippatsu = List(SEATS) { false },
                log = state.log + RcEvent(seat, RcEventKind.KONG, tile.plain, meld = MeldKind.CONCEALED_KONG),
            )
            return kongDone(after, seat)
        }
        val added = hand.first { it.id == tile.id }
        val after = state.copy(hands = state.hands.replace(seat, Tiles.minus(hand, listOf(added))), drawn = null)
        val robbers = Claims.after(seat, SEATS).filter { s ->
            !furiten(after, s) && winning(winFor(after, s, added, tsumo = false, chankan = true)) != null
        }
        if (robbers.isEmpty()) return addKong(after, seat, added)
        return after.copy(phase = RcPhase.ROB, turn = seat, claimTile = added, pending = robbers, answers = emptyMap())
    }

    private fun addKong(state: RcState, seat: Seat, tile: Tile): RcState {
        val melds = state.melds[seat].map {
            if (it.kind == MeldKind.PUNG && it.tile.id == tile.id) it.copy(kind = MeldKind.ADDED_KONG, reds = it.reds + if (tile.red) 1 else 0) else it
        }
        val after = state.copy(
            melds = state.melds.replace(seat, melds), claimTile = null, pending = emptyList(), answers = emptyMap(),
            interrupted = true, ippatsu = List(SEATS) { false },
            log = state.log + RcEvent(seat, RcEventKind.KONG, tile.plain, meld = MeldKind.ADDED_KONG),
        )
        return kongDone(after, seat)
    }

    /** After a kong: a new dora indicator is turned up and the player draws from the dead wall. */
    private fun kongDone(state: RcState, seat: Seat): RcState {
        val kongs = state.kongs + seat
        val indicator = doraIndicator(state.wall, kongs.size)
        val s = state.copy(kongs = kongs, log = state.log + RcEvent(seat, RcEventKind.DORA, indicator))
        return draw(s, seat, rinshan = true)
    }

    private fun answer(state: RcState, seat: Seat, action: RcAction): RcState {
        val tile = state.claimTile!!
        val rob = state.phase == RcPhase.ROB
        val options = if (rob) RcOptions(win = true) else claimOptions(state, seat, tile, state.turn)
        val ok = when (action) {
            RcAction.Pass -> true
            RcAction.Win -> options.win
            RcAction.Pung -> options.pung
            is RcAction.Kong -> options.kong && action.tile.id == tile.id
            is RcAction.Chow -> action.start in options.chows
            else -> throw IllegalActionException("Answer the discard: claim it or pass")
        }
        if (!ok) throw IllegalActionException("You cannot do that with $tile")
        var after = state.copy(answers = state.answers + (seat to action), pending = state.pending - seat)
        // Letting a winning tile go makes the player furiten until their next discard, or for good after riichi.
        if (action == RcAction.Pass && options.win) {
            after = if (state.riichi[seat]) after.copy(riichiMissed = after.riichiMissed.replace(seat, true)) else after.copy(missed = after.missed.replace(seat, true))
        }
        if (after.pending.isEmpty() || settled(after)) return if (rob) resolveRob(after) else resolveClaim(after)
        return after
    }

    private fun precedence(action: RcAction?): Int = when (action) {
        RcAction.Win -> 3
        RcAction.Pung, is RcAction.Kong -> 2
        is RcAction.Chow -> 1
        else -> 0
    }

    /** Whether nobody still to answer could change what happens. */
    private fun settled(state: RcState): Boolean {
        val best = state.answers.values.maxOfOrNull { precedence(it) } ?: 0
        if (best == 0) return false
        val tile = state.claimTile!!
        return state.pending.none { s ->
            val most = if (state.phase == RcPhase.ROB) 3 else {
                val o = claimOptions(state, s, tile, state.turn)
                when {
                    o.win -> 3
                    o.pung || o.kong -> 2
                    o.chows.isNotEmpty() -> 1
                    else -> 0
                }
            }
            // Several may win on one discard, so a possible win is always waited for.
            most > best || (most == 3)
        }
    }

    private fun resolveClaim(state: RcState): RcState {
        val tile = state.claimTile!!
        val from = state.turn
        val order = Claims.after(from, SEATS)
        val winners = order.filter { state.answers[it] == RcAction.Win }
        val cleared = state.copy(pending = emptyList(), answers = emptyMap())
        if (winners.isNotEmpty()) return ron(cleared, winners, tile, from, chankan = false)
        val claim = order.mapNotNull { s -> state.answers[s]?.let { s to it } }.filter { precedence(it.second) > 0 }.maxByOrNull { precedence(it.second) }
            ?: return discardStands(cleared, from)
        // The claim takes the tile, so a riichi declared with it goes through first.
        var s = settleRiichi(cleared, from)
        val (seat, action) = claim
        val pond = s.ponds[from]
        val wasSideways = s.sideways[from] == pond.size - 1
        s = s.copy(
            ponds = s.ponds.replace(from, pond.dropLast(1)),
            sideways = if (wasSideways) s.sideways.replace(from, -1) else s.sideways,
            sidewaysNext = if (wasSideways) s.sidewaysNext.replace(from, true) else s.sidewaysNext,
            taken = s.taken.replace(from, true), interrupted = true, ippatsu = List(SEATS) { false }, claimTile = null,
        )
        if (s.riichi.all { it }) return abort(s, RcAbort.FOUR_RIICHI)
        val hand = s.hands[seat]
        return when (action) {
            is RcAction.Kong -> {
                val used = hand.filter { it.id == tile.id }
                val reds = used.count { it.red } + if (tile.red) 1 else 0
                val after = s.copy(
                    hands = s.hands.replace(seat, hand.filter { it.id != tile.id }),
                    melds = s.melds.replace(seat, s.melds[seat] + Meld(MeldKind.EXPOSED_KONG, tile.plain, from, reds, tile)),
                    log = s.log + RcEvent(seat, RcEventKind.KONG, tile.plain, from, MeldKind.EXPOSED_KONG),
                )
                kongDone(after, seat)
            }
            RcAction.Pung -> {
                val rest = Tiles.minusKind(hand, tile, 2, redFirst = true)
                val used = Tiles.minus(hand, rest)
                val reds = used.count { it.red } + if (tile.red) 1 else 0
                s.copy(
                    hands = s.hands.replace(seat, rest),
                    melds = s.melds.replace(seat, s.melds[seat] + Meld(MeldKind.PUNG, tile.plain, from, reds, tile)),
                    phase = RcPhase.PLAY, turn = seat, drawn = null, rinshan = false, forbidden = listOf(tile.plain),
                    log = s.log + RcEvent(seat, RcEventKind.PUNG, tile, from),
                )
            }
            is RcAction.Chow -> {
                val start = action.start
                var rest = hand
                var reds = if (tile.red) 1 else 0
                for (d in 0..2) {
                    val t = Tile.of(start.suit, start.rank + d)
                    if (t.id == tile.id) continue
                    val before = rest
                    rest = Tiles.minusKind(rest, t, 1, redFirst = true)
                    reds += Tiles.minus(before, rest).count { it.red }
                }
                s.copy(
                    hands = s.hands.replace(seat, rest),
                    melds = s.melds.replace(seat, s.melds[seat] + Meld(MeldKind.CHOW, start, from, reds, tile)),
                    phase = RcPhase.PLAY, turn = seat, drawn = null, rinshan = false, forbidden = swapBan(start, tile),
                    log = s.log + RcEvent(seat, RcEventKind.CHOW, start, from),
                )
            }
            else -> throw IllegalStateException("Unknown claim $action")
        }
    }

    private fun resolveRob(state: RcState): RcState {
        val tile = state.claimTile!!
        val konger = state.turn
        val winners = Claims.after(konger, SEATS).filter { state.answers[it] == RcAction.Win }
        val cleared = state.copy(pending = emptyList(), answers = emptyMap())
        if (winners.isEmpty()) return addKong(cleared, konger, tile)
        return ron(cleared, winners, tile, konger, chankan = true)
    }

    // ---- Wins and the end of a hand ------------------------------------------------------------

    private fun record(state: RcState, seat: Seat, tile: Tile, from: Seat?, score: RcScore): RcWinRecord {
        val dealer = seat == state.dealer
        val value = if (from == null) {
            val (dealerShare, other) = score.tsumo(dealer)
            if (dealer) dealerShare * 3 else dealerShare + other * 2
        } else score.ron(dealer)
        return RcWinRecord(seat, from, tile, score.yaku, score.dora, score.aka, score.ura, score.han, score.fu, score.yakuman, score.limit, value)
    }

    private fun ron(state: RcState, winners: List<Seat>, tile: Tile, from: Seat, chankan: Boolean): RcState {
        val payments = IntArray(SEATS)
        val wins = ArrayList<RcWinRecord>()
        for ((i, seat) in winners.withIndex()) {
            val score = winning(winFor(state, seat, tile, tsumo = false, chankan = chankan))!!
            val win = record(state, seat, tile, from, score)
            wins += win
            // Honba and the riichi sticks go to the winner nearest the discarder.
            val pay = win.value + if (i == 0) 300 * state.honba else 0
            payments[from] -= pay
            payments[seat] += pay
            if (i == 0) payments[seat] += STICK * state.sticks
        }
        val hands = state.hands.toMutableList()
        for (seat in winners) hands[seat] = hands[seat] + tile
        val log = state.log + winners.map { RcEvent(it, RcEventKind.WIN, tile, from) }
        val result = handResult(state, wins, payments.toList())
        return endHand(state.copy(hands = hands, log = log, sticks = 0), result, dealerStays = state.dealer in winners)
    }

    private fun tsumo(state: RcState, seat: Seat): RcState {
        val score = tsumoScore(state, seat) ?: throw IllegalActionException("Your hand does not win")
        val tile = state.drawn!!
        val win = record(state, seat, tile, null, score)
        val (dealerShare, other) = score.tsumo(seat == state.dealer)
        val payments = IntArray(SEATS)
        for (p in 0 until SEATS) {
            if (p == seat) continue
            val pay = (if (p == state.dealer) dealerShare else other) + 100 * state.honba
            payments[p] -= pay
            payments[seat] += pay
        }
        payments[seat] += STICK * state.sticks
        val hands = state.hands.replace(seat, Tiles.minus(state.hands[seat], listOf(tile)) + tile)
        val result = handResult(state, listOf(win), payments.toList())
        return endHand(state.copy(hands = hands, sticks = 0, log = state.log + RcEvent(seat, RcEventKind.WIN, tile)), result, dealerStays = seat == state.dealer)
    }

    private fun handResult(state: RcState, wins: List<RcWinRecord>, payments: List<Int>, ready: List<Boolean> = emptyList(), nagashi: List<Seat> = emptyList(), abort: RcAbort? = null) =
        RcHandResult(
            round = state.round, hand = state.kyoku, honba = state.honba, dealer = state.dealer, wins = wins, ready = ready,
            nagashi = nagashi, abort = abort, payments = payments,
            doraIndicators = state.doraIndicators, uraIndicators = if (state.riichi.any { it }) state.uraIndicators else emptyList(),
        )

    /**
     * The wall ran out. Whoever discarded only terminals and honours, none taken, scores a
     * mangan as a tsumo (流し満貫); otherwise the players not ready pay 3000 between them to
     * those who are.
     */
    private fun exhausted(state: RcState): RcState {
        val ready = (0 until SEATS).map { RcScoring.waits(state.hands[it], state.melds[it]).isNotEmpty() }
        val nagashi = (0 until SEATS).filter { s ->
            !state.taken[s] && state.discarded[s].isNotEmpty() && state.discarded[s].all { it.isTerminalOrHonour }
        }
        val payments = IntArray(SEATS)
        if (nagashi.isNotEmpty()) {
            for (seat in nagashi) {
                val dealer = seat == state.dealer
                for (p in 0 until SEATS) {
                    if (p == seat) continue
                    val pay = if (dealer || p == state.dealer) 4000 else 2000
                    payments[p] -= pay
                    payments[seat] += pay
                }
            }
        } else {
            val yes = ready.count { it }
            if (yes in 1..3) {
                for (p in 0 until SEATS) payments[p] += if (ready[p]) 3000 / yes else -3000 / (SEATS - yes)
            }
        }
        val result = handResult(state, emptyList(), payments.toList(), ready, nagashi)
        return endHand(state.copy(log = state.log + RcEvent(state.turn, RcEventKind.EXHAUSTED)), result, dealerStays = ready[state.dealer], draw = true)
    }

    private fun abort(state: RcState, reason: RcAbort): RcState {
        val result = handResult(state, emptyList(), List(SEATS) { 0 }, abort = reason)
        return endHand(state.copy(log = state.log + RcEvent(state.turn, RcEventKind.ABORT)), result, dealerStays = true, draw = true)
    }

    private fun abortNine(state: RcState, seat: Seat): RcState {
        if (!canAbort(state, seat)) throw IllegalActionException("You cannot call the hand off")
        return abort(state, RcAbort.NINE_TERMINALS)
    }

    /**
     * Pays [result] out and works out what comes next: the dealer keeps the deal with a win
     * or a ready hand at a draw, with one more honba; otherwise the deal passes on. The game
     * ends after the last hand, when the dealer of the last hand keeps the deal while on top,
     * or when someone goes below zero.
     */
    private fun endHand(state: RcState, result: RcHandResult, dealerStays: Boolean, draw: Boolean = false): RcState {
        val points = state.points.zip(result.payments) { a, b -> a + b }
        val honba = if (dealerStays || draw) state.honba + 1 else 0
        var kyoku = state.kyoku
        var round = state.round
        if (!dealerStays) {
            kyoku++
            if (kyoku == SEATS) {
                kyoku = 0
                round++
            }
        }
        val lastHand = state.round == state.rules.lastRound && state.kyoku == SEATS - 1
        val dealerTop = points[state.dealer] >= points.max() && points.count { it == points.max() } == 1
        val over = (state.rules.bust && points.any { it < 0 }) || round > state.rules.lastRound || (lastHand && dealerStays && dealerTop)
        var final = points
        var sticks = state.sticks
        if (over && sticks > 0) {
            // Riichi sticks left on the table go to whoever is on top.
            val top = (0 until SEATS).map { (state.firstDealer + it) % SEATS }.maxBy { points[it] }
            final = points.replace(top, points[top] + STICK * sticks)
            sticks = 0
        }
        return state.copy(
            phase = if (over) RcPhase.OVER else RcPhase.HAND_OVER, result = result, points = final, sticks = sticks,
            pending = emptyList(), answers = emptyMap(), claimTile = null, drawn = null, ready = List(SEATS) { false },
            // Where the next hand is: kept aside until everyone has looked at this one.
            history = state.history, nextRound = round, nextKyoku = kyoku, nextHonba = honba,
        )
    }

    private fun next(state: RcState, seat: Seat): RcState {
        val ready = state.ready.replace(seat, true)
        if (ready.any { !it }) return state.copy(ready = ready)
        return deal(state.copy(history = state.history + state.result!!, round = state.nextRound, kyoku = state.nextKyoku, honba = state.nextHonba, ready = ready))
    }

    private fun <T> List<T>.replace(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }

    override fun result(state: RcState): GameResult? = state.gameResult

    override fun view(state: RcState, seat: Seat?): RcView = RcView.of(state, seat, open = false)

    override fun openView(state: RcState, seat: Seat?): RcView = RcView.of(state, seat, open = true)

    override fun createBot(level: BotLevel, seed: Long): Bot<RcView, RcAction> = RcBot(level, seed)

    override fun guide(rules: RcRules?): List<GuideSection> = RcGuide.sections(rules)
}
