package io.github.konyaco.tukuai.engine.core

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.doudizhu.DdzGuide
import io.github.konyaco.tukuai.engine.doudizhu.DdzRules
import io.github.konyaco.tukuai.engine.doudizhu.DouDizhu
import io.github.konyaco.tukuai.engine.doudizhu.Patterns
import io.github.konyaco.tukuai.engine.doudizhu.BiddingMode
import io.github.konyaco.tukuai.engine.doudizhu.DealMode
import io.github.konyaco.tukuai.engine.uno.Uno
import io.github.konyaco.tukuai.engine.uno.UnoDeck
import io.github.konyaco.tukuai.engine.uno.UnoFace
import io.github.konyaco.tukuai.engine.uno.UnoGuide
import io.github.konyaco.tukuai.engine.uno.UnoRules
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuideTest {
    private val games = listOf(DouDizhu, Uno)

    /** The English language file, read with a regex since the engine has no JSON library. */
    private val english: Map<String, String> by lazy {
        val file = File("../common/src/main/resources/assets/tukuai/lang/en_us.json")
        Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Every book: without a table, and for each preset. */
    private fun books(game: GameDefinition<*, *, *, *>): List<List<GuideSection>> =
        listOf(game.guide(null)) + game.presets.map { game.guide(it.id) }

    private fun keys(block: GuideBlock): List<Pair<String, Int>> = when (block) {
        is GuideBlock.Text -> listOf(block.key to block.args.size)
        is GuideBlock.Point -> listOf(block.key to block.args.size)
        is GuideBlock.Example -> listOfNotNull(block.name, block.text).map { it to 0 }
    }

    @Test
    fun `every text in every book is translated, with the right number of blanks`() {
        for (game in games) {
            for (book in books(game)) {
                assertTrue(book.isNotEmpty(), "${game.id} has no rule book")
                for (section in book) {
                    val used = keys(section).plus("tukuai.guide.${game.id}.${section.id}" to 0)
                    for ((key, args) in used) {
                        val text = english[key]
                        assertTrue(text != null, "$key is missing from en_us.json")
                        assertEquals(args, Regex("%s").findAll(text!!).count(), "$key should have $args %s")
                    }
                }
            }
        }
    }

    private fun keys(section: GuideSection) = section.blocks.flatMap(::keys)

    @Test
    fun `the table section comes first, and only when there is a table`() {
        for (game in games) {
            assertTrue(game.guide(null).none { it.id == GuideSection.TABLE }, "${game.id} explains a table without one")
            for (preset in game.presets) {
                assertEquals(GuideSection.TABLE, game.guide(preset.id).first().id, "${game.id} ${preset.id}")
            }
            val ids = game.guide(null).map { it.id }
            assertEquals(ids.size, ids.toSet().size, "${game.id} has two sections with the same id")
        }
    }

    @Test
    fun `presets with different rules read differently`() {
        for (game in games) {
            val tables = game.presets.map { preset -> keysOfTable(game.guide(preset.id)) to preset.id }
            for (i in tables.indices) for (j in i + 1 until tables.size) {
                assertNotEquals(tables[i].first, tables[j].first, "${game.id}: ${tables[i].second} and ${tables[j].second} read the same")
            }
        }
    }

    private fun keysOfTable(book: List<GuideSection>): List<GuideBlock> = book.first { it.id == GuideSection.TABLE }.blocks

    @Test
    fun `every Dou Dizhu rule changes what the table section says`() {
        // A new field in DdzRules needs a line in DdzGuide, and a case here.
        assertEquals(9, fieldCount(DdzRules::class.java), "DdzRules changed; describe the new rule in DdzGuide.table")
        val base = DdzRules.CLASSIC
        val changed = listOf(
            base.copy(bidding = BiddingMode.ROB),
            base.copy(doubling = true),
            base.copy(allowFourWithTwo = false),
            base.copy(deal = DealMode.CLUMPED),
            base.copy(baseScore = 2),
            base.copy(maxMultiplier = 64),
            base.copy(bombsDouble = false),
            base.copy(springDoubles = false),
            base.copy(redealLimit = 1),
            base.copy(redealLimit = 0),
        )
        val before = keysOfTable(DdzGuide.sections(base))
        for (rules in changed) assertNotEquals(before, keysOfTable(DdzGuide.sections(rules)), "$rules reads like the classic rules")
    }

    @Test
    fun `every UNO rule changes what the table section says`() {
        assertEquals(5, fieldCount(UnoRules::class.java), "UnoRules changed; describe the new rule in UnoGuide.table")
        val base = UnoRules.CLASSIC
        val changed = listOf(
            base.copy(players = 3),
            base.copy(handSize = 5),
            base.copy(stacking = true),
            base.copy(strictWildFour = false),
            base.copy(unoPenalty = 4),
            base.copy(unoPenalty = 0),
        )
        val before = keysOfTable(UnoGuide.sections(base))
        for (rules in changed) assertNotEquals(before, keysOfTable(UnoGuide.sections(rules)), "$rules reads like the classic rules")
    }

    private fun fieldCount(type: Class<*>) = type.declaredFields.count { !java.lang.reflect.Modifier.isStatic(it.modifiers) }

    @Test
    fun `each pattern example reads as that pattern`() {
        for ((type, cards) in DdzGuide.PATTERNS) {
            val readings = Patterns.classify(cards, DdzRules.CLASSIC)
            assertEquals(type, readings.firstOrNull()?.type, "${cards} should read as $type, not $readings")
        }
        assertEquals(io.github.konyaco.tukuai.engine.doudizhu.PatternType.entries.toSet(), DdzGuide.PATTERNS.map { it.first }.toSet())
        for ((_, cards) in DdzGuide.PATTERNS) assertEquals(cards.size, cards.toSet().size, "$cards uses one card twice")
    }

    @Test
    fun `Dou Dizhu examples of beating a play are right`() {
        val examples = DouDizhu.guide(null).flatMap { it.blocks }.filterIsInstance<GuideBlock.Example>().filter { it.follows != null }
        assertTrue(examples.isNotEmpty())
        for (example in examples) {
            for ((first, then) in example.groups.zipWithNext()) {
                val lead = Patterns.readingFor(first.map { it as PlayingCard }, null, DdzRules.CLASSIC)
                assertTrue(lead != null, "${example.text}: $first is not a play")
                val beats = Patterns.readingFor(then.map { it as PlayingCard }, lead, DdzRules.CLASSIC) != null
                assertEquals(example.follows, beats, "${example.text}: $then on $first")
            }
        }
    }

    @Test
    fun `UNO examples of what goes on what are right`() {
        for (section in Uno.guide(null)) {
            val rules = if (section.id == UnoGuide.STACKING) UnoRules.STACKING else UnoRules.CLASSIC
            for (example in section.blocks.filterIsInstance<GuideBlock.Example>().filter { it.follows != null }) {
                var pending = 0
                for ((first, then) in example.groups.zipWithNext()) {
                    val top = card(first.single() as UnoFace)
                    val card = card(then.single() as UnoFace)
                    // A wild on the pile takes the colour of the card that follows it, as if that was named.
                    val color = top.face.color ?: card.face.color ?: io.github.konyaco.tukuai.engine.uno.UnoColor.RED
                    if (rules.stacking) pending += top.face.drawPenalty
                    val ok = Uno.playable(rules, listOf(card), top, color, pending, null, card)
                    assertEquals(example.follows, ok, "${example.text}: ${card.face} on ${top.face}")
                }
            }
        }
    }

    private fun card(face: UnoFace) = UnoDeck.ALL.first { it.face == face }
}
