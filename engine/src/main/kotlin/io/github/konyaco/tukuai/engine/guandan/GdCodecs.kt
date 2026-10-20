package io.github.konyaco.tukuai.engine.guandan

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter
import io.github.konyaco.tukuai.engine.doudizhu.DdzCards

object GdCodecs {
    private fun WireWriter.card(card: PlayingCard) = int(DdzCards.index(card))

    private fun WireReader.card(): PlayingCard = DdzCards.fromIndex(int())

    val ACTION = object : WireCodec<GdAction> {
        override fun write(writer: WireWriter, value: GdAction) {
            when (value) {
                is GdAction.PlayCards -> writer.int(0).also { DdzCards.write(it, value.cards) }
                GdAction.Pass -> writer.int(1)
                is GdAction.Return -> writer.int(2).card(value.card)
                GdAction.Next -> writer.int(3)
            }
        }

        override fun read(reader: WireReader): GdAction = when (val tag = reader.int()) {
            0 -> GdAction.PlayCards(DdzCards.read(reader))
            1 -> GdAction.Pass
            2 -> GdAction.Return(reader.card())
            3 -> GdAction.Next
            else -> throw IllegalArgumentException("Unknown action $tag")
        }
    }

    private fun WireWriter.pattern(p: GdPattern) = int(p.type.ordinal).int(p.main).int(p.size)

    private fun WireReader.pattern() = GdPattern(GdPatternType.entries[int()], int(), int())

    private fun WireWriter.event(e: GdEvent) {
        int(e.seat).int(e.kind.ordinal)
        DdzCards.write(this, e.cards)
        nullable(e.pattern) { pattern(it) }
        int(e.other)
    }

    private fun WireReader.event() = GdEvent(
        seat = int(),
        kind = GdEventKind.entries[int()],
        cards = DdzCards.read(this),
        pattern = nullable { pattern() },
        other = int(),
    )

    private fun WireWriter.round(r: GdRound) = int(r.level).int(r.levelTeam).ints(r.order).int(r.gain).ints(r.levelsAfter)

    private fun WireReader.round() = GdRound(int(), int(), ints(), int(), ints())

    val VIEW = object : WireCodec<GdView> {
        override fun write(writer: WireWriter, value: GdView) {
            writer.int(value.seat ?: -1)
            GdRules.CODEC.write(writer, value.rules)
            writer.int(value.round).ints(value.levels).int(value.levelTeam).int(value.level)
                .int(value.phase.ordinal).int(value.turn)
            DdzCards.write(writer, value.hand)
            writer.ints(value.handCounts)
            writer.nullable(value.lead) { lead ->
                int(lead.seat)
                DdzCards.write(this, lead.cards)
                pattern(lead.pattern)
            }
            writer.ints(value.order)
            writer.list(value.tributes) { t ->
                int(t.from).int(t.to).card(t.card)
                nullable(t.returned) { card(it) }
            }
            writer.list(value.log) { event(it) }
            writer.list(value.rounds) { round(it) }
            writer.ints(value.scores)
            writer.nullable(value.winners) { ints(it.sorted()) }
            writer.list(value.revealedHands) { DdzCards.write(this, it) }
        }

        override fun read(reader: WireReader): GdView = GdView(
            seat = reader.int().takeIf { it >= 0 },
            rules = GdRules.CODEC.read(reader),
            round = reader.int(),
            levels = reader.ints(),
            levelTeam = reader.int(),
            level = reader.int(),
            phase = GdPhase.entries[reader.int()],
            turn = reader.int(),
            hand = DdzCards.read(reader),
            handCounts = reader.ints(),
            lead = reader.nullable { GdTablePlay(int(), DdzCards.read(this), pattern()) },
            order = reader.ints(),
            tributes = reader.list { GdTribute(int(), int(), card(), nullable { card() }) },
            log = reader.list { event() },
            rounds = reader.list { round() },
            scores = reader.ints(),
            winners = reader.nullable { ints().toSet() },
            revealedHands = reader.list { DdzCards.read(this) },
        )
    }
}
