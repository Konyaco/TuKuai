package io.github.konyaco.tukuai.engine.paodekuai

import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter
import io.github.konyaco.tukuai.engine.doudizhu.DdzCards

object PdkCodecs {
    val ACTION = object : WireCodec<PdkAction> {
        override fun write(writer: WireWriter, value: PdkAction) {
            when (value) {
                is PdkAction.PlayCards -> writer.int(0).also { DdzCards.write(it, value.cards) }
                PdkAction.Pass -> writer.int(1)
            }
        }

        override fun read(reader: WireReader): PdkAction = when (val tag = reader.int()) {
            0 -> PdkAction.PlayCards(DdzCards.read(reader))
            1 -> PdkAction.Pass
            else -> throw IllegalArgumentException("Unknown action $tag")
        }
    }

    private fun WireWriter.pattern(p: PdkPattern) = int(p.type.ordinal).int(p.main).int(p.length)

    private fun WireReader.pattern() = PdkPattern(PdkPatternType.entries[int()], int(), int())

    private fun WireWriter.event(e: PdkEvent) {
        int(e.seat).int(e.kind.ordinal)
        DdzCards.write(this, e.cards)
        nullable(e.pattern) { pattern(it) }
    }

    private fun WireReader.event() = PdkEvent(
        seat = int(),
        kind = PdkEventKind.entries[int()],
        cards = DdzCards.read(this),
        pattern = nullable { pattern() },
    )

    val VIEW = object : WireCodec<PdkView> {
        override fun write(writer: WireWriter, value: PdkView) {
            writer.int(value.seat ?: -1)
            PdkRules.CODEC.write(writer, value.rules)
            writer.int(value.starter).int(value.turn)
            DdzCards.write(writer, value.hand)
            writer.ints(value.handCounts)
            writer.nullable(value.lead) { lead ->
                int(lead.seat)
                DdzCards.write(this, lead.cards)
                pattern(lead.pattern)
            }
            writer.ints(value.bombs)
            writer.list(value.log) { event(it) }
            writer.nullable(value.settlement) { s ->
                int(s.winner).ints(s.cardsLeft).list(s.shutOut) { bool(it) }.ints(s.bombs)
                    .ints(s.cardScores).ints(s.bombScores).ints(s.scores)
            }
            writer.list(value.revealedHands) { DdzCards.write(this, it) }
        }

        override fun read(reader: WireReader): PdkView = PdkView(
            seat = reader.int().takeIf { it >= 0 },
            rules = PdkRules.CODEC.read(reader),
            starter = reader.int(),
            turn = reader.int(),
            hand = DdzCards.read(reader),
            handCounts = reader.ints(),
            lead = reader.nullable { PdkTablePlay(int(), DdzCards.read(this), pattern()) },
            bombs = reader.ints(),
            log = reader.list { event() },
            settlement = reader.nullable {
                PdkSettlement(int(), ints(), list { bool() }, ints(), ints(), ints(), ints())
            },
            revealedHands = reader.list { DdzCards.read(this) },
        )
    }
}
