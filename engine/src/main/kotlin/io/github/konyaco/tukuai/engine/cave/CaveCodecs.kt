package io.github.konyaco.tukuai.engine.cave

import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

object CaveCodecs {
    val ACTION = object : WireCodec<CaveAction> {
        override fun write(writer: WireWriter, value: CaveAction) {
            writer.int(choiceId(value))
        }

        override fun read(reader: WireReader): CaveAction = choice(reader.int()) ?: throw IllegalArgumentException("No action")
    }

    private fun choiceId(choice: CaveAction?): Int = when (choice) {
        null -> -1
        CaveAction.Deeper -> 0
        CaveAction.Back -> 1
    }

    private fun choice(id: Int): CaveAction? = when (id) {
        -1 -> null
        0 -> CaveAction.Deeper
        1 -> CaveAction.Back
        else -> throw IllegalArgumentException("Unknown action $id")
    }

    private fun WireWriter.card(card: CaveCard?) = int(card?.let(CaveDeck::id) ?: -1)

    private fun WireReader.card(): CaveCard? = int().takeIf { it >= 0 }?.let(CaveDeck::of)

    private fun WireWriter.event(e: CaveEvent) {
        int(e.kind.ordinal).int(e.round).int(e.seat)
        card(e.card)
        int(e.amount).int(e.count)
    }

    private fun WireReader.event() = CaveEvent(
        kind = CaveEventKind.entries[int()],
        round = int(),
        seat = int(),
        card = card(),
        amount = int(),
        count = int(),
    )

    val VIEW = object : WireCodec<CaveView> {
        override fun write(writer: WireWriter, value: CaveView) {
            writer.int(value.seat ?: -1)
            CaveRules.CODEC.write(writer, value.rules)
            writer.int(value.round).int(value.deckLeft)
            writer.ints(value.removed.map { it.ordinal })
            writer.list(value.path) { card(it.card); int(it.left) }
            writer.list(value.inside) { bool(it) }
            writer.list(value.decided) { bool(it) }
            writer.ints(value.choices.map(::choiceId))
            writer.ints(value.pack).ints(value.chest).bool(value.finished)
            writer.list(value.log) { event(it) }
        }

        override fun read(reader: WireReader): CaveView = CaveView(
            seat = reader.int().takeIf { it >= 0 },
            rules = CaveRules.CODEC.read(reader),
            round = reader.int(),
            deckLeft = reader.int(),
            removed = reader.ints().map { Hazard.entries[it] },
            path = reader.list { PathCard(card() ?: throw IllegalArgumentException("No card"), int()) },
            inside = reader.list { bool() },
            decided = reader.list { bool() },
            choices = reader.ints().map(::choice),
            pack = reader.ints(),
            chest = reader.ints(),
            finished = reader.bool(),
            log = reader.list { event() },
        )
    }
}
