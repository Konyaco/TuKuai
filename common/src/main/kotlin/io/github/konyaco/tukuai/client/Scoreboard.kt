package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * The small box at the top left of the table screen during a match: which game of how
 * many this is, and everyone's points so far, so nobody has to go back to the
 * lobby to see the score. Players who are ready for the next game get a tick, and a
 * game that waits because its players all left says so.
 */
object Scoreboard {
    /** Wide enough for a name and its points, and narrow enough to stay clear of the result box in the middle. */
    const val WIDTH = 100
    private const val LEFT = 4
    private const val TOP = 4
    private const val LINE = 10

    private val font get() = Minecraft.getInstance().font

    /** Draws the box; returns the y just below it. */
    fun render(g: GuiGraphicsExtractor, table: ClientTable): Int {
        val s = table.snapshot
        val voters = s.voters
        // A watcher's line, wrapped to the box, at its foot.
        val watching = table.watchingLine()?.let { font.split(it, WIDTH - 8) }.orEmpty()
        val lines = 1 + s.seats.size + (if (s.paused) 1 else 0) + watching.size
        val bottom = TOP + 4 + lines * LINE
        g.fill(LEFT, TOP, LEFT + WIDTH, bottom, BACKGROUND)
        g.fill(LEFT, TOP, LEFT + WIDTH, TOP + 1, EDGE)
        val x = LEFT + 4
        var y = TOP + 4
        g.text(font, roundLine(table), x, y, if (s.matchOver) TableScreen.YELLOW else TableScreen.WHITE, false)
        y += LINE
        val finished = s.status != TableStatus.PLAYING
        s.seats.forEachIndexed { seat, info ->
            val points = s.tally.getOrElse(seat) { 0 }
            val text = signed(points)
            val pointsX = LEFT + WIDTH - 4 - font.width(text)
            var nameX = x
            if (finished && info.ready && seat in voters && voters.size > 1) {
                g.text(font, "✔", x, y, TableScreen.GREEN, false)
                nameX += 8
            }
            val color = if (seat == s.you) TableScreen.YELLOW else TableScreen.WHITE
            val name = font.plainSubstrByWidth(TableScreen.seatName(info.occupant, seat).string, pointsX - nameX - 3)
            g.text(font, name, nameX, y, color, false)
            g.text(font, text, pointsX, y, pointsColor(points), false)
            y += LINE
        }
        if (s.paused) {
            g.text(font, Component.translatable("tukuai.table.paused"), x, y, TableScreen.YELLOW, false)
            y += LINE
        }
        for (line in watching) {
            g.text(font, line, x, y, if (s.spectators) TableScreen.YELLOW else TableScreen.GRAY, false)
            y += LINE
        }
        return bottom
    }

    /** "Game 3 of 8", or just "Game 3" with no limit, or that the match is over. */
    fun roundLine(table: ClientTable): Component {
        val s = table.snapshot
        if (s.matchOver) return Component.translatable("tukuai.table.match_over", s.rounds)
        val round = s.rounds + if (s.status == TableStatus.PLAYING) 1 else 0
        return if (s.matchRounds > 0) {
            Component.translatable("tukuai.table.round_of", round.coerceAtLeast(1), s.matchRounds)
        } else {
            Component.translatable("tukuai.table.rounds", round.coerceAtLeast(1))
        }
    }

    /** Seats from the most points to the fewest, each with its place; seats with the same points share it. */
    fun standings(table: ClientTable): List<Pair<Int, Seat>> {
        val tally = table.snapshot.tally
        val order = tally.indices.sortedByDescending { tally[it] }
        return order.map { seat -> 1 + order.count { tally[it] > tally[seat] } to seat }
    }

    fun signed(points: Int): String = if (points > 0) "+$points" else points.toString()

    fun pointsColor(points: Int): Int = when {
        points > 0 -> TableScreen.GREEN
        points < 0 -> TableScreen.RED
        else -> TableScreen.WHITE
    }

    /** Gold, silver and bronze for the first three places. */
    fun placeColor(place: Int): Int = when (place) {
        1 -> 0xFFFFD24A.toInt()
        2 -> 0xFFD6DEE6.toInt()
        3 -> 0xFFE09A5A.toInt()
        else -> TableScreen.GRAY
    }

    private const val BACKGROUND = 0xA0101418.toInt()
    private const val EDGE = 0xFFC9A44A.toInt()
}
