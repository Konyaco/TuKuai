package io.github.konyaco.tukuai.engine.mahjong.guobiao

import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.MjCodecs

object GbCodecs {
    val ACTION = object : WireCodec<GbAction> {
        override fun write(writer: WireWriter, value: GbAction) {
            when (value) {
                is GbAction.Discard -> writer.int(0).also { MjCodecs.writeTile(it, value.tile) }
                is GbAction.Kong -> writer.int(1).also { MjCodecs.writeTile(it, value.tile) }
                is GbAction.Chow -> writer.int(2).also { MjCodecs.writeTile(it, value.start) }
                GbAction.Pung -> writer.int(3)
                GbAction.Win -> writer.int(4)
                GbAction.Pass -> writer.int(5)
                GbAction.Next -> writer.int(6)
            }
        }

        override fun read(reader: WireReader): GbAction = when (val tag = reader.int()) {
            0 -> GbAction.Discard(MjCodecs.tile(reader))
            1 -> GbAction.Kong(MjCodecs.tile(reader))
            2 -> GbAction.Chow(MjCodecs.tile(reader))
            3 -> GbAction.Pung
            4 -> GbAction.Win
            5 -> GbAction.Pass
            6 -> GbAction.Next
            else -> throw IllegalArgumentException("Unknown action $tag")
        }
    }

    private fun WireWriter.fans(fans: List<GbFan>) = ints(fans.map { it.ordinal })

    private fun WireReader.fans(): List<GbFan> = ints().map { GbFan.entries[it] }

    private fun WireWriter.result(r: GbHandResult) {
        int(r.hand).int(r.dealer).int(r.winner ?: -1).int(r.from ?: -1)
        MjCodecs.writeTile(this, r.tile)
        fans(r.fans).ints(r.payments)
    }

    private fun WireReader.result() = GbHandResult(
        hand = int(),
        dealer = int(),
        winner = int().takeIf { it >= 0 },
        from = int().takeIf { it >= 0 },
        tile = MjCodecs.tileOrNull(this),
        fans = fans(),
        payments = ints(),
    )

    private fun WireWriter.event(e: GbEvent) {
        int(e.seat).int(e.kind.ordinal)
        MjCodecs.writeTile(this, e.tile)
        int(e.other).int(e.meld?.ordinal ?: -1)
    }

    private fun WireReader.event() = GbEvent(
        seat = int(),
        kind = GbEventKind.entries[int()],
        tile = MjCodecs.tileOrNull(this),
        other = int(),
        meld = int().takeIf { it >= 0 }?.let { MeldKind.entries[it] },
    )

    private fun WireWriter.options(o: GbOptions) {
        bool(o.win).bool(o.kong).bool(o.pung)
        MjCodecs.writeTiles(this, o.chows)
    }

    private fun WireReader.options() = GbOptions(win = bool(), kong = bool(), pung = bool(), chows = MjCodecs.tiles(this))

    val VIEW = object : WireCodec<GbView> {
        override fun write(writer: WireWriter, value: GbView) {
            writer.int(value.seat ?: -1)
            GbRules.CODEC.write(writer, value.rules)
            writer.int(value.phase.ordinal).int(value.handIndex).int(value.dealer).int(value.prevalentWind).int(value.turn).int(value.toAct ?: -1)
            writer.ints(value.actors)
            MjCodecs.writeTiles(writer, value.hand)
            MjCodecs.writeTile(writer, value.drawn)
            writer.ints(value.handCounts)
            writer.list(value.shown) { MjCodecs.writeTiles(this, it) }
            writer.list(value.melds) { list(it) { m -> MjCodecs.writeMeld(this, m) } }
            writer.list(value.discards) { MjCodecs.writeTiles(this, it) }
            writer.list(value.flowers) { MjCodecs.writeTiles(this, it) }
            MjCodecs.writeTile(writer, value.claimTile)
            writer.int(value.claimFrom ?: -1)
            writer.options(value.options)
            writer.int(value.wallSize).int(value.drawnFront).int(value.drawnBack)
            writer.nullable(value.selfDraw) { fans(it.fans) }
            writer.bool(value.fresh)
            writer.nullable(value.result) { result(it) }
            writer.list(value.history) { result(it) }
            writer.ints(value.scores)
            writer.list(value.ready) { bool(it) }
            writer.list(value.log) { event(it) }
        }

        override fun read(reader: WireReader): GbView = GbView(
            seat = reader.int().takeIf { it >= 0 },
            rules = GbRules.CODEC.read(reader),
            phase = GbPhase.entries[reader.int()],
            handIndex = reader.int(),
            dealer = reader.int(),
            prevalentWind = reader.int(),
            turn = reader.int(),
            toAct = reader.int().takeIf { it >= 0 },
            actors = reader.ints(),
            hand = MjCodecs.tiles(reader),
            drawn = MjCodecs.tileOrNull(reader),
            handCounts = reader.ints(),
            shown = reader.list { MjCodecs.tiles(this) },
            melds = reader.list { list { MjCodecs.meld(this) } },
            discards = reader.list { MjCodecs.tiles(this) },
            flowers = reader.list { MjCodecs.tiles(this) },
            claimTile = MjCodecs.tileOrNull(reader),
            claimFrom = reader.int().takeIf { it >= 0 },
            options = reader.options(),
            wallSize = reader.int(),
            drawnFront = reader.int(),
            drawnBack = reader.int(),
            selfDraw = reader.nullable { GbScore(fans()) },
            fresh = reader.bool(),
            result = reader.nullable { result() },
            history = reader.list { result() },
            scores = reader.ints(),
            ready = reader.list { bool() },
            log = reader.list { event() },
        )
    }
}
