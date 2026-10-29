package io.github.konyaco.tukuai.engine.mahjong.sichuan

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
import io.github.konyaco.tukuai.engine.mahjong.sichuan.bot.ScBot
import kotlin.random.Random

/**
 * Mahjong, played by the Sichuan rules as a blood battle (四川麻将·血战到底). Four players,
 * 108 tiles of three suits and no honours, and no runs claimed from discards. Each player
 * passes three tiles on and picks a suit to get rid of; nobody wins while holding any of it.
 * A player who wins leaves the hand, and the others play on until three have won or the
 * wall runs out. Kongs pay at once, and at the end players still holding their void suit,
 * or not ready, pay the others.
 *
 * Its id is the game's, `mahjong`: other rule sets may join it later as presets.
 */
object SichuanMahjong : GameDefinition<ScRules, ScState, ScAction, ScView> {
    override val id = "mahjong"
    override val rulesCodec: WireCodec<ScRules> = ScRules.CODEC
    override val actionCodec: WireCodec<ScAction> = ScCodecs.ACTION
    override val viewCodec: WireCodec<ScView> = ScCodecs.VIEW
    override val presets: List<RulesPreset<ScRules>> = ScRules.PRESETS

    const val SEATS = 4
    const val HAND = 13

    /** The suits in play. */
    val SUITS: List<TileSuit> = TileSuit.NUMBERED

    /** Points a concealed kong takes from each player still in, an exposed one from the discarder. */
    const val KONG_POINTS = 2 * ScRules.BASE

    /** Points an added kong takes from each player still in. */
    const val ADDED_KONG_POINTS = ScRules.BASE

    override fun seatCount(rules: ScRules) = SEATS

    override fun start(rules: ScRules, seed: Long): ScState {
        val random = Random(seed)
        var wall = Wall(Tiles.set(SUITS).shuffled(random))
        val dealer = random.nextInt(SEATS)
        val direction = SwapDirection.entries[random.nextInt(SwapDirection.entries.size)]
        val hands = MutableList(SEATS) { emptyList<Tile>() }
        for (i in 0 until SEATS) {
            val seat = (dealer + i) % SEATS
            val (tiles, rest) = wall.deal(HAND)
            hands[seat] = tiles.sorted()
            wall = rest
        }
        val (extra, rest) = wall.draw()
        hands[dealer] = (hands[dealer] + extra).sorted()
        return ScState(
            rules = rules, seed = seed, dealer = dealer, wall = rest, hands = hands,
            melds = List(SEATS) { emptyList() }, discards = List(SEATS) { emptyList() },
            voids = List(SEATS) { null }, swaps = List(SEATS) { emptyList() }, received = List(SEATS) { emptyList() },
            swapDirection = direction,
            phase = if (rules.swapThree) ScPhase.SWAP else ScPhase.VOID,
            turn = dealer, drawn = extra, claimTile = null, pending = emptyList(), answers = emptyList(),
            replacement = false, kongDiscard = false, fresh = true, played = List(SEATS) { false },
            wins = emptyList(), scores = List(SEATS) { 0 },
            log = listOf(ScEvent(dealer, ScEventKind.DEAL)),
        )
    }

    /** Seats in turn order from the dealer, for the choices everyone makes before play. */
    private fun fromDealer(state: ScState): List<Seat> = (0 until SEATS).map { (state.dealer + it) % SEATS }

    override fun toAct(state: ScState): Seat? = when (state.phase) {
        ScPhase.SWAP -> fromDealer(state).first { state.swaps[it].isEmpty() }
        ScPhase.VOID -> fromDealer(state).first { state.voids[it] == null }
        ScPhase.PLAY -> state.turn
        ScPhase.CLAIM, ScPhase.ROB -> state.pending.first()
        ScPhase.OVER -> null
    }

    override fun apply(state: ScState, seat: Seat, action: ScAction): ScState {
        val toAct = toAct(state) ?: throw IllegalActionException("The hand is over")
        if (seat != toAct) throw IllegalActionException("It is seat $toAct's turn, not $seat's")
        return when (state.phase) {
            ScPhase.SWAP -> swap(state, seat, action as? ScAction.Swap ?: throw IllegalActionException("Pick three tiles to pass on"))
            ScPhase.VOID -> chooseVoid(state, seat, action as? ScAction.ChooseVoid ?: throw IllegalActionException("Pick your void suit"))
            ScPhase.PLAY -> when (action) {
                is ScAction.Discard -> discard(state, seat, action.tile)
                is ScAction.Kong -> kong(state, seat, action.tile)
                ScAction.Win -> selfDraw(state, seat)
                else -> throw IllegalActionException("Discard, kong or win")
            }
            ScPhase.CLAIM, ScPhase.ROB -> answer(state, seat, action)
            ScPhase.OVER -> throw IllegalActionException("The hand is over")
        }
    }

