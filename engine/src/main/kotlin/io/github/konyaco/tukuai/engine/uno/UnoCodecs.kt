package io.github.konyaco.tukuai.engine.uno

import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

object UnoCodecs {
    private fun WireWriter.card(card: UnoCard?) = int(card?.id ?: -1)

    private fun WireReader.card(): UnoCard? = int().takeIf { it >= 0 }?.let {
        require(it < UnoDeck.SIZE) { "No card $it" }
        UnoCard(it)
    }

    private fun WireWriter.color(color: UnoColor?) = int(color?.ordinal ?: -1)

    private fun WireReader.color(): UnoColor? = int().takeIf { it >= 0 }?.let { UnoColor.entries[it] }

    val ACTION = object : WireCodec<UnoAction> {
        override fun write(writer: WireWriter, value: UnoAction) {
            when (value) {
                is UnoAction.Play -> writer.int(0).card(value.card).color(value.color).bool(value.uno)
                UnoAction.Draw -> writer.int(1)
                UnoAction.Pass -> writer.int(2)
            }
        }

        override fun read(reader: WireReader): UnoAction = when (val tag = reader.int()) {
            0 -> UnoAction.Play(reader.card() ?: throw IllegalArgumentException("No card to play"), reader.color(), reader.bool())
            1 -> UnoAction.Draw
            2 -> UnoAction.Pass
            else -> throw IllegalArgumentException("Unknown action $tag")
        }
    }

    private fun WireWriter.event(e: UnoEvent) {
        int(e.seat).int(e.kind.ordinal).card(e.card).color(e.color).int(e.count).bool(e.forced)
    }

    private fun WireReader.event() = UnoEvent(
        seat = int(),
        kind = UnoEventKind.entries[int()],
        card = card(),
        color = color(),
        count = int(),
        forced = bool(),
    )

    val VIEW = object : WireCodec<UnoView> {
        override fun write(writer: WireWriter, value: UnoView) {
            writer.int(value.seat ?: -1)
            UnoRules.CODEC.write(writer, value.rules)
            writer.int(value.turn).int(value.direction).int(value.color.ordinal)
            UnoDeck.write(writer, value.hand)
            writer.ints(value.handCounts)
            UnoDeck.write(writer, value.discardTop)
            writer.int(value.discardCount).int(value.drawCount).int(value.pendingDraw).card(value.drawn)
            writer.list(value.log) { event(it) }
            writer.nullable(value.settlement) { s -> int(s.winner).ints(s.handPoints).ints(s.scores) }
            writer.list(value.revealedHands) { UnoDeck.write(this, it) }
        }

        override fun read(reader: WireReader): UnoView = UnoView(
            seat = reader.int().takeIf { it >= 0 },
            rules = UnoRules.CODEC.read(reader),
            turn = reader.int(),
            direction = reader.int(),
            color = UnoColor.entries[reader.int()],
            hand = UnoDeck.read(reader),
            handCounts = reader.ints(),
            discardTop = UnoDeck.read(reader),
            discardCount = reader.int(),
            drawCount = reader.int(),
            pendingDraw = reader.int(),
            drawn = reader.card(),
            log = reader.list { event() },
            settlement = reader.nullable { UnoSettlement(int(), ints(), ints()) },
            revealedHands = reader.list { UnoDeck.read(this) },
        )
    }
}
