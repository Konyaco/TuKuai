package io.github.konyaco.tukuai.engine.bluff

import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter
import io.github.konyaco.tukuai.engine.doudizhu.DdzCards

object BluffCodecs {
    val ACTION = object : WireCodec<BluffAction> {
        override fun write(writer: WireWriter, value: BluffAction) {
            when (value) {
                is BluffAction.Play -> writer.int(0).also { DdzCards.write(it, value.cards) }
                BluffAction.Call -> writer.int(1)
                BluffAction.Light -> writer.int(2)
            }
        }

        override fun read(reader: WireReader): BluffAction = when (val tag = reader.int()) {
            0 -> BluffAction.Play(DdzCards.read(reader))
            1 -> BluffAction.Call
            2 -> BluffAction.Light
            else -> throw IllegalArgumentException("Unknown action $tag")
        }
    }

    private fun WireWriter.event(e: BluffEvent) {
        int(e.seat).int(e.kind.ordinal)
        DdzCards.write(this, e.cards)
        int(e.count).int(e.other).bool(e.flag).int(e.rank?.ordinal ?: -1)
    }

    private fun WireReader.event() = BluffEvent(
        seat = int(),
        kind = BluffEventKind.entries[int()],
        cards = DdzCards.read(this),
        count = int(),
        other = int(),
        flag = bool(),
        rank = int().takeIf { it >= 0 }?.let { Rank.entries[it] },
    )

    val VIEW = object : WireCodec<BluffView> {
        override fun write(writer: WireWriter, value: BluffView) {
            writer.int(value.seat ?: -1)
            BluffRules.CODEC.write(writer, value.rules)
            writer.int(value.round).int(value.target.ordinal).int(value.phase.ordinal).int(value.turn)
            DdzCards.write(writer, value.hand)
            writer.ints(value.handCounts).ints(value.lit).list(value.out) { bool(it) }
            writer.int(value.claimSeat ?: -1).int(value.claimCount)
            DdzCards.write(writer, value.claimCards)
            writer.int(value.pileCount)
            writer.nullable(value.reveal) { r ->
                int(r.caller).int(r.claimer)
                DdzCards.write(this, r.cards)
                bool(r.lied)
            }
            writer.list(value.log) { event(it) }
            writer.ints(value.fuses)
            writer.list(value.revealedHands) { DdzCards.write(this, it) }
        }

        override fun read(reader: WireReader): BluffView = BluffView(
            seat = reader.int().takeIf { it >= 0 },
            rules = BluffRules.CODEC.read(reader),
            round = reader.int(),
            target = Rank.entries[reader.int()],
            phase = BluffPhase.entries[reader.int()],
            turn = reader.int(),
            hand = DdzCards.read(reader),
            handCounts = reader.ints(),
            lit = reader.ints(),
            out = reader.list { bool() },
            claimSeat = reader.int().takeIf { it >= 0 },
            claimCount = reader.int(),
            claimCards = DdzCards.read(reader),
            pileCount = reader.int(),
            reveal = reader.nullable { BluffReveal(int(), int(), DdzCards.read(this), bool()) },
            log = reader.list { event() },
            fuses = reader.ints(),
            revealedHands = reader.list { DdzCards.read(this) },
        )
    }
}
