package io.github.konyaco.tukuai.engine.mahjong

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MjHandsTest {
    private fun c(text: String) = Tiles.counts(Tiles.parse(text))

    @Test
    fun `tiles read and write the short way`() {
        assertEquals("[1m, 2m, 9p, 5s]", Tiles.parse("12m 9p 5s").toString())
        assertEquals(34, Tile.KINDS)
        assertEquals(108, Tiles.set(TileSuit.NUMBERED).size)
        assertEquals(Tile.of(TileSuit.DOTS, 1), Tile.of(9))
        assertEquals(null, Tile.of(TileSuit.BAMBOO, 9).next)
        assertEquals(null, Tile.of(TileSuit.WINDS, 1).next)
    }

    @Test
    fun `a complete hand is four sets and a pair`() {
        assertTrue(MjHands.isStandard(c("123m 456m 789p 555p 99m"), 4))
        assertTrue(MjHands.isStandard(c("222m 555m 777p 999p 33p"), 4))
        assertFalse(MjHands.isStandard(c("123m 456m 789p 555p 98m"), 4))
        // Runs do not wrap from 9 to 1, and do not cross suits.
        assertFalse(MjHands.isStandard(c("891m 456m 789p 555p 99m"), 4))
        assertFalse(MjHands.isStandard(c("89m 1p 456m 789p 555p 99m"), 4))
        // Honours make no runs.
        assertFalse(MjHands.isStandard(c("123w 456m 789p 555p 99m"), 4))
        // With melds laid down, fewer sets are left in the hand.
        assertTrue(MjHands.isStandard(c("456m 99p"), 1))
        assertTrue(MjHands.isStandard(c("99p"), 0))
    }

    @Test
    fun `tricky hands split more than one way`() {
        // Three pungs or three runs.
        val shapes = MjHands.shapes(c("111222333m 456p 77p"), 4)
        assertTrue(shapes.size >= 2, "$shapes")
        assertTrue(shapes.any { s -> s.groups.count { !it.run } == 3 })
        assertTrue(shapes.any { s -> s.groups.count { it.run } == 4 })
        // The pair can sit in the middle of what looks like runs.
        assertTrue(MjHands.isStandard(c("1112345678999m 5m"), 4))
        assertTrue(MjHands.isStandard(c("123445566m 789p 55s"), 4))
        assertTrue(MjHands.isStandard(c("234456m 345p 789p 55s"), 4))
        assertTrue(MjHands.isStandard(c("22333444m 678p 999s"), 4))
        assertTrue(MjHands.isStandard(c("11122233m 678p 999s"), 4))
        assertTrue(MjHands.isStandard(c("11122234m 678p 999s"), 4))
        assertFalse(MjHands.isStandard(c("11122245m 678p 999s"), 4))
    }

    @Test
    fun `seven pairs, with four of a kind as two pairs only when the rules say so`() {
        assertTrue(MjHands.isSevenPairs(c("1133557799m 2244p"), true))
        assertTrue(MjHands.isSevenPairs(c("1111m 33557799m 22p"), true))
        assertFalse(MjHands.isSevenPairs(c("1111m 33557799m 22p"), false))
        assertFalse(MjHands.isSevenPairs(c("1133557799m 224p 5p"), true))
        assertTrue(MjHands.isWin(c("1111m 33557799m 22p"), 4, sevenPairs = true, fourAsTwoPairs = true))
        assertFalse(MjHands.isWin(c("1111m 33557799m 22p"), 4, sevenPairs = false, fourAsTwoPairs = true))
    }

    @Test
    fun `shanten counts the tiles away from ready`() {
        assertEquals(-1, MjHands.standardShanten(c("123m 456m 789p 555p 99m"), 4))
        assertEquals(0, MjHands.standardShanten(c("123m 456m 789p 555p 9m"), 4))
        assertEquals(0, MjHands.standardShanten(c("123m 456m 789p 55p 99m"), 4))
        assertEquals(1, MjHands.standardShanten(c("123m 456m 78p 55p 99m 1s"), 4))
        assertEquals(2, MjHands.standardShanten(c("123m 456m 78p 55p 9m 1s 4s"), 4))
        assertEquals(0, MjHands.sevenPairsShanten(c("113355779m 2244p"), true))
        assertEquals(6, MjHands.sevenPairsShanten(c("1234567m 123456p"), true))
        // Four of a kind is two pairs in Sichuan, but not in other rules.
        assertEquals(0, MjHands.sevenPairsShanten(c("1111m 3355779m 22p"), true))
        assertEquals(2, MjHands.sevenPairsShanten(c("1111m 3355779m 22p"), false))
        assertEquals(0, MjHands.shanten(c("113355779m 2244p"), 4, sevenPairs = true, fourAsTwoPairs = true))
        assertTrue(MjHands.shanten(c("113355779m 2244p"), 4, sevenPairs = false, fourAsTwoPairs = true) > 0)
    }

    @Test
    fun `waits are the tiles that complete the hand`() {
        assertEquals(Tiles.parse("14p"), MjHands.waits(c("123m 456m 789m 11p 23p"), 4, true, true))
        // Nine gates waits on every tile of its suit.
        assertEquals(Tiles.parse("123456789m"), MjHands.waits(c("1112345678999m"), 4, true, true))
        // A single wait on the pair, and seven pairs waiting on the last pair.
        assertEquals(Tiles.parse("5s"), MjHands.waits(c("123m 456m 789m 999p 5s"), 4, true, true))
        assertEquals(Tiles.parse("9m"), MjHands.waits(c("113355779m 2244p"), 4, true, true))
        // A tile the hand holds all four of cannot come.
        assertEquals(emptyList<Tile>(), MjHands.waits(c("1111m"), 1, true, true))
        assertEquals(Tiles.parse("23m"), MjHands.waits(c("111m 2m"), 1, true, true))
        assertEquals(emptyList<Tile>(), MjHands.waits(c("123m 456m 789m 11p 23p"), 4, true, true) { it.suit != TileSuit.DOTS })
    }

    @Test
    fun `claims go to a win first, then in turn order`() {
        val answers = listOf(1 to ClaimKind.PUNG, 2 to ClaimKind.WIN, 3 to ClaimKind.WIN)
        assertEquals(listOf(2 to ClaimKind.WIN, 3 to ClaimKind.WIN), Claims.resolve(answers, multipleWins = true))
        assertEquals(listOf(2 to ClaimKind.WIN), Claims.resolve(answers, multipleWins = false))
        assertEquals(listOf(1 to ClaimKind.PUNG), Claims.resolve(listOf(1 to ClaimKind.PUNG, 2 to ClaimKind.CHOW), true))
        assertEquals(emptyList<Pair<Int, ClaimKind>>(), Claims.resolve(listOf(1 to ClaimKind.PASS, 2 to ClaimKind.PASS), true))
        assertEquals(listOf(2, 3, 0), Claims.after(1, 4))
    }

    @Test
    fun `the wall deals from the front and replaces kongs from the back`() {
        val wall = Wall(Tiles.parse("123456789m"))
        val (dealt, rest) = wall.deal(4)
        assertEquals(Tiles.parse("1234m"), dealt)
        val (front, w2) = rest.draw()
        assertEquals(Tile.of(4), front)
        val (back, w3) = w2.drawBack()
        assertEquals(Tile.of(8), back)
        assertEquals(3, w3.remaining)
        assertEquals(Tile.of(7), w3.drawBack().first)
    }

    @Test
    fun `melds hold their tiles`() {
        assertEquals(Tiles.parse("345m"), Meld(MeldKind.CHOW, Tile.of(2)).tiles)
        assertEquals(4, Meld(MeldKind.ADDED_KONG, Tile.of(2), 1).tiles.size)
        assertTrue(MeldKind.CONCEALED_KONG.isKong)
        assertFalse(MeldKind.PUNG.isKong)
    }
}
