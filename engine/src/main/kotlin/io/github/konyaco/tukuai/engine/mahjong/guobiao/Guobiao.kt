package io.github.konyaco.tukuai.engine.mahjong.guobiao

import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.GameDefinition
import io.github.konyaco.tukuai.engine.core.GameResult
import io.github.konyaco.tukuai.engine.core.GuideSection
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.RulesPreset
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.mahjong.ClaimKind
import io.github.konyaco.tukuai.engine.mahjong.Claims
import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit
import io.github.konyaco.tukuai.engine.mahjong.Tiles
import io.github.konyaco.tukuai.engine.mahjong.Wall
import io.github.konyaco.tukuai.engine.mahjong.guobiao.bot.GbBot
import kotlin.random.Random

/** What a player may answer to a discard: win, kong, pung, or a run from each of [chows]. */
data class GbOptions(val win: Boolean = false, val kong: Boolean = false, val pung: Boolean = false, val chows: List<Tile> = emptyList()) {
    val any: Boolean get() = win || kong || pung || chows.isNotEmpty()

    val kinds: List<ClaimKind>
        get() = listOfNotNull(
            ClaimKind.WIN.takeIf { win }, ClaimKind.KONG.takeIf { kong }, ClaimKind.PUNG.takeIf { pung }, ClaimKind.CHOW.takeIf { chows.isNotEmpty() },
        )

    companion object {
        val NONE = GbOptions()
    }
}

/**
 * Guobiao mahjong (国标麻将), by the Chinese official rules for competition. Four players,
 * 136 tiles and eight flowers. Runs may be claimed from the player before you, pungs and
 * kongs from anyone. A hand needs 8 fan, flowers not counted, to win; the 81 fan are read
 * from the hand as [GbScoring] does. The winner takes 8 points from everyone and the fan
 * from whoever discarded the tile, or from everyone when self-drawn. The deal passes on
 * every hand, and the round's wind every four.
 */
object Guobiao : GameDefinition<GbRules, GbState, GbAction, GbView> {
    override val id = "guobiao"
    override val rulesCodec: WireCodec<GbRules> = GbRules.CODEC
    override val actionCodec: WireCodec<GbAction> = GbCodecs.ACTION
    override val viewCodec: WireCodec<GbView> = GbCodecs.VIEW
    override val presets: List<RulesPreset<GbRules>> = GbRules.PRESETS

    const val SEATS = 4
    const val HAND = 13

    override fun seatCount(rules: GbRules) = SEATS

    override fun start(rules: GbRules, seed: Long): GbState {
        val firstDealer = Random(seed).nextInt(SEATS)
        val empty = GbState(
            rules = rules, seed = seed, hand = 0, firstDealer = firstDealer, wall = Wall(emptyList()),
            hands = List(SEATS) { emptyList() }, melds = List(SEATS) { emptyList() }, discards = List(SEATS) { emptyList() },
            flowers = List(SEATS) { emptyList() }, phase = GbPhase.PLAY, turn = firstDealer, drawn = null, claimTile = null,
            pending = emptyList(), answers = emptyMap(), replacement = false, fresh = true, result = null, history = emptyList(),
            scores = List(SEATS) { 0 }, ready = List(SEATS) { false }, log = emptyList(),
        )
        return deal(empty, 0)
    }

