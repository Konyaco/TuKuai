package io.github.konyaco.tukuai.engine.core

import io.github.konyaco.tukuai.engine.doudizhu.DdzCodecs
import io.github.konyaco.tukuai.engine.doudizhu.DdzView
import io.github.konyaco.tukuai.engine.doudizhu.DouDizhu
import io.github.konyaco.tukuai.engine.uno.Uno
import io.github.konyaco.tukuai.engine.uno.UnoCodecs
import io.github.konyaco.tukuai.engine.uno.UnoView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchingTest {
    private fun ddz(): MatchHandle = DouDizhu.newMatch(DouDizhu.presets.first().id, 42)

    private fun ddzView(match: MatchHandle, dealtIn: Seat?, seated: Seat?, open: Boolean): DdzView =
        DdzCodecs.VIEW.decode(Watching.encodedView(match, dealtIn, seated, open))

    @Test
    fun `a watcher sees every hand and the landlord's cards when the table allows it`() {
        val view = ddzView(ddz(), dealtIn = null, seated = null, open = true)
        assertNull(view.seat)
        assertEquals(3, view.revealedHands.size)
        assertEquals(view.handCounts, view.revealedHands.map { it.size })
        assertEquals(3, view.kitty.size)
    }

    @Test
    fun `a watcher sees no hand when the table does not allow it`() {
        val view = ddzView(ddz(), dealtIn = null, seated = null, open = false)
        assertTrue(view.revealedHands.isEmpty())
        assertTrue(view.hand.isEmpty())
        assertTrue(view.kitty.isEmpty())
    }

    @Test
    fun `a player in the match only sees their own seat, even out of it`() {
        val match = ddz()
        for (seated in listOf(1, null)) {
            val view = ddzView(match, dealtIn = 1, seated = seated, open = true)
            assertEquals(1, view.seat)
            assertEquals(17, view.hand.size)
            assertTrue(view.revealedHands.isEmpty())
            assertTrue(view.kitty.isEmpty())
        }
    }

    @Test
    fun `once the match is over, players are back to the usual rules`() {
        val match = ddz()
        while (match.result == null) match.playBot(match.toAct!!, BotLevel.EASY)
        assertNull(Watching.seatFor(dealtIn = 1, seated = null, over = true))
        assertEquals(2, Watching.seatFor(dealtIn = 1, seated = 2, over = true))
        assertEquals(1, Watching.seatFor(dealtIn = 1, seated = 2, over = false))
        val view = ddzView(match, dealtIn = 1, seated = null, open = true)
        assertNull(view.seat)
        assertEquals(3, view.revealedHands.size)
    }

    @Test
    fun `an UNO watcher sees every hand only when allowed`() {
        val match = Uno.newMatch(Uno.presets.first().id, 7)
        val open: UnoView = UnoCodecs.VIEW.decode(Watching.encodedView(match, null, null, open = true))
        assertEquals(match.seatCount, open.revealedHands.size)
        assertEquals(open.handCounts, open.revealedHands.map { it.size })
        val closed: UnoView = UnoCodecs.VIEW.decode(Watching.encodedView(match, null, null, open = false))
        assertTrue(closed.revealedHands.isEmpty())
        val player: UnoView = UnoCodecs.VIEW.decode(Watching.encodedView(match, 2, null, open = true))
        assertEquals(2, player.seat)
        assertTrue(player.revealedHands.isEmpty())
        assertEquals(player.handCounts[2], player.hand.size)
    }
}
