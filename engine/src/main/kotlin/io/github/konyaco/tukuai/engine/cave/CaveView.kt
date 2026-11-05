package io.github.konyaco.tukuai.engine.cave

import io.github.konyaco.tukuai.engine.core.Seat

/**
 * What one seat can see: the path and everything on it, who is inside and what each player
 * carries and has brought out, and who has chosen already, but not what the others chose
 * until everyone has. Nobody sees the order of the deck. Clients draw the table from this
 * and computers decide from it.
 */
data class CaveView(
    /** The seat this view is for, or null for a spectator. */
    val seat: Seat?,
    val rules: CaveRules,
    val round: Int,
    /** Cards still face down this round. */
    val deckLeft: Int,
    val removed: List<Hazard>,
    val path: List<PathCard>,
    val inside: List<Boolean>,
    /** Who has chosen already for the card on top of the path. */
    val decided: List<Boolean>,
    /** What was chosen: this seat's own choice, or in a replay everyone's; null where unknown or not yet made. */
    val choices: List<CaveAction?>,
    val pack: List<Int>,
    val chest: List<Int>,
    val finished: Boolean,
    val log: List<CaveEvent>,
) {
    val players: Int get() = inside.size

    /** The players inside who still have to choose. */
    val deciding: List<Seat> get() = if (finished) emptyList() else inside.indices.filter { inside[it] && !decided[it] }

    /** Whether this seat is inside and has yet to choose. */
    val isMyTurn: Boolean get() = seat != null && seat in deciding

    val myChoice: CaveAction? get() = seat?.let { choices.getOrNull(it) }

    /** Emeralds lying on the path, for whoever goes back next. */
    val leftover: Int get() = path.sumOf { it.left }

    /** The hazards that have turned up once this round, so a second one would end it. */
    val warned: Set<Hazard> get() = path.mapNotNull { (it.card as? CaveCard.Danger)?.hazard }.toSet()

    /** The cards still face down this round, in no particular order: the round's deck less what turned up. */
    fun unseen(): List<CaveCard> {
        val turned = path.map { it.card }.toSet()
        return CaveDeck.deck(removed).filter { it !in turned }
    }

    /** The chance that the next card ends the round. */
    fun danger(): Double {
        val unseen = unseen()
        if (unseen.isEmpty()) return 0.0
        val warned = warned
        return unseen.count { it is CaveCard.Danger && it.hazard in warned }.toDouble() / unseen.size
    }

    val winners: Set<Seat> get() = if (finished) CaveScoring.result(chest).winners else emptySet()

    /** The events of the round being played, starting with its start. */
    val roundLog: List<CaveEvent>
        get() = log.subList(log.indexOfLast { it.kind == CaveEventKind.ROUND }.coerceAtLeast(0), log.size)

    companion object {
        fun of(state: CaveState, seat: Seat?, open: Boolean): CaveView = CaveView(
            seat = seat,
            rules = state.rules,
            round = state.round,
            deckLeft = state.deck.size,
            removed = state.removed,
            path = state.path,
            inside = state.inside,
            decided = state.choices.map { it != null },
            choices = state.choices.mapIndexed { s, c -> if (open || s == seat) c else null },
            pack = state.pack,
            chest = state.chest,
            finished = state.finished,
            log = state.log,
        )
    }
}