    // ---- Before play ---------------------------------------------------------------------------

    /** Why [tiles] may not be passed on from [hand], or null if they may. */
    fun swapProblem(hand: List<Tile>, tiles: List<Tile>): String? = when {
        tiles.size != 3 -> "Pass on three tiles"
        tiles.map { it.suit }.toSet().size != 1 -> "Pass on three tiles of one suit"
        !Tiles.counts(hand).let { held -> Tiles.counts(tiles).withIndex().all { (id, n) -> held[id] >= n } } -> "You do not hold those tiles"
        else -> null
    }

    private fun swap(state: ScState, seat: Seat, action: ScAction.Swap): ScState {
        swapProblem(state.hands[seat], action.tiles)?.let { throw IllegalActionException(it) }
        val swaps = state.swaps.toMutableList().also { it[seat] = action.tiles.sorted() }
        val picked = state.copy(swaps = swaps)
        if (swaps.any { it.isEmpty() }) return picked
        val received = MutableList(SEATS) { emptyList<Tile>() }
        for (from in 0 until SEATS) received[state.swapDirection.target(from)] = swaps[from]
        val hands = state.hands.indices.map { s -> (Tiles.minus(state.hands[s], swaps[s]) + received[s]).sorted() }
        val drawn = state.drawn?.takeIf { it in hands[state.dealer] }
        return picked.copy(
            hands = hands, received = received, drawn = drawn, phase = ScPhase.VOID,
            log = state.log + ScEvent(state.dealer, ScEventKind.SWAP),
        )
    }

    private fun chooseVoid(state: ScState, seat: Seat, action: ScAction.ChooseVoid): ScState {
        if (action.suit !in SUITS) throw IllegalActionException("${action.suit} is not in play")
        val voids = state.voids.toMutableList().also { it[seat] = action.suit }
        val picked = state.copy(voids = voids)
        if (voids.any { it == null }) return picked
        return picked.copy(phase = ScPhase.PLAY, turn = state.dealer, log = state.log + ScEvent(state.dealer, ScEventKind.VOID))
    }

    // ---- A turn --------------------------------------------------------------------------------

    /** The tiles [hand] may discard: any, or only the void suit while it holds some. */
    fun discardable(hand: List<Tile>, void: TileSuit?): List<Tile> {
        val voidTiles = hand.filter { it.suit == void }
        return (voidTiles.ifEmpty { hand }).distinct()
    }

    /**
     * The kongs a player may make on their turn: four in [hand], or the fourth tile of a pung
     * in [melds]. Never of the void suit, and only while the wall has a tile to replace it.
     */
    fun kongs(hand: List<Tile>, melds: List<Meld>, void: TileSuit?, wallLeft: Int): List<Tile> {
        if (wallLeft == 0) return emptyList()
        val counts = Tiles.counts(hand)
        val concealed = hand.distinct().filter { counts[it.id] == 4 }
        val added = melds.filter { it.kind == MeldKind.PUNG && counts[it.tile.id] > 0 }.map { it.tile }
        return (concealed + added).filter { it.suit != void }.distinct().sorted()
    }

    private fun discard(state: ScState, seat: Seat, tile: Tile): ScState {
        val hand = state.hands[seat]
        if (tile !in hand) throw IllegalActionException("You do not hold $tile")
        if (tile !in discardable(hand, state.voids[seat])) throw IllegalActionException("Discard your void suit first")
        val after = state.copy(
            hands = state.hands.replace(seat, Tiles.minus(hand, listOf(tile))),
            discards = state.discards.replace(seat, state.discards[seat] + tile),
            played = state.played.replace(seat, true),
            drawn = null,
            log = state.log + ScEvent(seat, ScEventKind.DISCARD, tile),
        )
        val claimers = Claims.after(seat, SEATS).filter { claimOptions(after, it, tile).isNotEmpty() }
        if (claimers.isEmpty()) return advance(after.copy(replacement = false), seat)
        return after.copy(
            phase = ScPhase.CLAIM, turn = seat, claimTile = tile, pending = claimers, answers = emptyList(),
            kongDiscard = state.replacement, replacement = false,
        )
    }