    /** Deals hand [hand]: 13 tiles each and a 14th to the dealer, each player then replacing their flowers from the back. */
    private fun deal(state: GbState, hand: Int): GbState {
        val random = Random(state.seed * 1_000_003L + hand * 7919L + 17)
        val flowers = if (state.rules.flowers) TileSuit.FLOWERS.tiles else emptyList()
        var wall = Wall((Tiles.set(TileSuit.HAND) + flowers).shuffled(random))
        val dealer = (state.firstDealer + hand) % SEATS
        val hands = MutableList(SEATS) { emptyList<Tile>() }
        for (i in 0 until SEATS) {
            val seat = (dealer + i) % SEATS
            val (tiles, rest) = wall.deal(HAND)
            hands[seat] = tiles
            wall = rest
        }
        val (extra, rest) = wall.draw()
        wall = rest
        hands[dealer] = hands[dealer] + extra
        var drawn: Tile = extra
        val set = MutableList(SEATS) { emptyList<Tile>() }
        val log = arrayListOf(GbEvent(dealer, GbEventKind.DEAL))
        // Each player in turn sets their flowers aside and draws as many from the back, again for flowers drawn so.
        for (i in 0 until SEATS) {
            val seat = (dealer + i) % SEATS
            while (true) {
                val found = hands[seat].filter { it.suit == TileSuit.FLOWERS }
                if (found.isEmpty()) break
                hands[seat] = hands[seat] - found.toSet()
                set[seat] = set[seat] + found
                for (f in found) {
                    log += GbEvent(seat, GbEventKind.FLOWER, f)
                    val (tile, w) = wall.drawBack()
                    wall = w
                    hands[seat] = hands[seat] + tile
                    if (seat == dealer && f == drawn) drawn = tile
                }
            }
        }
        return state.copy(
            hand = hand, wall = wall, hands = hands.map { it.sorted() }, melds = List(SEATS) { emptyList() },
            discards = List(SEATS) { emptyList() }, flowers = set, phase = GbPhase.PLAY, turn = dealer,
            drawn = drawn, claimTile = null, pending = emptyList(), answers = emptyMap(), replacement = false,
            fresh = true, result = null, ready = List(SEATS) { false }, log = log,
        )
    }

    override fun toAct(state: GbState): Seat? = actors(state).firstOrNull()

    override fun actors(state: GbState): List<Seat> = when (state.phase) {
        GbPhase.PLAY -> listOf(state.turn)
        GbPhase.CLAIM, GbPhase.ROB -> state.pending
        GbPhase.HAND_OVER -> (0 until SEATS).map { (state.dealer + it) % SEATS }.filter { !state.ready[it] }
        GbPhase.OVER -> emptyList()
    }

    override fun apply(state: GbState, seat: Seat, action: GbAction): GbState {
        if (seat !in actors(state)) throw IllegalActionException(if (state.finished) "The game is over" else "It is not your turn")
        return when (state.phase) {
            GbPhase.PLAY -> when (action) {
                is GbAction.Discard -> discard(state, seat, action.tile)
                is GbAction.Kong -> kong(state, seat, action.tile)
                GbAction.Win -> selfDraw(state, seat)
                else -> throw IllegalActionException("Discard, kong or win")
            }
            GbPhase.CLAIM, GbPhase.ROB -> answer(state, seat, action)
            GbPhase.HAND_OVER -> if (action == GbAction.Next) next(state, seat) else throw IllegalActionException("The hand is over")
            GbPhase.OVER -> throw IllegalActionException("The game is over")
        }
    }

    // ---- Reading the table ---------------------------------------------------------------------

    /** How many tiles of [tile]'s kind lie face up: in the discards and the melds. */
    fun visible(discards: List<List<Tile>>, melds: List<List<Meld>>, tile: Tile): Int =
        discards.sumOf { d -> d.count { it.id == tile.id } } + melds.sumOf { m -> m.sumOf { meld -> meld.tiles.count { it.id == tile.id } } }

    /**
     * [seat]'s hand won on [tile], as the scorer reads it. [claimed] is set when the tile is a
     * discard still lying in the discards; [robKong] when it is a kong's fourth tile.
     */
    fun winFor(state: GbState, seat: Seat, tile: Tile, selfDrawn: Boolean, claimed: Boolean = false, robKong: Boolean = false): GbWin {
        val concealed = if (selfDrawn) state.hands[seat] else state.hands[seat] + tile
        val shown = visible(state.discards, state.melds, tile) - (if (claimed) 1 else 0)
        return GbWin(
            concealed = concealed, melds = state.melds[seat], tile = tile, selfDrawn = selfDrawn,
            seatWind = state.seatWind(seat), prevalentWind = state.prevalentWind,
            lastTile = state.wall.isEmpty && !robKong, kongReplacement = selfDrawn && state.replacement, robKong = robKong,
            lastOfKind = shown == 3, flowers = state.flowers[seat].size,
        )
    }

