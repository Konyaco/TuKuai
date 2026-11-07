package io.github.konyaco.tukuai.engine.mahjong.riichi

import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.Tiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RcScoringTest {
    private fun t(text: String): List<Tile> = Tiles.parse(text)

    private fun tile(text: String): Tile = t(text).single()

    /** South's seat in the east round unless told otherwise. */
    private fun score(
        concealed: String,
        on: String,
        melds: List<Meld> = emptyList(),
        tsumo: Boolean = false,
        seat: Int = 1,
        riichi: Boolean = false,
        ippatsu: Boolean = false,
        dora: String = "",
        ura: String = "",
    ): RcScore? = RcScoring.score(
        RcWin(t(concealed), melds, tile(on), tsumo, seat, 0, riichi = riichi, ippatsu = ippatsu, doraIndicators = t(dora), uraIndicators = t(ura)),
    )

    private fun yaku(s: RcScore?) = s!!.yaku.map { it.first }.toSet()

    @Test
    fun `pinfu on a discard is 1 han 30 fu, 1000 points`() {
        val s = score("123m 456m 789p 234s 66s", "4s")!!
        assertEquals(setOf(RcYaku.PINFU), yaku(s))
        assertEquals(30, s.fu)
        assertEquals(1000, s.ron(dealer = false))
        assertEquals(1500, s.ron(dealer = true))
    }

    @Test
    fun `pinfu tsumo is 2 han 20 fu, 400 and 700`() {
        val s = score("123m 456m 789p 234s 66s", "4s", tsumo = true)!!
        assertEquals(setOf(RcYaku.PINFU, RcYaku.MENZEN_TSUMO), yaku(s))
        assertEquals(20, s.fu)
        assertEquals(700 to 400, s.tsumo(dealer = false))
    }

    @Test
    fun `seven pairs is 25 fu`() {
        val s = score("11m 33m 55p 77p 22s 11w 11d", "1d")!!
        assertEquals(setOf(RcYaku.CHIITOITSU), yaku(s))
        assertEquals(1600, s.ron(dealer = false))
    }

    @Test
    fun `an open hand with no yaku cannot win, and dora alone are no yaku`() {
        val s = score("456p 789s 11w 123s", "1s", listOf(Meld(MeldKind.CHOW, tile("1m"), 0)), dora = "9p")
        assertTrue(s != null && s.yaku.isEmpty())
        assertEquals(0, s!!.dora)
    }

    @Test
    fun `value honours, all simples open, and flushes`() {
        assertTrue(RcYaku.HAKU in yaku(score("234m 567p 789s 333d 11m", "1m")))
        val open = score("234m 678s 22p 55s 5s", "5s", listOf(Meld(MeldKind.PUNG, tile("5p"), 0)))
        assertTrue(RcYaku.TANYAO in yaku(open))
        assertTrue(RcYaku.HONITSU in yaku(score("234p 666p 111d 333w 88p", "8p")))
        assertTrue(RcYaku.CHINITSU in yaku(score("123p 345p 666p 789p 99p", "9p")))
    }

    @Test
    fun `a pung completed on a discard is not concealed`() {
        // Shanpon on a discard: three concealed pungs, all pungs; by tsumo it would be four concealed pungs.
        val ron = score("222m 555p 888s 99m 111w", "1w")!!
        assertTrue(RcYaku.SANANKOU in yaku(ron) && RcYaku.TOITOI in yaku(ron))
        val tsumo = score("222m 555p 888s 99m 111w", "1w", tsumo = true)!!
        assertEquals(setOf(RcYaku.SUUANKOU), yaku(tsumo))
        assertEquals(16000 to 8000, tsumo.tsumo(dealer = false))
    }

    @Test
    fun `thirteen orphans is a yakuman`() {
        val s = score("19m 19p 19s 1234w 123d 1m", "1m")!!
        assertEquals(setOf(RcYaku.KOKUSHI), yaku(s))
        assertEquals(32000, s.ron(dealer = false))
        assertEquals(48000, s.ron(dealer = true))
    }

    @Test
    fun `riichi, ippatsu, dora, red fives and ura dora add up to a limit`() {
        val s = score("123m 456m 789p 234s 0s5s", "4s", riichi = true, ippatsu = true, dora = "1m", ura = "4s")!!
        assertTrue(RcYaku.RIICHI in yaku(s) && RcYaku.IPPATSU in yaku(s) && RcYaku.PINFU in yaku(s))
        assertEquals(1, s.dora)
        assertEquals(1, s.aka)
        assertEquals(2, s.ura)
        assertEquals(7, s.han)
        assertEquals(RcLimit.HANEMAN, s.limit)
        assertEquals(12000, s.ron(dealer = false))
    }

    @Test
    fun `dora come after the indicator, round the suit`() {
        assertEquals(tile("1m"), RcScoring.doraOf(tile("9m")))
        assertEquals(tile("1w"), RcScoring.doraOf(tile("4w")))
        assertEquals(tile("2d"), RcScoring.doraOf(tile("3d")))
        assertEquals(tile("1d"), RcScoring.doraOf(tile("2d")))
        assertEquals(tile("3d"), RcScoring.doraOf(tile("1d")))
    }

    @Test
    fun `fu count the sets, the pair and the wait`() {
        // Closed ron, a concealed pung of terminals (8), a closed wait (2): 20 + 10 + 8 + 2 = 40.
        val s = score("111m 456p 789s 13p 55s 2p", "2p", riichi = true)!!
        assertEquals(40, s.fu)
        assertFalse(RcYaku.PINFU in yaku(s))
    }

    @Test
    fun `not a hand`() {
        assertNull(score("123m 456m 789p 555p 98m", "8m"))
    }

    @Test
    fun `the book's hands count the yaku it names them for`() {
        for (hand in RcGuide.SHAPES + RcGuide.HANDS) {
            val tiles = hand.groups.flatten()
            val s = RcScoring.score(RcWin(tiles, emptyList(), tiles.last(), false, 1, 0))
            assertTrue(s != null && hand.yaku in s.yaku.map { it.first }, "${hand.yaku} in $tiles: ${s?.yaku}")
        }
    }
}
