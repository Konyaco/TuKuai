package io.github.konyaco.tukuai.engine.mahjong.guobiao

import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.Tiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GbScoringTest {
    private fun t(text: String): List<Tile> = Tiles.parse(text)

    private fun tile(text: String): Tile = t(text).single()

    private fun chow(start: String) = Meld(MeldKind.CHOW, tile(start), 0)

    private fun pung(of: String) = Meld(MeldKind.PUNG, tile(of), 0)

    /**
     * Scores [concealed] (with the winning tile) won on [on]; south's seat in the east round
     * unless told otherwise, so that no wind is anyone's by accident.
     */
    private fun score(
        concealed: String,
        on: String,
        melds: List<Meld> = emptyList(),
        selfDrawn: Boolean = false,
        seat: Int = 1,
        prevalent: Int = 0,
        lastTile: Boolean = false,
        robKong: Boolean = false,
        lastOfKind: Boolean = false,
        flowers: Int = 0,
    ): GbScore? = GbScoring.score(
        GbWin(t(concealed), melds, tile(on), selfDrawn, seat, prevalent, lastTile = lastTile, robKong = robKong, lastOfKind = lastOfKind, flowers = flowers),
    )

    private fun fans(s: GbScore?): Map<GbFan, Int> = s!!.fans.groupingBy { it }.eachCount()

    @Test
    fun `a pure straight counts with all chows, a concealed hand and the single wait`() {
        val s = score("123456789m 234p 55s", "5s")
        assertEquals(mapOf(GbFan.PURE_STRAIGHT to 1, GbFan.ALL_CHOWS to 1, GbFan.CONCEALED_HAND to 1, GbFan.SINGLE_WAIT to 1), fans(s))
        assertEquals(21, s!!.total)
    }

    @Test
    fun `all pungs with the round's wind`() {
        val s = score("111w 99m", "9m", listOf(pung("2m"), pung("5p"), pung("8s")))
        assertEquals(mapOf(GbFan.ALL_PUNGS to 1, GbFan.PREVALENT_WIND to 1, GbFan.SINGLE_WAIT to 1), fans(s))
    }

    @Test
    fun `seven pairs self-drawn, with all five kinds of tile`() {
        val s = score("11m 33m 55p 77p 22s 11w 11d", "1d", selfDrawn = true)
        assertEquals(mapOf(GbFan.SEVEN_PAIRS to 1, GbFan.ALL_TYPES to 1, GbFan.SELF_DRAWN to 1), fans(s))
        assertEquals(31, s!!.total)
    }

    @Test
    fun `thirteen orphans counts nothing it includes`() {
        val s = score("19m 19p 19s 1234w 123d 1m", "1m")
        assertEquals(listOf(GbFan.THIRTEEN_ORPHANS), s!!.fans)
    }

    @Test
    fun `big three dragons with a half flush and three concealed pungs`() {
        val s = score("111222333d 123m 55m", "5m")
        assertEquals(
            mapOf(GbFan.BIG_THREE_DRAGONS to 1, GbFan.THREE_CONCEALED_PUNGS to 1, GbFan.HALF_FLUSH to 1, GbFan.CONCEALED_HAND to 1, GbFan.SINGLE_WAIT to 1),
            fans(s),
        )
        assertEquals(113, s!!.total)
    }

    @Test
    fun `two chows go together once each, never the same fan twice for one chow`() {
        val f = fans(score("123m 123m 456m 789p 55s", "5s"))
        assertEquals(1, f[GbFan.PURE_DOUBLE_CHOW])
        assertEquals(1, f[GbFan.SHORT_STRAIGHT])
    }

    @Test
    fun `a chow left over goes with a pattern of three once`() {
        val f = fans(score("234m 345p 456s 456m 99p", "9p"))
        assertEquals(1, f[GbFan.MIXED_SHIFTED_CHOWS])
        assertEquals(1, f[GbFan.MIXED_DOUBLE_CHOW])
    }

    @Test
    fun `shifted pungs and a double pung`() {
        val f = fans(score("222m 333m 444m 55s", "5s", listOf(pung("2p"))))
        assertEquals(1, f[GbFan.PURE_SHIFTED_PUNGS])
        assertEquals(1, f[GbFan.DOUBLE_PUNG])
        assertEquals(1, f[GbFan.ALL_PUNGS])
    }

    @Test
    fun `nine gates leaves out the full flush`() {
        val f = fans(score("1112345678999m 5m", "5m"))
        assertTrue(GbFan.NINE_GATES in f)
        assertFalse(GbFan.FULL_FLUSH in f)
        assertFalse(GbFan.CONCEALED_HAND in f)
    }

    @Test
    fun `honours and knitted tiles, and a knitted straight with a set and a pair`() {
        assertEquals(listOf(GbFan.LESSER_HONOURS_KNITTED), score("147m 258p 36s 1234w 12d", "2d")!!.fans)
        assertEquals(listOf(GbFan.GREATER_HONOURS_KNITTED), score("14m 25p 369s 1234w 123d", "3d")!!.fans)
        assertEquals(listOf(GbFan.LESSER_HONOURS_KNITTED, GbFan.KNITTED_STRAIGHT), score("147m 258p 369s 123w 12d", "2d")!!.fans.sortedBy { it.ordinal })
        val f = fans(score("147m 258p 369s 789m 55s", "5s"))
        assertTrue(GbFan.KNITTED_STRAIGHT in f)
        assertTrue(GbFan.ALL_CHOWS in f)
    }

    @Test
    fun `a hand with nothing else is a chicken hand`() {
        val s = score("67s 44w 8s", "8s", listOf(chow("1m"), chow("5p"), chow("2s")))
        assertEquals(listOf(GbFan.CHICKEN_HAND), s!!.fans)
        assertEquals(8, s.base)
    }

    @Test
    fun `everything melded, won on a discard with a single tile`() {
        val s = score("22s", "2s", listOf(chow("1m"), pung("5p"), chow("7s"), pung("1w")))
        assertEquals(mapOf(GbFan.MELDED_HAND to 1, GbFan.PREVALENT_WIND to 1), fans(s))
    }

    @Test
    fun `the dealer's east pung counts both winds and no terminal pung`() {
        val f = fans(score("111w 234m 567p 789s 55s", "5s", seat = 0, prevalent = 0))
        assertEquals(1, f[GbFan.PREVALENT_WIND])
        assertEquals(1, f[GbFan.SEAT_WIND])
        assertFalse(GbFan.PUNG_OF_TERMINALS_OR_HONOURS in f)
    }

    @Test
    fun `kongs count by kind`() {
        val melds = listOf(Meld(MeldKind.CONCEALED_KONG, tile("2m")), Meld(MeldKind.EXPOSED_KONG, tile("5p"), 0))
        val f = fans(score("789s 123s 44w", "4w", melds))
        assertEquals(1, f[GbFan.CONCEALED_KONG])
        assertEquals(1, f[GbFan.MELDED_KONG])
    }

    @Test
    fun `situational fan leave out what they include`() {
        val robbed = fans(score("67s 44w 8s", "8s", listOf(chow("1m"), chow("5p"), chow("2s")), robKong = true, lastOfKind = true))
        assertTrue(GbFan.ROBBING_THE_KONG in robbed)
        assertFalse(GbFan.LAST_OF_KIND in robbed)
        val last = fans(score("67s 44w 8s", "8s", listOf(chow("1m"), chow("5p"), chow("2s")), selfDrawn = true, lastTile = true))
        assertTrue(GbFan.LAST_TILE_DRAW in last)
        assertFalse(GbFan.SELF_DRAWN in last)
    }

    @Test
    fun `flowers add a fan each but do not count towards the least`() {
        val s = score("67s 44w 8s", "8s", listOf(chow("1m"), chow("5p"), chow("2s")), flowers = 2)!!
        assertEquals(10, s.total)
        assertEquals(8, s.base)
    }

    @Test
    fun `not a hand`() {
        assertNull(score("123m 456m 789p 555p 98m", "8m"))
    }

    @Test
    fun `waits come from every shape`() {
        assertEquals(t("1m 9m 1p 9p 1s 9s 1234w 123d"), GbScoring.waits(t("19m 19p 19s 1234w 123d"), emptyList()))
        assertEquals(t("47m"), GbScoring.waits(t("123m 56m 789p 555p 99m"), emptyList()))
        // A knitted straight waiting on its one missing tile.
        assertEquals(t("9s"), GbScoring.waits(t("147m 258p 36s 789m 55s"), emptyList()))
    }

    @Test
    fun `the book's hands have the fan it names them for`() {
        for (hand in GbGuide.SHAPES + GbGuide.HANDS) {
            val tiles = hand.groups.flatten()
            val s = GbScoring.score(GbWin(tiles, emptyList(), tiles.last(), false, 1, 0))
            // The fan, or a bigger one that includes it, as four concealed pungs include all pungs.
            assertTrue(s != null && (hand.fan in s.fans || s.fans.any { hand.fan in it.excludes }), "${hand.fan} in $tiles: ${s?.fans}")
        }
    }
}