    /** The score of [win] when it wins at this table, or null when it is not a hand or falls short of the least fan. */
    fun winning(rules: GbRules, win: GbWin): GbScore? = GbScoring.score(win)?.takeIf { it.base >= rules.minFan }

    /** What [seat] may answer to [tile], discarded by [from]. */
    fun claimOptions(state: GbState, seat: Seat, tile: Tile, from: Seat): GbOptions {
        if (seat == from) return GbOptions.NONE
        val hand = state.hands[seat]
        val win = winning(state.rules, winFor(state, seat, tile, selfDrawn = false, claimed = true)) != null
        if (state.wall.isEmpty) return GbOptions(win = win)
        val counts = Tiles.counts(hand)
        val chows = if (seat == (from + 1) % SEATS) chowStarts(counts, tile) else emptyList()
        return GbOptions(win = win, kong = Claims.canKong(counts, tile), pung = Claims.canPung(counts, tile), chows = chows)
    }

    /** The runs a hand with [counts] can make with [tile], by where they start. */
    fun chowStarts(counts: IntArray, tile: Tile): List<Tile> {
        if (!tile.suit.numbered) return emptyList()
        return (tile.rank - 2..tile.rank).filter { start -> start >= 1 && start + 2 <= tile.suit.ranks }
            .map { Tile.of(tile.suit, it) }
            .filter { start -> (0..2).all { d -> val t = Tile.of(start.suit, start.rank + d); t.id == tile.id || counts[t.id] > 0 } }
    }

    /** The kongs [hand] may make on its turn: four in hand, or the fourth of a pung laid down. */
    fun kongs(hand: List<Tile>, melds: List<Meld>, wallLeft: Int): List<Tile> {
        if (wallLeft == 0) return emptyList()
        val counts = Tiles.counts(hand)
        val concealed = hand.distinct().filter { counts[it.id] == 4 }
        val added = melds.filter { it.kind == MeldKind.PUNG && counts[it.tile.id] > 0 }.map { it.tile }
        return (concealed + added).distinct().sorted()
    }

    /** The score [seat] would win with, self-drawn, on their turn now; null when they cannot. */
    fun selfDrawScore(state: GbState, seat: Seat): GbScore? {
        if (state.phase != GbPhase.PLAY || state.turn != seat) return null
        val tile = state.drawn ?: return null
        return winning(state.rules, winFor(state, seat, tile, selfDrawn = true))
    }

    // ---- A turn --------------------------------------------------------------------------------

    private fun discard(state: GbState, seat: Seat, tile: Tile): GbState {
        val hand = state.hands[seat]
        if (tile !in hand) throw IllegalActionException("You do not hold $tile")
        val after = state.copy(
            hands = state.hands.replace(seat, Tiles.minus(hand, listOf(tile))),
            discards = state.discards.replace(seat, state.discards[seat] + tile),
            drawn = null, fresh = false, replacement = false,
            log = state.log + GbEvent(seat, GbEventKind.DISCARD, tile),
        )
        val claimers = Claims.after(seat, SEATS).filter { claimOptions(after, it, tile, seat).any }
        if (claimers.isEmpty()) return advance(after, seat)
        return after.copy(phase = GbPhase.CLAIM, turn = seat, claimTile = tile, pending = claimers, answers = emptyMap())
    }