    /** What [seat] may answer to [tile], discarded by someone else; empty when only passing. */
    fun claimOptions(state: ScState, seat: Seat, tile: Tile): List<ClaimKind> {
        if (state.won(seat)) return emptyList()
        val hand = state.hands[seat]
        val void = state.voids[seat]
        val out = ArrayList<ClaimKind>()
        if (ScScoring.isWin(hand + tile, state.melds[seat], void)) out += ClaimKind.WIN
        if (tile.suit != void && !state.wall.isEmpty) {
            val counts = Tiles.counts(hand)
            if (Claims.canKong(counts, tile)) out += ClaimKind.KONG
            if (Claims.canPung(counts, tile)) out += ClaimKind.PUNG
        }
        return out
    }

    /** Who could rob a kong of [tile] made by [seat]: anyone still in who would win on it. */
    private fun robbers(state: ScState, seat: Seat, tile: Tile): List<Seat> =
        Claims.after(seat, SEATS).filter { !state.won(it) && ScScoring.isWin(state.hands[it] + tile, state.melds[it], state.voids[it]) }

    private fun kong(state: ScState, seat: Seat, tile: Tile): ScState {
        val hand = state.hands[seat]
        if (tile !in kongs(hand, state.melds[seat], state.voids[seat], state.wall.remaining)) throw IllegalActionException("You cannot kong $tile")
        val counts = Tiles.counts(hand)
        if (counts[tile.id] == 4) {
            val payments = pay(state, state.active - seat, seat, KONG_POINTS)
            val after = state.copy(
                hands = state.hands.replace(seat, hand.filter { it != tile }),
                melds = state.melds.replace(seat, state.melds[seat] + Meld(MeldKind.CONCEALED_KONG, tile)),
                scores = add(state.scores, payments), fresh = false, drawn = null,
                log = state.log + ScEvent(seat, ScEventKind.KONG, tile, meld = MeldKind.CONCEALED_KONG, payments = payments),
            )
            return drawReplacement(after, seat)
        }
        val robbers = robbers(state, seat, tile)
        val after = state.copy(hands = state.hands.replace(seat, Tiles.minus(hand, listOf(tile))), drawn = null, fresh = false)
        if (robbers.isEmpty()) return addKong(after, seat, tile)
        return after.copy(phase = ScPhase.ROB, turn = seat, claimTile = tile, pending = robbers, answers = emptyList())
    }

    /** Makes [seat]'s pung of [tile] a kong with the tile they already took from their hand, and draws them a replacement. */
    private fun addKong(state: ScState, seat: Seat, tile: Tile): ScState {
        val payments = pay(state, state.active - seat, seat, ADDED_KONG_POINTS)
        val melds = state.melds[seat].map { if (it.kind == MeldKind.PUNG && it.tile == tile) it.copy(kind = MeldKind.ADDED_KONG) else it }
        val after = state.copy(
            melds = state.melds.replace(seat, melds), scores = add(state.scores, payments), claimTile = null,
            log = state.log + ScEvent(seat, ScEventKind.KONG, tile, meld = MeldKind.ADDED_KONG, payments = payments),
        )
        return drawReplacement(after, seat)
    }

    private fun answer(state: ScState, seat: Seat, action: ScAction): ScState {
        val tile = state.claimTile!!
        val options = if (state.phase == ScPhase.ROB) listOf(ClaimKind.WIN) else claimOptions(state, seat, tile)
        val kind = when (action) {
            ScAction.Pass -> ClaimKind.PASS
            ScAction.Win -> ClaimKind.WIN
            ScAction.Pung -> ClaimKind.PUNG
            is ScAction.Kong -> if (action.tile == tile) ClaimKind.KONG else throw IllegalActionException("Kong the discard, $tile")
            else -> throw IllegalActionException("Answer the discard: claim it or pass")
        }
        if (kind != ClaimKind.PASS && kind !in options) throw IllegalActionException("You cannot ${kind.name.lowercase()} on $tile")
        val answers = state.answers + (seat to kind)
        var pending = state.pending.drop(1)
        // Once someone wins, only those who could also win on it are still asked.
        if (answers.any { it.second == ClaimKind.WIN }) {
            pending = pending.filter { state.phase == ScPhase.ROB || ClaimKind.WIN in claimOptions(state, it, tile) }
        }
        val after = state.copy(answers = answers, pending = pending)
        if (pending.isNotEmpty()) return after
        return if (state.phase == ScPhase.ROB) resolveRob(after) else resolveClaim(after)
    }

