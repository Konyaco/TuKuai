package io.github.konyaco.tukuai.engine.mahjong.sichuan

import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter
import io.github.konyaco.tukuai.engine.mahjong.ClaimKind
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.MjCodecs
import io.github.konyaco.tukuai.engine.mahjong.TileSuit

object ScCodecs {
    val ACTION = object : WireCodec<ScAction> {
        override fun write(writer: WireWriter, value: ScAction) {
            when (value) {
                is ScAction.Swap -> writer.int(0).also { MjCodecs.writeTiles(it, value.tiles) }
                is ScAction.ChooseVoid -> writer.int(1).int(value.suit.ordinal)
                is ScAction.Discard -> writer.int(2).int(value.tile.id)
                is ScAction.Kong -> writer.int(3).int(value.tile.id)
                ScAction.Pung -> writer.int(4)
                ScAction.Win -> writer.int(5)
                ScAction.Pass -> writer.int(6)
            }
        }

        override fun read(reader: WireReader): ScAction = when (val tag = reader.int()) {
            0 -> ScAction.Swap(MjCodecs.tiles(reader))
            1 -> ScAction.ChooseVoid(TileSuit.entries[reader.int()])
            2 -> ScAction.Discard(MjCodecs.tile(reader))
            3 -> ScAction.Kong(MjCodecs.tile(reader))
            4 -> ScAction.Pung
            5 -> ScAction.Win
            6 -> ScAction.Pass
            else -> throw IllegalArgumentException("Unknown action $tag")
        }
    }

    private fun WireWriter.event(e: ScEvent) {
        int(e.seat).int(e.kind.ordinal).int(e.tile?.id ?: -1).int(e.other).int(e.meld?.ordinal ?: -1).ints(e.payments)
    }

    private fun WireReader.event() = ScEvent(
        seat = int(),
        kind = ScEventKind.entries[int()],
        tile = MjCodecs.tileOrNull(this),
        other = int(),
        meld = int().takeIf { it >= 0 }?.let { MeldKind.entries[it] },
        payments = ints(),
    )

    private fun WireWriter.win(w: ScWin) {
        int(w.seat).int(w.tile.id).int(w.from ?: -1).ints(w.fans.map { it.ordinal }).int(w.fan).int(w.points).ints(w.payments)
    }

    private fun WireReader.win() = ScWin(
        seat = int(),
        tile = MjCodecs.tile(this),
        from = int().takeIf { it >= 0 },
        fans = ints().map { ScFan.entries[it] },
        fan = int(),
        points = int(),
        payments = ints(),
    )

    val VIEW = object : WireCodec<ScView> {
        override fun write(writer: WireWriter, value: ScView) {
            writer.int(value.seat ?: -1)
            ScRules.CODEC.write(writer, value.rules)
            writer.int(value.phase.ordinal).int(value.dealer).int(value.turn).int(value.toAct ?: -1)
            MjCodecs.writeTiles(writer, value.hand)
            writer.int(value.drawn?.id ?: -1)
            writer.ints(value.handCounts)
            writer.list(value.shown) { MjCodecs.writeTiles(this, it) }
            writer.list(value.melds) { list(it) { m -> MjCodecs.writeMeld(this, m) } }
            writer.list(value.discards) { MjCodecs.writeTiles(this, it) }
            writer.list(value.voids) { int(it?.ordinal ?: -1) }
            writer.list(value.picked) { bool(it) }
            writer.int(value.swapDirection?.ordinal ?: -1)
            MjCodecs.writeTiles(writer, value.gave)
            MjCodecs.writeTiles(writer, value.got)
            writer.int(value.claimTile?.id ?: -1).int(value.claimFrom ?: -1)
            writer.ints(value.options.map { it.ordinal })
            writer.int(value.wallSize).int(value.drawnFront).int(value.drawnBack)
            writer.list(value.wins) { win(it) }
            writer.ints(value.scores)
            writer.list(value.log) { event(it) }
        }

        override fun read(reader: WireReader): ScView = ScView(
            seat = reader.int().takeIf { it >= 0 },
            rules = ScRules.CODEC.read(reader),
            phase = ScPhase.entries[reader.int()],
            dealer = reader.int(),
            turn = reader.int(),
            toAct = reader.int().takeIf { it >= 0 },
            hand = MjCodecs.tiles(reader),
            drawn = MjCodecs.tileOrNull(reader),
            handCounts = reader.ints(),
            shown = reader.list { MjCodecs.tiles(this) },
            melds = reader.list { list { MjCodecs.meld(this) } },
            discards = reader.list { MjCodecs.tiles(this) },
            voids = reader.list { MjCodecs.suitOrNull(this) },
            picked = reader.list { bool() },
            swapDirection = reader.int().takeIf { it >= 0 }?.let { SwapDirection.entries[it] },
            gave = MjCodecs.tiles(reader),
            got = MjCodecs.tiles(reader),
            claimTile = MjCodecs.tileOrNull(reader),
            claimFrom = reader.int().takeIf { it >= 0 },
            options = reader.ints().map { ClaimKind.entries[it] },
            wallSize = reader.int(),
            drawnFront = reader.int(),
            drawnBack = reader.int(),
            wins = reader.list { win() },
            scores = reader.ints(),
            log = reader.list { event() },
        )
    }
}
