package io.github.konyaco.tukuai.engine.tutu

import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

object TutuCodecs {
    private fun WireWriter.card(card: TutuCard?) = int(card?.id ?: -1)

    private fun WireReader.card(): TutuCard? = int().takeIf { it >= 0 }?.let {
        require(it < TutuDeck.SIZE) { "No card $it" }
        TutuCard(it)
    }

    private fun WireWriter.face(face: TutuFace?) = int(face?.let(TutuDeck::indexOf) ?: -1)

    private fun WireReader.face(): TutuFace? = int().takeIf { it >= 0 }?.let {
        require(it < TutuDeck.FACES.size) { "No face $it" }
        TutuDeck.FACES[it]
    }

    private fun WireWriter.color(color: TutuColor?) = int(color?.ordinal ?: -1)

    private fun WireReader.color(): TutuColor? = int().takeIf { it >= 0 }?.let { TutuColor.entries[it] }

    val ACTION = object : WireCodec<TutuAction> {
        override fun write(writer: WireWriter, value: TutuAction) {
            when (value) {
                is TutuAction.Play -> writer.int(0).card(value.card).color(value.color)
                TutuAction.Draw -> writer.int(1)
                TutuAction.Pass -> writer.int(2)
            }
        }

        override fun read(reader: WireReader): TutuAction = when (val tag = reader.int()) {
            0 -> TutuAction.Play(reader.card() ?: throw IllegalArgumentException("No card to play"), reader.color())
            1 -> TutuAction.Draw
            2 -> TutuAction.Pass
            else -> throw IllegalArgumentException("Unknown action $tag")
        }
    }

    private fun WireWriter.event(e: TutuEvent) {
        int(e.seat).int(e.kind.ordinal).card(e.card).face(e.face).color(e.color).int(e.count).bool(e.forced)
    }

    private fun WireReader.event() = TutuEvent(
        seat = int(),
        kind = TutuEventKind.entries[int()],
        card = card(),
        face = face(),
        color = color(),
        count = int(),
        forced = bool(),
    )

    val VIEW = object : WireCodec<TutuView> {
        override fun write(writer: WireWriter, value: TutuView) {
            writer.int(value.seat ?: -1)
            TutuRules.CODEC.write(writer, value.rules)
            writer.int(value.turn).int(value.direction).face(value.top).int(value.color.ordinal)
            TutuDeck.write(writer, value.hand)
            writer.ints(value.handCounts)
            TutuDeck.write(writer, value.discardTop)
            writer.int(value.discardCount).int(value.drawCount).ints(value.cacti).int(value.bombs).ints(value.fuses)
            writer.card(value.drawn)
            writer.list(value.log) { event(it) }
            writer.nullable(value.settlement) { s -> ints(s.winners.toList()).bool(s.pileEmpty).ints(s.handPoints).ints(s.scores) }
            writer.list(value.revealedHands) { TutuDeck.write(this, it) }
        }

        override fun read(reader: WireReader): TutuView = TutuView(
            seat = reader.int().takeIf { it >= 0 },
            rules = TutuRules.CODEC.read(reader),
            turn = reader.int(),
            direction = reader.int(),
            top = reader.face() ?: throw IllegalArgumentException("No top card"),
            color = TutuColor.entries[reader.int()],
            hand = TutuDeck.read(reader),
            handCounts = reader.ints(),
            discardTop = TutuDeck.read(reader),
            discardCount = reader.int(),
            drawCount = reader.int(),
            cacti = reader.ints(),
            bombs = reader.int(),
            fuses = reader.ints(),
            drawn = reader.card(),
            log = reader.list { event() },
            settlement = reader.nullable { TutuSettlement(ints().toCollection(LinkedHashSet()), bool(), ints(), ints()) },
            revealedHands = reader.list { TutuDeck.read(this) },
        )
    }
}