    private fun kong(state: GbState, seat: Seat, tile: Tile): GbState {
        val hand = state.hands[seat]
        if (tile !in kongs(hand, state.melds[seat], state.wall.remaining)) throw IllegalActionException("You cannot kong $tile")
        val counts = Tiles.counts(hand)
        if (counts[tile.id] == 4) {
            val after = state.copy(
                hands = state.hands.replace(seat, hand.filter { it.id != tile.id }),
                melds = state.melds.replace(seat, state.melds[seat] + Meld(MeldKind.CONCEALED_KONG, tile.plain)),
                fresh = false, drawn = null,
                log = state.log + GbEvent(seat, GbEventKind.KONG, tile, meld = MeldKind.CONCEALED_KONG),
            )
            return draw(after, seat, back = true, kong = true)
        }
        val after = state.copy(hands = state.hands.replace(seat, Tiles.minusKind(hand, tile, 1)), drawn = null, fresh = false)
        val robbers = Claims.after(seat, SEATS).filter { winning(state.rules, winFor(after, it, tile, selfDrawn = false, robKong = true)) != null }
        if (robbers.isEmpty()) return addKong(after, seat, tile)
        return after.copy(phase = GbPhase.ROB, turn = seat, claimTile = tile, pending = robbers, answers = emptyMap())
    }

    private fun addKong(state: GbState, seat: Seat, tile: Tile): GbState {
        val melds = state.melds[seat].map { if (it.kind == MeldKind.PUNG && it.tile.id == tile.id) it.copy(kind = MeldKind.ADDED_KONG) else it }
        val after = state.copy(
            melds = state.melds.replace(seat, melds), claimTile = null, pending = emptyList(), answers = emptyMap(),
            log = state.log + GbEvent(seat, GbEventKind.KONG, tile, meld = MeldKind.ADDED_KONG),
        )
        return draw(after, seat, back = true, kong = true)
    }

    private fun answer(state: GbState, seat: Seat, action: GbAction): GbState {
        val tile = state.claimTile!!
        val rob = state.phase == GbPhase.ROB
        val options = if (rob) GbOptions(win = true) else claimOptions(state, seat, tile, state.turn)
        val ok = when (action) {
            GbAction.Pass -> true
            GbAction.Win -> options.win
            GbAction.Pung -> options.pung
            is GbAction.Kong -> options.kong && action.tile.id == tile.id
            is GbAction.Chow -> action.start in options.chows
            else -> throw IllegalActionException("Answer the discard: claim it or pass")
        }
        if (!ok) throw IllegalActionException("You cannot do that with $tile")
        val after = state.copy(answers = state.answers + (seat to action), pending = state.pending - seat)
        if (after.pending.isEmpty() || settled(after)) return if (rob) resolveRob(after) else resolveClaim(after)
        return after
    }

    private fun precedence(action: GbAction?): Int = when (action) {
        GbAction.Win -> ClaimKind.WIN.precedence
        GbAction.Pung, is GbAction.Kong -> ClaimKind.PUNG.precedence
        is GbAction.Chow -> ClaimKind.CHOW.precedence
        else -> 0
    }

    /** The answer that goes through: the highest, and of two wins the one nearer in turn after the discarder. */
    private fun chosen(state: GbState): Pair<Seat, GbAction>? {
        val order = Claims.after(state.turn, SEATS)
        return order.mapNotNull { s -> state.answers[s]?.let { s to it } }
            .filter { precedence(it.second) > 0 }
            .maxWithOrNull(compareBy<Pair<Seat, GbAction>> { precedence(it.second) }.thenByDescending { order.indexOf(it.first) })
    }