    private fun resolveClaim(state: ScState): ScState {
        val tile = state.claimTile!!
        val from = state.turn
        val chosen = Claims.resolve(state.answers, multipleWins = true)
        val cleared = state.copy(pending = emptyList(), answers = emptyList())
        if (chosen.isEmpty()) return advance(cleared.copy(claimTile = null, kongDiscard = false), from)
        val claimed = cleared.copy(discards = state.discards.replace(from, state.discards[from].dropLast(1)))
        if (chosen.first().second == ClaimKind.WIN) {
            val context = WinContext(kongDiscard = state.kongDiscard, lastTile = state.wall.isEmpty)
            var s = claimed
            for ((winner, _) in chosen) s = win(s, winner, tile, from, context)
            return afterWins(s.copy(claimTile = null, kongDiscard = false), chosen.last().first)
        }
        val (seat, kind) = chosen.single()
        val hand = state.hands[seat]
        return if (kind == ClaimKind.KONG) {
            val payments = pay(state, listOf(from), seat, KONG_POINTS)
            val after = claimed.copy(
                hands = claimed.hands.replace(seat, hand.filter { it != tile }),
                melds = claimed.melds.replace(seat, claimed.melds[seat] + Meld(MeldKind.EXPOSED_KONG, tile, from)),
                scores = add(state.scores, payments), fresh = false, claimTile = null, kongDiscard = false,
                log = state.log + ScEvent(seat, ScEventKind.KONG, tile, from, MeldKind.EXPOSED_KONG, payments),
            )
            drawReplacement(after, seat)
        } else {
            claimed.copy(
                hands = claimed.hands.replace(seat, Tiles.minus(hand, listOf(tile, tile))),
                melds = claimed.melds.replace(seat, claimed.melds[seat] + Meld(MeldKind.PUNG, tile, from)),
                fresh = false, claimTile = null, kongDiscard = false,
                phase = ScPhase.PLAY, turn = seat, drawn = null, replacement = false,
                log = state.log + ScEvent(seat, ScEventKind.PUNG, tile, from),
            )
        }
    }

    private fun resolveRob(state: ScState): ScState {
        val tile = state.claimTile!!
        val konger = state.turn
        val robbers = state.answers.filter { it.second == ClaimKind.WIN }.map { it.first }
        val cleared = state.copy(pending = emptyList(), answers = emptyList())
        if (robbers.isEmpty()) return addKong(cleared, konger, tile)
        var s = cleared
        for (robber in robbers) s = win(s, robber, tile, konger, WinContext(robKong = true))
        return afterWins(s.copy(claimTile = null), robbers.last())
    }

    private fun selfDraw(state: ScState, seat: Seat): ScState {
        val hand = state.hands[seat]
        if (!ScScoring.isWin(hand, state.melds[seat], state.voids[seat])) throw IllegalActionException("Your hand is not complete")
        val heavenly = state.fresh && seat == state.dealer && !state.played[seat]
        // The dealer's hand as dealt may have passed its fourteenth tile on; any tile will do then.
        val tile = state.drawn ?: hand.lastOrNull()?.takeIf { heavenly } ?: throw IllegalActionException("Win on a tile you drew")
        val context = WinContext(
            selfDrawn = true,
            kongBloom = state.replacement,
            lastTile = state.wall.isEmpty,
            heavenly = heavenly,
            earthly = state.fresh && seat != state.dealer && !state.played[seat],
        )
        val won = win(state.copy(hands = state.hands.replace(seat, Tiles.minus(hand, listOf(tile)))), seat, tile, null, context)
        return afterWins(won.copy(drawn = null, replacement = false), seat)
    }

    /**
     * Records [seat]'s win on [tile]: self-drawn when [from] is null, when everyone else still
     * in pays the hand and one more; otherwise [from] pays it. The tile joins the winner's
     * hand, which then lies face up.
     */
    private fun win(state: ScState, seat: Seat, tile: Tile, from: Seat?, context: WinContext): ScState {
        val concealed = state.hands[seat] + tile
        val earthly = from != null && state.fresh && seat != state.dealer && !state.played[seat]
        val fans = ScScoring.fans(state.rules, concealed, state.melds[seat], context.copy(earthly = context.earthly || earthly))
        val fan = ScScoring.total(state.rules, fans)
        val points = state.rules.points(fan)
        val payments = if (from == null) {
            pay(state, state.active - seat, seat, points + ScRules.BASE)
        } else {
            pay(state, listOf(from), seat, points)
        }
        val record = ScWin(seat, tile, from, fans, fan, points, payments)
        return state.copy(
            hands = state.hands.replace(seat, concealed.sorted()),
            wins = state.wins + record,
            scores = add(state.scores, payments),
            log = state.log + ScEvent(seat, ScEventKind.WIN, tile, from ?: -1, payments = payments),
        )
    }

