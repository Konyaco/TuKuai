package io.github.konyaco.tukuai.engine.cave

import io.github.konyaco.tukuai.engine.cave.bot.CaveBot
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
 * Cave expedition (矿洞探险), for 2 to 8 players: a game of pushing your luck. Each round
 * everyone goes down into a cave, and cards turn up one at a time. A vein of ore is shared
 * out evenly among the players inside, and what does not divide stays on the card. After
 * every card, all those inside choose at once, unseen, to go deeper or to go back out. Going
 * back puts what they carry in their chest for good, together with their share of the
 * emeralds left on the path. When a hazard turns up for the second time, whoever is still
 * inside drops everything they carry. After the last round the richest player wins.
 */
object Cave : GameDefinition<CaveRules, CaveState, CaveAction, CaveView> {
    override val id = "cave"
    override val rulesCodec: WireCodec<CaveRules> = CaveRules.CODEC
    override val actionCodec: WireCodec<CaveAction> = CaveCodecs.ACTION
    override val viewCodec: WireCodec<CaveView> = CaveCodecs.VIEW
    override val presets: List<RulesPreset<CaveRules>> = CaveRules.PRESETS

    override fun seatCount(rules: CaveRules) = rules.players

    override fun start(rules: CaveRules, seed: Long): CaveState {
        val empty = CaveState(
            rules = rules, seed = seed, round = 0, deck = emptyList(), removed = emptyList(), path = emptyList(),
            inside = List(rules.players) { false }, choices = List(rules.players) { null },
            pack = List(rules.players) { 0 }, chest = List(rules.players) { 0 }, finished = false, log = emptyList(),
        )
        return startRound(empty)
    }

    /** Everyone goes down with a freshly shuffled deck, and the first card turns up. */
    private fun startRound(state: CaveState): CaveState {
        val round = state.round + 1
        val random = Random(state.seed xor round * -0x61c8864680b583ebL)
        val started = state.copy(
            round = round,
            deck = CaveDeck.deck(state.removed).shuffled(random),
            path = emptyList(),
            inside = List(state.players) { true },
            choices = List(state.players) { null },
            pack = List(state.players) { 0 },
            log = state.log + CaveEvent(CaveEventKind.ROUND, round),
        )
        return reveal(started)
    }

    override fun toAct(state: CaveState): Seat? = state.deciding.firstOrNull()

    override fun actors(state: CaveState): List<Seat> = state.deciding

    override fun apply(state: CaveState, seat: Seat, action: CaveAction): CaveState {
        if (state.finished) throw IllegalActionException("The game is over")
        if (seat !in state.inside.indices || !state.inside[seat]) throw IllegalActionException("Seat $seat is not in the cave")
        if (state.choices[seat] != null) throw IllegalActionException("Seat $seat has already chosen")
        val chosen = state.copy(choices = state.choices.mapIndexed { s, c -> if (s == seat) action else c })
        return if (chosen.deciding.isEmpty()) settle(chosen) else chosen
    }

    /** Everyone inside has chosen: those going back share out the path and leave, then the next card turns up. */
    private fun settle(state: CaveState): CaveState {
        val leaving = state.inside.indices.filter { state.inside[it] && state.choices[it] == CaveAction.Back }
        var next = state.copy(choices = List(state.players) { null })
        if (leaving.isNotEmpty()) {
            val n = leaving.size
            val share = state.path.sumOf { it.left / n }
            val log = leaving.map { CaveEvent(CaveEventKind.BACK, state.round, it, amount = state.pack[it] + share, count = share) }
            next = next.copy(
                path = state.path.map { it.copy(left = it.left % n) },
                inside = state.inside.mapIndexed { s, inside -> inside && s !in leaving },
                chest = state.chest.mapIndexed { s, c -> if (s in leaving) c + state.pack[s] + share else c },
                pack = state.pack.mapIndexed { s, p -> if (s in leaving) 0 else p },
                log = state.log + log,
            )
        }
        return if (next.inside.none { it }) endRound(next) else reveal(next)
    }

    /** The next card turns up for those inside; with none left, they all come back out safely. */
    private fun reveal(state: CaveState): CaveState {
        val inside = state.inside.indices.filter { state.inside[it] }
        val card = state.deck.firstOrNull()
        if (card == null) {
            val log = listOf(CaveEvent(CaveEventKind.EMPTY, state.round, count = inside.size)) +
                inside.map { CaveEvent(CaveEventKind.BACK, state.round, it, amount = state.pack[it]) }
            return endRound(
                state.copy(
                    inside = List(state.players) { false },
                    chest = state.chest.mapIndexed { s, c -> c + state.pack[s] },
                    pack = List(state.players) { 0 },
                    log = state.log + log,
                ),
            )
        }
        val rest = state.deck.drop(1)
        return when (card) {
            is CaveCard.Vein -> {
                val each = card.value / inside.size
                state.copy(
                    deck = rest,
                    path = state.path + PathCard(card, card.value % inside.size),
                    pack = state.pack.mapIndexed { s, p -> if (s in inside) p + each else p },
                    log = state.log + CaveEvent(CaveEventKind.REVEAL, state.round, card = card, amount = each, count = inside.size),
                )
            }
            is CaveCard.Danger -> {
                val revealed = state.copy(
                    deck = rest,
                    path = state.path + PathCard(card),
                    log = state.log + CaveEvent(CaveEventKind.REVEAL, state.round, card = card),
                )
                if (state.path.none { (it.card as? CaveCard.Danger)?.hazard == card.hazard }) return revealed
                val log = listOf(CaveEvent(CaveEventKind.COLLAPSE, state.round, card = card, count = inside.size)) +
                    inside.map { CaveEvent(CaveEventKind.LOST, state.round, it, amount = state.pack[it]) }
                endRound(
                    revealed.copy(
                        removed = state.removed + card.hazard,
                        inside = List(state.players) { false },
                        pack = List(state.players) { 0 },
                        log = revealed.log + log,
                    ),
                )
            }
        }
    }

    private fun endRound(state: CaveState): CaveState =
        if (state.round >= state.rules.rounds) state.copy(finished = true, choices = List(state.players) { null }) else startRound(state)

    override fun result(state: CaveState): GameResult? = state.result

    override val mayHarm: Boolean get() = true

    /** Whoever was inside when a creeper went off. */
    override fun harmed(before: CaveState, after: CaveState): List<Seat> {
        val events = after.log.drop(before.log.size)
        val blast = events.indexOfFirst { it.kind == CaveEventKind.COLLAPSE && (it.card as? CaveCard.Danger)?.hazard == Hazard.CREEPER }
        if (blast < 0) return emptyList()
        return events.drop(blast + 1).takeWhile { it.kind == CaveEventKind.LOST }.map { it.seat }
    }

    override fun view(state: CaveState, seat: Seat?): CaveView = CaveView.of(state, seat, open = false)

    override fun openView(state: CaveState, seat: Seat?): CaveView = CaveView.of(state, seat, open = true)

    override fun createBot(level: BotLevel, seed: Long): Bot<CaveView, CaveAction> = CaveBot(level, seed)

    override fun guide(rules: CaveRules?): List<GuideSection> = CaveGuide.sections(rules)
}