    /** Whether nobody still to answer could beat the best answer so far, so play need not wait for them. */
    private fun settled(state: GbState): Boolean {
        val best = chosen(state) ?: return false
        val order = Claims.after(state.turn, SEATS)
        val tile = state.claimTile!!
        return state.pending.none { s ->
            val most = if (state.phase == GbPhase.ROB) ClaimKind.WIN.precedence else {
                val o = claimOptions(state, s, tile, state.turn)
                when {
                    o.win -> ClaimKind.WIN.precedence
                    o.pung || o.kong -> ClaimKind.PUNG.precedence
                    o.chows.isNotEmpty() -> ClaimKind.CHOW.precedence
                    else -> 0
                }
            }
            val bestP = precedence(best.second)
            most > bestP || (most == bestP && order.indexOf(s) < order.indexOf(best.first))
        }
    }

    private fun resolveClaim(state: GbState): GbState {
        val tile = state.claimTile!!
        val from = state.turn
        val choice = chosen(state)
        val cleared = state.copy(pending = emptyList(), answers = emptyMap())
        if (choice == null) return advance(cleared.copy(claimTile = null), from)
        val (seat, action) = choice
        if (action == GbAction.Win) {
            val win = winFor(state, seat, tile, selfDrawn = false, claimed = true)
            val score = winning(state.rules, win)!!
            val taken = cleared.copy(discards = state.discards.replace(from, state.discards[from].dropLast(1)))
            return win(taken, seat, tile, from, score)
        }
        val hand = state.hands[seat]
        val claimed = cleared.copy(discards = state.discards.replace(from, state.discards[from].dropLast(1)), claimTile = null, fresh = false)
        return when (action) {
            is GbAction.Kong -> {
                val after = claimed.copy(
                    hands = claimed.hands.replace(seat, hand.filter { it.id != tile.id }),
                    melds = claimed.melds.replace(seat, claimed.melds[seat] + Meld(MeldKind.EXPOSED_KONG, tile.plain, from, claimed = tile)),
                    log = state.log + GbEvent(seat, GbEventKind.KONG, tile, from, MeldKind.EXPOSED_KONG),
                )
                draw(after, seat, back = true, kong = true)
            }
            GbAction.Pung -> claimed.copy(
                hands = claimed.hands.replace(seat, Tiles.minusKind(hand, tile, 2)),
                melds = claimed.melds.replace(seat, claimed.melds[seat] + Meld(MeldKind.PUNG, tile.plain, from, claimed = tile)),
                phase = GbPhase.PLAY, turn = seat, drawn = null, replacement = false,
                log = state.log + GbEvent(seat, GbEventKind.PUNG, tile, from),
            )
            is GbAction.Chow -> {
                val start = action.start
                val others = (0..2).map { Tile.of(start.suit, start.rank + it) }.filter { it.id != tile.id }
                var left = hand
                for (t in others) left = Tiles.minusKind(left, t, 1)
                claimed.copy(
                    hands = claimed.hands.replace(seat, left),
                    melds = claimed.melds.replace(seat, claimed.melds[seat] + Meld(MeldKind.CHOW, start, from, claimed = tile)),
                    phase = GbPhase.PLAY, turn = seat, drawn = null, replacement = false,
                    log = state.log + GbEvent(seat, GbEventKind.CHOW, start, from),
                )
            }
            else -> throw IllegalStateException("Unknown claim $action")
        }
    }

    private fun resolveRob(state: GbState): GbState {
        val tile = state.claimTile!!
        val konger = state.turn
        val choice = chosen(state)
        val cleared = state.copy(pending = emptyList(), answers = emptyMap())
        if (choice == null) return addKong(cleared, konger, tile)
        val robber = choice.first
        val score = winning(state.rules, winFor(state, robber, tile, selfDrawn = false, robKong = true))!!
        return win(cleared, robber, tile, konger, score)
    }

    private fun selfDraw(state: GbState, seat: Seat): GbState {
        val score = selfDrawScore(state, seat) ?: throw IllegalActionException("Your hand does not win")
        val tile = state.drawn!!
        return win(state.copy(hands = state.hands.replace(seat, Tiles.minus(state.hands[seat], listOf(tile)))), seat, tile, null, score)
    }