    /** After a win or several, the hand ends with three winners; otherwise play goes on after [last]. */
    private fun afterWins(state: ScState, last: Seat): ScState =
        if (state.wins.size >= SEATS - 1) state.copy(phase = ScPhase.OVER, pending = emptyList()) else advance(state, last)

    /** The next player still in after [seat] draws, or the hand ends when the wall is empty. */
    private fun advance(state: ScState, seat: Seat): ScState {
        if (state.wall.isEmpty) return exhausted(state)
        val next = Claims.after(seat, SEATS).first { !state.won(it) }
        val (tile, wall) = state.wall.draw()
        return state.copy(
            wall = wall, hands = state.hands.replace(next, (state.hands[next] + tile).sorted()),
            phase = ScPhase.PLAY, turn = next, drawn = tile, replacement = false, pending = emptyList(),
            log = state.log + ScEvent(next, ScEventKind.DRAW, tile),
        )
    }

    private fun drawReplacement(state: ScState, seat: Seat): ScState {
        val (tile, wall) = state.wall.drawBack()
        return state.copy(
            wall = wall, hands = state.hands.replace(seat, (state.hands[seat] + tile).sorted()),
            phase = ScPhase.PLAY, turn = seat, drawn = tile, replacement = true, pending = emptyList(),
            log = state.log + ScEvent(seat, ScEventKind.DRAW, tile, other = 1),
        )
    }

    /**
     * The wall ran out. Whoever still holds their void suit (花猪) pays the most a hand can win
     * to each other player still in who does not; then whoever is not ready pays each ready
     * player the best hand that player waits on (查大叫).
     */
    private fun exhausted(state: ScState): ScState {
        var s = state.copy(phase = ScPhase.OVER, pending = emptyList(), claimTile = null, log = state.log + ScEvent(state.turn, ScEventKind.EXHAUSTED))
        val active = state.active
        val pigs = active.filter { seat -> state.hands[seat].any { it.suit == state.voids[seat] } }
        for (pig in pigs) {
            val payments = payTo(s, pig, (active - pigs.toSet()).associateWith { state.rules.points(state.rules.topFan) })
            if (payments.any { it != 0 }) s = s.copy(scores = add(s.scores, payments), log = s.log + ScEvent(pig, ScEventKind.PIG, payments = payments))
        }
        val others = active - pigs.toSet()
        val best = others.associateWith { ScScoring.bestReady(state.rules, state.hands[it], state.melds[it], state.voids[it]) }
        val ready = others.filter { best.getValue(it) >= 0 }
        for (seat in others - ready.toSet()) {
            val payments = payTo(s, seat, ready.associateWith { state.rules.points(best.getValue(it)) })
            if (payments.any { it != 0 }) s = s.copy(scores = add(s.scores, payments), log = s.log + ScEvent(seat, ScEventKind.NOT_READY, payments = payments))
        }
        return s
    }

    /** [payers] each pay [receiver] [points]: what that changes for each seat. */
    private fun pay(state: ScState, payers: List<Seat>, receiver: Seat, points: Int): List<Int> {
        val out = IntArray(state.hands.size)
        for (payer in payers) {
            out[payer] -= points
            out[receiver] += points
        }
        return out.toList()
    }

    /** [payer] pays each seat of [amounts] its amount. */
    private fun payTo(state: ScState, payer: Seat, amounts: Map<Seat, Int>): List<Int> {
        val out = IntArray(state.hands.size)
        for ((seat, points) in amounts) {
            out[payer] -= points
            out[seat] += points
        }
        return out.toList()
    }

    private fun add(scores: List<Int>, payments: List<Int>): List<Int> = scores.zip(payments) { a, b -> a + b }

    private fun <T> List<T>.replace(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }

    override fun result(state: ScState): GameResult? = state.result

    override fun view(state: ScState, seat: Seat?): ScView = ScView.of(state, seat, open = false)

    override fun openView(state: ScState, seat: Seat?): ScView = ScView.of(state, seat, open = true)

    override fun createBot(level: BotLevel, seed: Long): Bot<ScView, ScAction> = ScBot(level, seed)

    override fun guide(rules: ScRules?): List<GuideSection> = ScGuide.sections(rules)
}
