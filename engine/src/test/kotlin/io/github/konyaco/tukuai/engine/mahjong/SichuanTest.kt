package io.github.konyaco.tukuai.engine.mahjong

import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.Match
import io.github.konyaco.tukuai.engine.core.Replay
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScAction
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScCodecs
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScEventKind
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScFan
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScGuide
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScPhase
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScRules
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScScoring
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScState
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScView
import io.github.konyaco.tukuai.engine.mahjong.sichuan.SichuanMahjong
import io.github.konyaco.tukuai.engine.mahjong.sichuan.SwapDirection
import io.github.konyaco.tukuai.engine.mahjong.sichuan.WinContext
import io.github.konyaco.tukuai.engine.mahjong.sichuan.bot.ScBot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SichuanTest {
    private val game = SichuanMahjong
    private fun t(text: String) = Tiles.parse(text)
    private fun tile(text: String) = t(text).single()

    /**
     * A hand in play: [turn] has just drawn [drawn] (already in their hand), nobody has
     * claimed yet, and everyone is void in bamboo unless [voids] says otherwise.
     */
    private fun state(
        hands: List<String>,
        wall: String = "1s 1s 2s 2s 3s 3s 4s 4s 6s 6s 7s 7s 8s 8s",
        turn: Seat = 0,
        drawn: String? = null,
        voids: List<TileSuit> = List(4) { TileSuit.BAMBOO },
        melds: List<List<Meld>> = List(4) { emptyList() },
        rules: ScRules = ScRules.BLOOD,
        fresh: Boolean = false,
        dealer: Seat = 0,
    ): ScState = ScState(
        rules = rules, seed = 0, dealer = dealer, wall = Wall(t(wall)),
        hands = hands.map { t(it).sorted() }, melds = melds, discards = List(4) { emptyList() },
        voids = voids, swaps = List(4) { emptyList() }, received = List(4) { emptyList() },
        swapDirection = SwapDirection.NEXT, phase = ScPhase.PLAY, turn = turn,
        drawn = drawn?.let(::tile), claimTile = null, pending = emptyList(), answers = emptyList(),
        replacement = false, kongDiscard = false, fresh = fresh, played = List(4) { !fresh },
        wins = emptyList(), scores = List(4) { 0 }, log = emptyList(),
    )

    private fun ScState.act(seat: Seat, action: ScAction) = game.apply(this, seat, action)

    // ---- Dealing -------------------------------------------------------------------------------

    @Test
    fun `the deal gives thirteen each and the dealer fourteen, from 108 tiles`() {
        for (seed in 0L until 20L) {
            val s = game.start(ScRules.BLOOD, seed)
            assertEquals(108, s.wall.tiles.size)
            assertEquals(Tiles.counts(Tiles.set(TileSuit.NUMBERED)).toList(), Tiles.counts(s.wall.tiles).toList())
            assertEquals(List(4) { if (it == s.dealer) 14 else 13 }, s.hands.map { it.size })
            assertEquals(108 - 53, s.wall.remaining)
            assertEquals(ScPhase.SWAP, s.phase)
            assertEquals(s.dealer, game.toAct(s))
            assertTrue(s.hands.flatten().all { it.suit.numbered })
        }
        assertEquals(ScPhase.VOID, game.start(ScRules.NO_SWAP, 1).phase)
    }

    @Test
    fun `everyone passes three tiles of one suit the same way, then picks a void suit`() {
        var s = game.start(ScRules.BLOOD, 7)
        val before = s.hands
        val picks = HashMap<Seat, List<Tile>>()
        repeat(4) {
            val seat = game.toAct(s)!!
            val suit = TileSuit.NUMBERED.first { suit -> s.hands[seat].count { it.suit == suit } >= 3 }
            val pick = s.hands[seat].filter { it.suit == suit }.take(3)
            assertThrows(IllegalActionException::class.java) { s.act(seat, ScAction.Swap(pick.take(2))) }
            val mixed = s.hands[seat].filter { it.suit != suit }.take(1) + pick.take(2)
            assertThrows(IllegalActionException::class.java) { s.act(seat, ScAction.Swap(mixed)) }
            picks[seat] = pick
            s = s.act(seat, ScAction.Swap(pick))
            // Nobody sees what the others picked, nor their own new tiles, before everyone has.
            if (it < 3) assertEquals(emptyList<Tile>(), game.view(s, (seat + 1) % 4).got)
        }
        assertEquals(ScPhase.VOID, s.phase)
        for (seat in 0 until 4) {
            val from = (0 until 4).single { s.swapDirection.target(it) == seat }
            assertEquals(picks.getValue(from).sorted(), s.received[seat])
            assertEquals((Tiles.minus(before[seat], picks.getValue(seat)) + picks.getValue(from)).sorted(), s.hands[seat])
            assertEquals(picks.getValue(from).sorted(), game.view(s, seat).got)
        }
        repeat(4) {
            val seat = game.toAct(s)!!
            s = s.act(seat, ScAction.ChooseVoid(TileSuit.entries[seat % 3]))
            if (it < 3) {
                val other = game.view(s, (seat + 1) % 4)
                assertNull(other.voids[seat], "a void suit stays hidden until everyone has picked")
            }
        }
        assertEquals(ScPhase.PLAY, s.phase)
        assertEquals(s.dealer, game.toAct(s))
        assertEquals(TileSuit.entries[1], game.view(s, 0).voids[1])
    }

    // ---- The void suit -------------------------------------------------------------------------

    @Test
    fun `the void suit goes first and blocks winning`() {
        // Seat 0 holds one bamboo and is void in bamboo.
        val s = state(listOf("123m 456m 789m 11p 23p 5s", "1m", "1m", "1m"), drawn = "5s")
        assertThrows(IllegalActionException::class.java) { s.act(0, ScAction.Discard(tile("1m"))) }
        assertEquals(listOf(tile("5s")), game.view(s, 0).discardable)
        val after = s.act(0, ScAction.Discard(tile("5s")))
        assertEquals(t("5s"), after.discards[0])
        // A complete hand with a void tile in it is no win.
        val holding = state(listOf("123m 456m 789m 111p 5s 5s", "1m", "1m", "1m"), turn = 0, drawn = "5s")
        assertFalse(game.view(holding, 0).canWin)
        assertThrows(IllegalActionException::class.java) { holding.act(0, ScAction.Win) }
        assertFalse(ScScoring.isWin(t("123m 456m 789m 111p 55s"), emptyList(), TileSuit.BAMBOO))
        assertTrue(ScScoring.isWin(t("123m 456m 789m 111p 55s"), emptyList(), TileSuit.DOTS).not())
        assertTrue(ScScoring.isWin(t("123m 456m 789m 111p 55p"), emptyList(), TileSuit.BAMBOO))
        // Nor can a player claim their void suit.
        val claim = state(listOf("123m 456m 789m 11p 9s 2s 3p", "33p 1m 2m", "1m", "1m"), drawn = "3p",
            voids = listOf(TileSuit.DOTS, TileSuit.DOTS, TileSuit.BAMBOO, TileSuit.BAMBOO))
        val discarded = claim.act(0, ScAction.Discard(tile("3p")))
        assertEquals(ScPhase.PLAY, discarded.phase, "nobody may claim")
    }

    // ---- Claims --------------------------------------------------------------------------------

    @Test
    fun `a win beats a pung, and several players may win on one discard`() {
        val s = state(
            listOf("123m 456m 789m 11m 5p 9p", "55p 1m 2m 3m 4m 6m 7m 8m 9m 9m 9m 2p", "123m 456m 789m 4p 6p 7p 7p", "123m 456m 789m 22p 46p"),
            drawn = "9p",
        )
        val d = s.act(0, ScAction.Discard(tile("5p")))
        assertEquals(ScPhase.CLAIM, d.phase)
        assertEquals(listOf(1, 2, 3), d.pending)
        assertEquals(listOf(ClaimKind.PUNG), game.view(d, 1).options)
        val a = d.act(1, ScAction.Pung)
        assertEquals(2, game.toAct(a))
        assertEquals(listOf(ClaimKind.WIN), game.view(a, 2).options)
        val b = a.act(2, ScAction.Win)
        val c = b.act(3, ScAction.Win)
        assertEquals(listOf(2, 3), c.wins.map { it.seat })
        assertEquals(emptyList<Meld>(), c.melds[1], "the pung lost to the wins")
        assertEquals(listOf(-2, 0, 1, 1), c.scores)
        // Play goes on after the last winner, with the next player still in.
        assertEquals(ScPhase.PLAY, c.phase)
        assertEquals(0, c.turn)
        assertEquals(emptyList<Tile>(), c.discards[0], "the winners took the discard")
        assertEquals(0, c.scores.sum())
    }

    @Test
    fun `a player who passes a win lets a pung go through, and winners claim no more`() {
        val s = state(
            listOf("123m 456m 789m 11m 5p 9p", "55p 1m 2m 3m 4m 6m 7m 8m 9m 9m 9m 2p", "123m 456m 789m 4p 6p 7p 7p", "1m"),
            drawn = "9p",
        )
        val d = s.act(0, ScAction.Discard(tile("5p")))
        val c = d.act(1, ScAction.Pung).act(2, ScAction.Pass)
        assertEquals(listOf(Meld(MeldKind.PUNG, tile("5p"), 0)), c.melds[1])
        assertEquals(ScPhase.PLAY, c.phase)
        assertEquals(1, c.turn)
        assertNull(c.drawn, "a pung draws nothing")
        assertEquals(11, c.hands[1].size)
        // A player who has won is not asked again.
        val won = s.copy(wins = listOf(io.github.konyaco.tukuai.engine.mahjong.sichuan.ScWin(2, tile("1m"), null, emptyList(), 0, 1, List(4) { 0 })))
        val again = won.act(0, ScAction.Discard(tile("5p")))
        assertEquals(listOf(1), again.pending)
        // Nor can the pung be refused when it is not there to make.
        assertThrows(IllegalActionException::class.java) { d.act(1, ScAction.Win) }
    }

    @Test
    fun `an exposed kong takes three from the hand, pays two from the discarder and draws from the back`() {
        val s = state(listOf("123m 456m 789m 11m 5p 9p", "555p 1m 2m 3m 4m 6m 7m 8m 9m 9m 2p", "1m", "1m"), drawn = "9p", wall = "1p 2p 3p 4p")
        val d = s.act(0, ScAction.Discard(tile("5p")))
        assertEquals(listOf(ClaimKind.KONG, ClaimKind.PUNG), game.view(d, 1).options)
        val k = d.act(1, ScAction.Kong(tile("5p")))
        assertEquals(listOf(Meld(MeldKind.EXPOSED_KONG, tile("5p"), 0)), k.melds[1])
        assertEquals(listOf(-2, 2, 0, 0), k.scores)
        assertEquals(tile("4p"), k.drawn, "the replacement comes off the back")
        assertTrue(k.replacement)
        assertEquals(1, k.turn)
        assertEquals(1, k.wall.drawnBack)
    }

    @Test
    fun `a concealed kong pays two from everyone still in, an added kong one`() {
        val s = state(listOf("5555p 123m 456m 789m 9p", "1m", "1m", "1m"), drawn = "9p", wall = "1p 2p 3p 4p")
        val k = s.act(0, ScAction.Kong(tile("5p")))
        assertEquals(listOf(6, -2, -2, -2), k.scores)
        assertEquals(listOf(Meld(MeldKind.CONCEALED_KONG, tile("5p"))), k.melds[0])
        // Winners pay nothing more.
        val won = s.copy(wins = listOf(io.github.konyaco.tukuai.engine.mahjong.sichuan.ScWin(3, tile("1m"), null, emptyList(), 0, 1, List(4) { 0 })))
        assertEquals(listOf(4, -2, -2, 0), won.act(0, ScAction.Kong(tile("5p"))).scores)

        val added = state(listOf("5p 123m 456m 789m 9p", "1m", "1m", "1m"), drawn = "5p", melds = listOf(listOf(Meld(MeldKind.PUNG, tile("5p"), 2))) + List(3) { emptyList() })
        assertEquals(listOf(tile("5p")), game.view(added, 0).kongs)
        val a = added.act(0, ScAction.Kong(tile("5p")))
        assertEquals(listOf(3, -1, -1, -1), a.scores)
        assertEquals(MeldKind.ADDED_KONG, a.melds[0].single().kind)
        assertEquals(ScPhase.PLAY, a.phase)
        // No kong without a tile left to replace it.
        assertEquals(emptyList<Tile>(), game.view(added.copy(wall = Wall(emptyList())), 0).kongs)
    }

    @Test
    fun `a kong may be robbed by a player who wins on its fourth tile`() {
        val pung = listOf(listOf(Meld(MeldKind.PUNG, tile("5p"), 2))) + List(3) { emptyList<Meld>() }
        val s = state(listOf("5p 123m 456m 789m 9p", "123m 456m 789m 46p 99m", "1m", "1m"), drawn = "5p", melds = pung)
        val k = s.act(0, ScAction.Kong(tile("5p")))
        assertEquals(ScPhase.ROB, k.phase)
        assertEquals(listOf(1), k.pending)
        val robbed = k.act(1, ScAction.Win)
        val win = robbed.wins.single()
        assertEquals(1, win.seat)
        assertEquals(0, win.from)
        assertTrue(ScFan.ROB_KONG in win.fans)
        assertEquals(MeldKind.PUNG, robbed.melds[0].single().kind, "a robbed kong stays a pung")
        assertFalse(tile("5p") in robbed.hands[0])
        assertEquals(listOf(-2, 2, 0, 0), robbed.scores)
        // Passing lets the kong stand.
        val stood = k.act(1, ScAction.Pass)
        assertEquals(MeldKind.ADDED_KONG, stood.melds[0].single().kind)
        assertEquals(listOf(3, -1, -1, -1), stood.scores)
        assertEquals(0, stood.turn)
    }

    // ---- Fan -----------------------------------------------------------------------------------

    @Test
    fun `every hand in the rule book is worth what it says`() {
        for (hand in ScGuide.HANDS) {
            val tiles = hand.groups.flatten()
            assertTrue(ScScoring.isWin(tiles, emptyList(), TileSuit.BAMBOO.takeIf { tiles.none { it.suit == TileSuit.BAMBOO } } ?: TileSuit.CHARACTERS.takeIf { tiles.none { it.suit == TileSuit.CHARACTERS } } ?: TileSuit.DOTS), hand.key)
            assertEquals(hand.fans, ScScoring.fans(ScRules.BLOOD, tiles, emptyList(), WinContext.PLAIN), hand.key)
        }
        assertEquals(0, ScScoring.total(ScRules.BLOOD, emptyList()))
        assertEquals(3, ScScoring.total(ScRules.BLOOD, listOf(ScFan.DRAGON_SEVEN_PAIRS)))
    }

    @Test
    fun `fan add up, and points double with each up to the limit`() {
        val r = ScRules.BLOOD
        // All pungs in one suit with a kong: 1 + 2 + 1 gen.
        val kong = listOf(Meld(MeldKind.EXPOSED_KONG, tile("2p"), 1))
        val fans = ScScoring.fans(r, t("555p 777p 999p 33p"), kong, WinContext.PLAIN)
        assertEquals(listOf(ScFan.ALL_PUNGS, ScFan.GEN, ScFan.FULL_FLUSH), fans)
        assertEquals(4, ScScoring.total(r, fans))
        assertEquals(8, r.points(4), "capped at 3 fan")
        assertEquals(16, ScRules(maxFan = 0).points(4))
        assertEquals(16, ScRules(maxFan = 4).points(5))
        // A pung with its fourth tile still in hand is a gen too.
        val pung = listOf(Meld(MeldKind.PUNG, tile("2p"), 1))
        assertEquals(listOf(ScFan.GEN), ScScoring.fans(r, t("2p 345m 678m 99m 55m"), pung, WinContext.PLAIN))
        // Dragon seven pairs with two fours: the second is another gen.
        assertEquals(listOf(ScFan.DRAGON_SEVEN_PAIRS, ScFan.GEN, ScFan.FULL_FLUSH), ScScoring.fans(r, t("1111m 2222m 33m 44m 55m"), emptyList(), WinContext.PLAIN))
        // Seven pairs in one suit.
        assertEquals(listOf(ScFan.SEVEN_PAIRS, ScFan.FULL_FLUSH), ScScoring.fans(r, t("11223344556677m"), emptyList(), WinContext.PLAIN))
        // Heavenly hands count the most there is, only when the table allows them.
        assertEquals(3, ScScoring.total(r, ScScoring.fans(r, t("123m 456m 789m 11p 234p"), emptyList(), WinContext(heavenly = true))))
        assertEquals(5, ScScoring.total(ScRules(maxFan = 0), listOf(ScFan.EARTHLY)))
        assertEquals(emptyList<ScFan>(), ScScoring.fans(ScRules(heavenly = false), t("123m 456m 789m 11p 234p"), emptyList(), WinContext(heavenly = true)))
    }

    @Test
    fun `a self-drawn win takes the hand and one more from everyone still in`() {
        val s = state(listOf("123m 456m 789m 11p 23p 4p", "1m", "1m", "1m"), drawn = "4p")
        assertTrue(game.view(s, 0).canWin)
        val w = s.act(0, ScAction.Win)
        assertEquals(listOf(6, -2, -2, -2), w.scores)
        assertTrue(w.wins.single().selfDrawn)
        assertEquals(1, w.turn, "play goes on after the winner")
        assertEquals(t("123m 456m 789m 11p 234p").sorted(), w.hands[0])
        assertEquals(t("123m 456m 789m 11p 234p").sorted(), game.view(w, 2).shown[0], "a winner's hand lies face up")
    }

    @Test
    fun `kong bloom, kong discard, last tile, heavenly and earthly hands`() {
        // A concealed kong whose replacement from the back completes the hand.
        val bloom = state(listOf("5555m 123m 789m 99p 2p 3p", "1m", "1m", "1m"), drawn = "3p", wall = "1s 4p")
        val k = bloom.act(0, ScAction.Kong(tile("5m")))
        assertEquals(tile("4p"), k.drawn)
        assertTrue(k.replacement)
        assertTrue(game.view(k, 0).canWin)
        val bloomed = k.act(0, ScAction.Win).wins.single()
        assertEquals(listOf(ScFan.GEN, ScFan.KONG_BLOOM), bloomed.fans, "the kong is a gen too")

        // Discarding right after a kong: whoever wins on it gets a fan more.
        val shoot = state(listOf("5555m 123m 789m 99p 7p 8p", "123m 456m 789m 68p 11m", "1m", "1m"), drawn = "8p", wall = "1s 7p")
        val discarded = shoot.act(0, ScAction.Kong(tile("5m"))).act(0, ScAction.Discard(tile("7p")))
        assertTrue(discarded.kongDiscard)
        assertEquals(listOf(1), discarded.pending)
        assertEquals(listOf(ScFan.KONG_DISCARD), discarded.act(1, ScAction.Win).wins.single().fans)

        // The last tile of the wall.
        val last = state(listOf("123m 456m 789m 11p 23p 4p", "1m", "1m", "1m"), drawn = "4p", wall = "")
        assertEquals(listOf(ScFan.LAST_TILE), last.act(0, ScAction.Win).wins.single().fans)

        // Heavenly: the dealer wins on the hand dealt.
        val heaven = state(listOf("123m 456m 789m 11p 234p", "1m", "1m", "1m"), drawn = null, fresh = true)
        assertTrue(game.view(heaven, 0).canWin)
        val hw = heaven.act(0, ScAction.Win)
        assertEquals(listOf(ScFan.HEAVENLY), hw.wins.single().fans)
        assertEquals(3, hw.wins.single().fan)
        assertEquals(listOf(27, -9, -9, -9), hw.scores)
        // Earthly: another player wins on the dealer's first discard.
        val earth = state(listOf("123m 456m 789m 11p 9m 5p 9p", "123m 456m 789m 46p 11m", "1m", "1m"), drawn = "9p", fresh = true)
        val ew = earth.act(0, ScAction.Discard(tile("5p"))).act(1, ScAction.Win)
        assertEquals(listOf(ScFan.EARTHLY), ew.wins.single().fans)
        assertEquals(-8, ew.scores[0])
        // Not after someone has claimed.
        val late = earth.copy(fresh = false).act(0, ScAction.Discard(tile("5p"))).act(1, ScAction.Win)
        assertEquals(emptyList<ScFan>(), late.wins.single().fans)
    }

    // ---- The end -------------------------------------------------------------------------------

    @Test
    fun `when the wall runs out, void holders and players not ready pay`() {
        val s = state(
            listOf(
                "123m 456m 789m 11p 23p 9p", // ready on 1p and 4p once 9p goes
                "123m 456m 789m 11p 2p 5s", // still holds bamboo, its void
                "123m 456m 789m 44p 55p", // ready on 4p and 5p, a plain hand either way
                "13579m 2468m 13p 9p", // far from ready
            ),
            drawn = "9p", wall = "",
        )
        val end = s.act(0, ScAction.Discard(tile("9p")))
        assertEquals(ScPhase.OVER, end.phase)
        assertEquals(listOf(ScEventKind.DISCARD, ScEventKind.EXHAUSTED, ScEventKind.PIG, ScEventKind.NOT_READY), end.log.map { it.kind })
        // The void holder pays the most a hand can win, 8, to each of the other three.
        val pig = end.log.first { it.kind == ScEventKind.PIG }.payments
        assertEquals(listOf(8, -24, 8, 8), pig)
        // The player far from ready pays each ready player their best hand: 1 point each for these plain hands.
        val notReady = end.log.first { it.kind == ScEventKind.NOT_READY }.payments
        assertEquals(listOf(1, 0, 1, -2), notReady)
        assertEquals(0, end.scores.sum())
        assertEquals(listOf(9, -24, 9, 6), end.scores)
        assertEquals(emptySet<Int>(), game.result(end)!!.winners)
    }

    @Test
    fun `the hand ends once three players have won`() {
        val s = state(
            listOf("123m 456m 789m 11m 5p 9p", "123m 456m 789m 46p 99m", "123m 456m 789m 4p 6p 7p 7p", "123m 456m 789m 22p 46p"),
            drawn = "9p",
        )
        val end = s.act(0, ScAction.Discard(tile("5p"))).act(1, ScAction.Win).act(2, ScAction.Win).act(3, ScAction.Win)
        assertEquals(ScPhase.OVER, end.phase)
        assertEquals(setOf(1, 2, 3), game.result(end)!!.winners)
        assertEquals(listOf(-3, 1, 1, 1), end.scores)
        assertNull(game.toAct(end))
    }

    // ---- Views, codecs and replays -------------------------------------------------------------

    @Test
    fun `a seat sees only its own tiles, and draws by others are hidden`() {
        val match = Match(game, ScRules.BLOOD, 3)
        playOut(match, List(4) { BotLevel.NORMAL }, stopAfter = 40)
        val state = match.state
        val view = game.view(state, 1)
        assertEquals(state.hands[1], view.hand)
        for (seat in listOf(0, 2, 3)) if (!state.won(seat)) assertEquals(emptyList<Tile>(), view.shown[seat])
        assertTrue(view.log.filter { it.kind == ScEventKind.DRAW && it.seat != 1 }.all { it.tile == null })
        assertTrue(view.log.filter { it.kind == ScEventKind.DRAW && it.seat == 1 }.all { it.tile != null })
        val spectator = game.view(state, null)
        assertEquals(emptyList<Tile>(), spectator.hand)
        val open = game.openView(state, null)
        assertEquals(state.hands, open.shown)
    }

    @Test
    fun `views and actions survive the wire, and replays rebuild the same hand`() {
        val match = Match(game, ScRules.BLOOD, 11)
        playOut(match, listOf(BotLevel.EASY, BotLevel.NORMAL, BotLevel.HARD, BotLevel.NORMAL))
        for (seat in listOf(null, 0, 1, 2, 3)) {
            val view = game.view(match.state, seat)
            assertEquals(view, ScCodecs.VIEW.decode(ScCodecs.VIEW.encode(view)))
        }
        for ((_, action) in match.history) assertEquals(action, ScCodecs.ACTION.decode(ScCodecs.ACTION.encode(action)))
        val rebuilt = Replay.decode(match.replay(listOf("a", "b", "c", "d")).encode()).rebuild(game)
        assertEquals(match.state, rebuilt.state)
    }

    // ---- Computers -----------------------------------------------------------------------------

    private fun playOut(match: Match<ScRules, ScState, ScAction, ScView>, levels: List<BotLevel>, stopAfter: Int = Int.MAX_VALUE) {
        val bots = levels.mapIndexed { seat, level -> ScBot(level, match.seed * 31 + seat) }
        var steps = 0
        while (match.toAct != null && steps < stopAfter) {
            val seat = match.toAct!!
            match.apply(seat, bots[seat].act(match.view(seat)))
            check(++steps < 2000) { "Hand ${match.seed} did not end" }
        }
    }

    @Test
    fun `computers at every level finish every hand, by the rules, with points adding up to zero`() {
        var wins = 0
        var exhausted = 0
        for (seed in 0L until 60L) {
            for (rules in listOf(ScRules.BLOOD, ScRules.NO_SWAP)) {
                val levels = List(4) { BotLevel.entries[((seed + it) % 3).toInt()] }
                val match = Match(game, rules, seed)
                playOut(match, levels)
                val s = match.state
                assertEquals(ScPhase.OVER, s.phase)
                val result = match.result!!
                assertEquals(0, result.scores.sum(), "seed $seed")
                assertTrue(s.wins.size <= 3)
                assertTrue(s.wins.size == 3 || s.log.any { it.kind == ScEventKind.EXHAUSTED }, "seed $seed")
                // Every tile is somewhere: hands, melds, discards or the wall, and a discard several
                // players won on is in each of their hands.
                val everywhere = s.hands.flatten() + s.melds.flatten().flatMap { it.tiles } + s.discards.flatten() +
                    s.wall.tiles.subList(s.wall.drawnFront, s.wall.tiles.size - s.wall.drawnBack)
                val shared = s.log.zipWithNext().count { (a, b) -> a.kind == ScEventKind.WIN && b.kind == ScEventKind.WIN && b.other >= 0 && a.other == b.other }
                assertEquals(108 + shared, everywhere.size, "seed $seed")
                wins += s.wins.size
                if (s.log.any { it.kind == ScEventKind.EXHAUSTED }) exhausted++
            }
        }
        assertTrue(wins > 100, "computers should win plenty of hands, won $wins")
    }

    @Test
    fun `harder computers do better`() {
        var hard = 0
        var easy = 0
        for (seed in 0L until 40L) {
            val levels = List(4) { if (it % 2 == 0) BotLevel.HARD else BotLevel.EASY }
            val match = Match(game, ScRules.BLOOD, seed)
            playOut(match, levels)
            val scores = match.result!!.scores
            hard += scores[0] + scores[2]
            easy += scores[1] + scores[3]
        }
        assertTrue(hard > easy, "hard $hard, easy $easy")
    }

    @Test
    fun `computers pass on three tiles of their weakest suit and get rid of the suit they hold least`() {
        val bot = ScBot(BotLevel.NORMAL, 1)
        val hand = t("123456789m 2355p 9s")
        assertEquals(TileSuit.BAMBOO, bot.voidSuit(hand))
        val swap = bot.swapTiles(t("1234567m 2359p 19s 5s"))
        assertEquals(setOf(TileSuit.BAMBOO), swap.map { it.suit }.toSet())
    }
}
