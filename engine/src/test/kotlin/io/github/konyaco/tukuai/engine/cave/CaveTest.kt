package io.github.konyaco.tukuai.engine.cave

import io.github.konyaco.tukuai.engine.cave.bot.CaveBot
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.Match
import io.github.konyaco.tukuai.engine.core.Replay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CaveTest {
    private fun vein(value: Int): CaveCard = CaveDeck.VEINS.first { it.value == value }

    private fun danger(hazard: Hazard, copy: Int = 0): CaveCard = CaveDeck.DANGERS.first { it.hazard == hazard && it.copy == copy }

    /** A round set up by hand: [deck] still to turn up, [path] already out, everyone inside. */
    private fun state(
        players: Int = 3,
        deck: List<CaveCard>,
        path: List<PathCard> = emptyList(),
        pack: List<Int> = List(players) { 0 },
        chest: List<Int> = List(players) { 0 },
        round: Int = 1,
        rules: CaveRules = CaveRules(players = players),
    ) = CaveState(
        rules = rules, seed = 5, round = round, deck = deck, removed = emptyList(), path = path,
        inside = List(players) { true }, choices = List(players) { null }, pack = pack, chest = chest,
        finished = false, log = emptyList(),
    )

    private fun CaveState.all(vararg actions: CaveAction): CaveState =
        actions.withIndex().fold(this) { s, (seat, action) -> if (s.inside[seat] && s.choices[seat] == null) Cave.apply(s, seat, action) else s }

    @Test
    fun `the deck has four ores of four cards and four hazards of three`() {
        assertEquals(28, CaveDeck.ALL.size)
        assertEquals(16, CaveDeck.VEINS.size)
        assertEquals(12, CaveDeck.DANGERS.size)
        assertEquals(CaveDeck.ALL.size, CaveDeck.ALL.toSet().size)
        assertEquals(26, CaveDeck.deck(listOf(Hazard.LAVA, Hazard.LAVA)).size)
        assertEquals(1, CaveDeck.deck(listOf(Hazard.LAVA, Hazard.LAVA)).count { it is CaveCard.Danger && it.hazard == Hazard.LAVA })
    }

    @Test
    fun `a game starts with the first card up and everyone choosing at once`() {
        val s = Cave.start(CaveRules.CLASSIC, 42)
        assertEquals(1, s.round)
        assertEquals(1, s.path.size)
        assertEquals(27, s.deck.size)
        assertEquals(listOf(0, 1, 2, 3), Cave.actors(s))
        assertEquals(0, Cave.toAct(s))
    }

    @Test
    fun `a vein is shared evenly and the rest stays on the card`() {
        val after = state(deck = listOf(vein(10), danger(Hazard.LAVA))).all(CaveAction.Deeper, CaveAction.Deeper, CaveAction.Deeper)
        assertEquals(listOf(3, 3, 3), after.pack)
        assertEquals(PathCard(vein(10), 1), after.path.single())
        assertEquals(listOf(0, 1, 2), Cave.actors(after))
    }

    @Test
    fun `the rule book's example shares a seven among three as two each with one left`() {
        val shared = CaveGuide.SHARED as CaveCard.Vein
        assertEquals(7, shared.value)
        assertEquals(3, CaveGuide.SHARED_AMONG)
        val after = state(deck = listOf(shared)).all(CaveAction.Deeper, CaveAction.Deeper, CaveAction.Deeper)
        assertEquals(listOf(2, 2, 2), after.pack)
        assertEquals(1, after.path.single().left)
    }

    @Test
    fun `nobody sees what the others chose until everyone has`() {
        val s = Cave.apply(state(deck = listOf(vein(10))), 1, CaveAction.Back)
        val view = Cave.view(s, 0)
        assertEquals(listOf(false, true, false), view.decided)
        assertNull(view.choices[1])
        assertEquals(CaveAction.Back, Cave.view(s, 1).myChoice)
        assertEquals(CaveAction.Back, Cave.openView(s, 0).choices[1])
        assertEquals(listOf(0, 2), Cave.actors(s))
        assertThrows<IllegalActionException> { Cave.apply(s, 1, CaveAction.Deeper) }
    }

    @Test
    fun `going back shares out the path among those leaving together`() {
        val path = listOf(PathCard(vein(5), 2), PathCard(danger(Hazard.GRAVEL)), PathCard(vein(9), 1))
        val s = state(deck = listOf(vein(6), vein(7)), path = path, pack = listOf(4, 4, 4))
        val after = s.all(CaveAction.Back, CaveAction.Back, CaveAction.Deeper)
        // Two leave: the 2 on the five splits, the 1 on the nine stays.
        assertEquals(listOf(5, 5, 0), after.chest)
        assertEquals(listOf(false, false, true), after.inside)
        assertEquals(listOf(0, 0, 1), after.path.take(3).map { it.left })
        // The one left inside gets the whole six that turns up next.
        assertEquals(10, after.pack[2])
        assertEquals(listOf(2), Cave.actors(after))
        // Alone, they take everything on the path when they go.
        val home = Cave.apply(after, 2, CaveAction.Back)
        assertEquals(10 + 1, home.chest[2])
        assertEquals(2, home.round)
    }

    @Test
    fun `a second hazard of a kind ends the round and those inside drop what they carry`() {
        val s = state(
            deck = listOf(danger(Hazard.CREEPER, 1), vein(3)),
            path = listOf(PathCard(danger(Hazard.CREEPER, 0)), PathCard(vein(8), 2)),
            pack = listOf(2, 2, 2),
        )
        val after = s.all(CaveAction.Back, CaveAction.Deeper, CaveAction.Deeper)
        assertEquals(listOf(2 + 2, 0, 0), after.chest)
        assertEquals(2, after.round)
        assertEquals(listOf(Hazard.CREEPER), after.removed)
        assertEquals(27 - 1, after.deck.size)
        val lost = after.log.filter { it.kind == CaveEventKind.LOST }
        assertEquals(listOf(1, 2), lost.map { it.seat })
        assertEquals(listOf(1, 2), Cave.harmed(s, after))
    }

    @Test
    fun `only a creeper hurts anyone`() {
        val s = state(deck = listOf(danger(Hazard.LAVA, 1)), path = listOf(PathCard(danger(Hazard.LAVA, 0))))
        val after = s.all(CaveAction.Deeper, CaveAction.Deeper, CaveAction.Deeper)
        assertEquals(listOf(Hazard.LAVA), after.removed)
        assertEquals(emptyList<Int>(), Cave.harmed(s, after))
    }

    @Test
    fun `different hazards do no harm together`() {
        val s = state(deck = listOf(danger(Hazard.SPIDER), vein(2)), path = listOf(PathCard(danger(Hazard.LAVA))))
        val after = s.all(CaveAction.Deeper, CaveAction.Deeper, CaveAction.Deeper)
        assertEquals(1, after.round)
        assertTrue(after.inside.all { it })
    }

    @Test
    fun `when the deck runs out everyone inside comes back with what they carry`() {
        val s = state(deck = emptyList(), pack = listOf(3, 3, 3), rules = CaveRules(players = 3, rounds = 1))
        val after = s.all(CaveAction.Deeper, CaveAction.Back, CaveAction.Deeper)
        assertEquals(listOf(3, 3, 3), after.chest)
        assertTrue(after.finished)
    }

    @Test
    fun `the richest win, ties share, and the points add up to nothing`() {
        val one = CaveScoring.result(listOf(10, 30, 20, 5))
        assertEquals(setOf(1), one.winners)
        assertEquals(listOf(-1, 3, -1, -1), one.scores)
        val two = CaveScoring.result(listOf(30, 30, 20))
        assertEquals(setOf(0, 1), two.winners)
        assertEquals(listOf(1, 1, -2), two.scores)
        assertEquals(0, CaveScoring.result(listOf(7, 7)).scores.sum())
    }

    @Test
    fun `the danger is the share of face down cards that match a hazard already out`() {
        val s = state(deck = emptyList(), path = listOf(PathCard(danger(Hazard.GRAVEL)), PathCard(vein(4))))
        val view = Cave.view(s, 0)
        assertEquals(26, view.unseen().size)
        assertEquals(2.0 / 26, view.danger(), 1e-9)
    }

    @Test
    fun `the view and the actions survive the wire`() {
        var s = Cave.start(CaveRules.SIX, 7)
        s = Cave.apply(s, 3, CaveAction.Back)
        for (seat in listOf(0, 1, 5)) assertEquals(Cave.view(s, seat), CaveCodecs.VIEW.decode(CaveCodecs.VIEW.encode(Cave.view(s, seat))))
        assertEquals(Cave.openView(s, null), CaveCodecs.VIEW.decode(CaveCodecs.VIEW.encode(Cave.openView(s, null))))
        for (action in listOf(CaveAction.Deeper, CaveAction.Back)) assertEquals(action, CaveCodecs.ACTION.decode(CaveCodecs.ACTION.encode(action)))
        assertEquals(CaveRules.EIGHT, CaveRules.CODEC.decode(CaveRules.CODEC.encode(CaveRules.EIGHT)))
    }

    @Test
    fun `computers finish every kind of table, and a replay plays the game again`() {
        for (preset in CaveRules.PRESETS) for (level in BotLevel.entries) for (seed in 0L until 20L) {
            val match = Match(Cave, preset.rules, seed)
            var moves = 0
            while (match.result == null) {
                val actors = match.actors
                assertTrue(actors.isNotEmpty())
                // Several decide at once; take them out of order to show the order does not matter.
                match.playBot(if (moves % 2 == 0) actors.last() else actors.first(), level)
                moves++
                assertTrue(moves < 2000, "${preset.id} $level $seed never ended")
            }
            val result = match.result!!
            assertEquals(0, result.scores.sum())
            assertEquals(preset.rules.rounds, match.state.round)
            val replay = Replay.decode(match.replay(List(preset.rules.players) { "P$it" }).encode())
            assertEquals(match.state, replay.rebuild(Cave).state)
        }
    }

    @Test
    fun `a computer never turns back while nothing can go wrong`() {
        val s = state(deck = listOf(vein(5)), path = listOf(PathCard(vein(16), 1)), pack = listOf(30, 5, 5))
        for (level in BotLevel.entries) for (seed in 0L until 30L) {
            assertEquals(CaveAction.Deeper, CaveBot(level, seed).act(Cave.view(s, 0)))
        }
    }

    @Test
    fun `a sensible computer turns back with a full pack in a dangerous cave`() {
        val path = listOf(
            PathCard(danger(Hazard.LAVA)), PathCard(danger(Hazard.CREEPER)), PathCard(danger(Hazard.SPIDER)),
            PathCard(danger(Hazard.GRAVEL)), PathCard(vein(2)),
        )
        val s = state(deck = listOf(vein(5)), path = path, pack = listOf(40, 5, 5))
        for (level in listOf(BotLevel.NORMAL, BotLevel.HARD)) {
            assertEquals(CaveAction.Back, CaveBot(level, 1).act(Cave.view(s, 0)))
        }
        assertFalse(Cave.view(s, 0).danger() == 0.0)
    }
}
