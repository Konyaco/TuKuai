package io.github.konyaco.tukuai.engine.doudizhu

import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

object DdzCodecs {
    val ACTION = object : WireCodec<DdzAction> {
        override fun write(writer: WireWriter, value: DdzAction) {
            when (value) {
                is DdzAction.Bid -> writer.int(0).int(value.points)
                is DdzAction.Call -> writer.int(1).bool(value.yes)
                is DdzAction.Double -> writer.int(2).bool(value.yes)
                is DdzAction.PlayCards -> writer.int(3).also { DdzCards.write(it, value.cards) }
                DdzAction.Pass -> writer.int(4)
            }
        }

        override fun read(reader: WireReader): DdzAction = when (val tag = reader.int()) {
            0 -> DdzAction.Bid(reader.int())
            1 -> DdzAction.Call(reader.bool())
            2 -> DdzAction.Double(reader.bool())
            3 -> DdzAction.PlayCards(DdzCards.read(reader))
            4 -> DdzAction.Pass
            else -> throw IllegalArgumentException("Unknown action $tag")
        }
    }

    private fun WireWriter.pattern(p: Pattern) = int(p.type.ordinal).int(p.main).int(p.length)

    private fun WireReader.pattern() = Pattern(PatternType.entries[int()], int(), int())

    private fun WireWriter.event(e: DdzEvent) {
        int(e.seat).int(e.kind.ordinal).int(e.value)
        DdzCards.write(this, e.cards)
        nullable(e.pattern) { pattern(it) }
    }

    private fun WireReader.event() = DdzEvent(
        seat = int(),
        kind = DdzEventKind.entries[int()],
        value = int(),
        cards = DdzCards.read(this),
        pattern = nullable { pattern() },
    )

    private fun WireWriter.optionalBool(value: Boolean?) = int(
        when (value) {
            null -> -1
            false -> 0
            true -> 1
        },
    )

    private fun WireReader.optionalBool(): Boolean? = when (int()) {
        -1 -> null
        0 -> false
        else -> true
    }

    val VIEW = object : WireCodec<DdzView> {
        override fun write(writer: WireWriter, value: DdzView) {
            writer.int(value.seat ?: -1)
            DdzRules.CODEC.write(writer, value.rules)
            writer.int(value.phase.ordinal).int(value.turn).int(value.firstBidder).int(value.landlord ?: -1)
            DdzCards.write(writer, value.hand)
            writer.ints(value.handCounts)
            DdzCards.write(writer, value.kitty)
            writer.list(value.bids) { event(it) }
            writer.int(value.bidMultiplier)
            writer.list(value.doubles) { optionalBool(it) }
            writer.nullable(value.lead) { lead ->
                int(lead.seat)
                DdzCards.write(this, lead.cards)
                pattern(lead.pattern)
            }
            writer.int(value.bombs)
            writer.list(value.log) { event(it) }
            writer.nullable(value.settlement) { s ->
                bool(s.landlordWon).int(s.bidMultiplier).int(s.bombs).bool(s.spring).int(s.multiplier).ints(s.scores)
            }
            writer.list(value.revealedHands) { DdzCards.write(this, it) }
        }

        override fun read(reader: WireReader): DdzView = DdzView(
            seat = reader.int().takeIf { it >= 0 },
            rules = DdzRules.CODEC.read(reader),
            phase = DdzPhase.entries[reader.int()],
            turn = reader.int(),
            firstBidder = reader.int(),
            landlord = reader.int().takeIf { it >= 0 },
            hand = DdzCards.read(reader),
            handCounts = reader.ints(),
            kitty = DdzCards.read(reader),
            bids = reader.list { event() },
            bidMultiplier = reader.int(),
            doubles = reader.list { optionalBool() },
            lead = reader.nullable { TablePlay(int(), DdzCards.read(this), pattern()) },
            bombs = reader.int(),
            log = reader.list { event() },
            settlement = reader.nullable {
                Settlement(bool(), int(), int(), bool(), int(), ints())
            },
            revealedHands = reader.list { DdzCards.read(this) },
        )
    }
}