    /**
     * [seat] won on [tile] with [score]: self-drawn when [from] is null, when everyone pays 8
     * and the fan; otherwise [from] pays 8 and the fan and the other two pay 8 each.
     */
    private fun win(state: GbState, seat: Seat, tile: Tile, from: Seat?, score: GbScore): GbState {
        val fan = score.total
        val payments = IntArray(SEATS)
        for (other in 0 until SEATS) {
            if (other == seat) continue
            val pay = if (from == null || other == from) GbRules.BASE + fan else GbRules.BASE
            payments[other] -= pay
            payments[seat] += pay
        }
        val result = GbHandResult(state.hand, state.dealer, seat, from, tile, score.fans, payments.toList())
        val hands = state.hands.replace(seat, state.hands[seat] + tile)
        return endHand(state.copy(hands = hands, log = state.log + GbEvent(seat, GbEventKind.WIN, tile, from ?: -1)), result)
    }

    private fun endHand(state: GbState, result: GbHandResult): GbState {
        val scores = state.scores.zip(result.payments) { a, b -> a + b }
        val last = state.hand + 1 >= state.rules.hands
        return state.copy(
            phase = if (last) GbPhase.OVER else GbPhase.HAND_OVER, result = result, scores = scores,
            pending = emptyList(), answers = emptyMap(), claimTile = null, drawn = null, ready = List(SEATS) { false },
        )
    }

    private fun next(state: GbState, seat: Seat): GbState {
        val ready = state.ready.replace(seat, true)
        if (ready.any { !it }) return state.copy(ready = ready)
        return deal(state.copy(history = state.history + state.result!!, ready = ready), state.hand + 1)
    }

    /** The player after [seat] draws, or the hand ends when the wall is empty. */
    private fun advance(state: GbState, seat: Seat): GbState {
        if (state.wall.isEmpty) return exhausted(state)
        return draw(state.copy(claimTile = null), (seat + 1) % SEATS, back = false, kong = false)
    }

    /** [seat] draws, from the back after a kong, setting flowers aside and drawing again from the back for each. */
    private fun draw(state: GbState, seat: Seat, back: Boolean, kong: Boolean): GbState {
        var s = state
        var fromBack = back
        while (true) {
            if (s.wall.isEmpty) return exhausted(s)
            val (tile, wall) = if (fromBack) s.wall.drawBack() else s.wall.draw()
            s = s.copy(wall = wall, log = s.log + GbEvent(seat, GbEventKind.DRAW, tile, if (fromBack) 1 else -1))
            if (tile.suit == TileSuit.FLOWERS) {
                s = s.copy(flowers = s.flowers.replace(seat, s.flowers[seat] + tile), log = s.log + GbEvent(seat, GbEventKind.FLOWER, tile))
                fromBack = true
                continue
            }
            return s.copy(
                hands = s.hands.replace(seat, (s.hands[seat] + tile).sorted()),
                phase = GbPhase.PLAY, turn = seat, drawn = tile, replacement = kong, pending = emptyList(), answers = emptyMap(),
            )
        }
    }

    private fun exhausted(state: GbState): GbState {
        val result = GbHandResult(state.hand, state.dealer, null, null, null, emptyList(), List(SEATS) { 0 })
        return endHand(state.copy(log = state.log + GbEvent(state.turn, GbEventKind.EXHAUSTED)), result)
    }

    private fun <T> List<T>.replace(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }

    override fun result(state: GbState): GameResult? = state.gameResult

    override fun view(state: GbState, seat: Seat?): GbView = GbView.of(state, seat, open = false)

    override fun openView(state: GbState, seat: Seat?): GbView = GbView.of(state, seat, open = true)

    override fun createBot(level: BotLevel, seed: Long): Bot<GbView, GbAction> = GbBot(level, seed)

    override fun guide(rules: GbRules?): List<GuideSection> = GbGuide.sections(rules)
}
