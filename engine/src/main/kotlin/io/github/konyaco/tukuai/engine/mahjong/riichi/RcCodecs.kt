package io.github.konyaco.tukuai.engine.mahjong.riichi

import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.MjCodecs

object RcCodecs {
    val ACTION = object : WireCodec<RcAction> {
        override fun write(writer: WireWriter, value: RcAction) {
            when (value) {
                is RcAction.Discard -> writer.int(0).also { MjCodecs.writeTile(it, value.tile) }
                is RcAction.Riichi -> writer.int(1).also { MjCodecs.writeTile(it, value.tile) }
                is RcAction.Kong -> writer.int(2).also { MjCodecs.writeTile(it, value.tile) }
                is RcAction.Chow -> writer.int(3).also { MjCodecs.writeTile(it, value.start) }
                RcAction.Pung -> writer.int(4)
                RcAction.Win -> writer.int(5)
                RcAction.Pass -> writer.int(6)
                RcAction.Abort -> writer.int(7)
                RcAction.Next -> writer.int(8)
            }
        }

        override fun read(reader: WireReader): RcAction = when (val tag = reader.int()) {
            0 -> RcAction.Discard(MjCodecs.tile(reader))
            1 -> RcAction.Riichi(MjCodecs.tile(reader))
            2 -> RcAction.Kong(MjCodecs.tile(reader))
            3 -> RcAction.Chow(MjCodecs.tile(reader))
            4 -> RcAction.Pung
            5 -> RcAction.Win
            6 -> RcAction.Pass
            7 -> RcAction.Abort
            8 -> RcAction.Next
            else -> throw IllegalArgumentException("Unknown action $tag")
        }
    }

    private fun WireWriter.yaku(list: List<Pair<RcYaku, Int>>) = list(list) { int(it.first.ordinal).int(it.second) }

    private fun WireReader.yaku(): List<Pair<RcYaku, Int>> = list { RcYaku.entries[int()] to int() }

    private fun WireWriter.score(s: RcScore) {
        yaku(s.yaku).int(s.dora).int(s.aka).int(s.ura).int(s.fu).int(s.yakuman)
    }

    private fun WireReader.score() = RcScore(yaku(), int(), int(), int(), int(), int())

    private fun WireWriter.win(w: RcWinRecord) {
        int(w.seat).int(w.from ?: -1)
        MjCodecs.writeTile(this, w.tile)
        yaku(w.yaku).int(w.dora).int(w.aka).int(w.ura).int(w.han).int(w.fu).int(w.yakuman).int(w.limit.ordinal).int(w.value)
    }

    private fun WireReader.win() = RcWinRecord(
        seat = int(), from = int().takeIf { it >= 0 }, tile = MjCodecs.tile(this), yaku = yaku(), dora = int(), aka = int(),
        ura = int(), han = int(), fu = int(), yakuman = int(), limit = RcLimit.entries[int()], value = int(),
    )

    private fun WireWriter.result(r: RcHandResult) {
        int(r.round).int(r.hand).int(r.honba).int(r.dealer)
        list(r.wins) { win(it) }
        list(r.ready) { bool(it) }
        ints(r.nagashi)
        int(r.abort?.ordinal ?: -1)
        ints(r.payments)
        MjCodecs.writeTiles(this, r.doraIndicators)
        MjCodecs.writeTiles(this, r.uraIndicators)
    }

    private fun WireReader.result() = RcHandResult(
        round = int(), hand = int(), honba = int(), dealer = int(), wins = list { win() }, ready = list { bool() },
        nagashi = ints(), abort = int().takeIf { it >= 0 }?.let { RcAbort.entries[it] }, payments = ints(),
        doraIndicators = MjCodecs.tiles(this), uraIndicators = MjCodecs.tiles(this),
    )

    private fun WireWriter.event(e: RcEvent) {
        int(e.seat).int(e.kind.ordinal)
        MjCodecs.writeTile(this, e.tile)
        int(e.other).int(e.meld?.ordinal ?: -1)
    }

    private fun WireReader.event() = RcEvent(
        seat = int(), kind = RcEventKind.entries[int()], tile = MjCodecs.tileOrNull(this), other = int(),
        meld = int().takeIf { it >= 0 }?.let { MeldKind.entries[it] },
    )

    private fun WireWriter.options(o: RcOptions) {
        bool(o.win).bool(o.kong).bool(o.pung)
        MjCodecs.writeTiles(this, o.chows)
    }

    private fun WireReader.options() = RcOptions(win = bool(), kong = bool(), pung = bool(), chows = MjCodecs.tiles(this))

    val VIEW = object : WireCodec<RcView> {
        override fun write(writer: WireWriter, value: RcView) {
            writer.int(value.seat ?: -1)
            RcRules.CODEC.write(writer, value.rules)
            writer.int(value.phase.ordinal).int(value.round).int(value.kyoku).int(value.honba).int(value.sticks).ints(value.points)
            writer.int(value.dealer).int(value.turn).int(value.toAct ?: -1).ints(value.actors)
            MjCodecs.writeTiles(writer, value.hand)
            MjCodecs.writeTile(writer, value.drawn)
            writer.ints(value.handCounts)
            writer.list(value.shown) { MjCodecs.writeTiles(this, it) }
            writer.list(value.melds) { list(it) { m -> MjCodecs.writeMeld(this, m) } }
            writer.list(value.discards) { MjCodecs.writeTiles(this, it) }
            writer.list(value.discarded) { MjCodecs.writeTiles(this, it) }
            writer.ints(value.sidewaysAt)
            writer.list(value.riichi) { bool(it) }
            writer.list(value.doubleRiichi) { bool(it) }
            writer.bool(value.ippatsu)
            MjCodecs.writeTiles(writer, value.doraIndicators)
            MjCodecs.writeTiles(writer, value.uraIndicators)
            MjCodecs.writeTile(writer, value.claimTile)
            writer.int(value.claimFrom ?: -1)
            writer.options(value.options)
            writer.nullable(value.tsumo) { score(it) }
            MjCodecs.writeTiles(writer, value.riichiTiles)
            MjCodecs.writeTiles(writer, value.kongs)
            MjCodecs.writeTiles(writer, value.discardable)
            writer.bool(value.canAbort).bool(value.furiten).bool(value.rinshan)
            writer.int(value.wallSize).int(value.drawnFront).int(value.drawnBack)
            writer.nullable(value.result) { result(it) }
            writer.list(value.history) { result(it) }
            writer.list(value.ready) { bool(it) }
            writer.list(value.log) { event(it) }
        }

        override fun read(reader: WireReader): RcView = RcView(
            seat = reader.int().takeIf { it >= 0 },
            rules = RcRules.CODEC.read(reader),
            phase = RcPhase.entries[reader.int()],
            round = reader.int(),
            kyoku = reader.int(),
            honba = reader.int(),
            sticks = reader.int(),
            points = reader.ints(),
            dealer = reader.int(),
            turn = reader.int(),
            toAct = reader.int().takeIf { it >= 0 },
            actors = reader.ints(),
            hand = MjCodecs.tiles(reader),
            drawn = MjCodecs.tileOrNull(reader),
            handCounts = reader.ints(),
            shown = reader.list { MjCodecs.tiles(this) },
            melds = reader.list { list { MjCodecs.meld(this) } },
            discards = reader.list { MjCodecs.tiles(this) },
            discarded = reader.list { MjCodecs.tiles(this) },
            sidewaysAt = reader.ints(),
            riichi = reader.list { bool() },
            doubleRiichi = reader.list { bool() },
            ippatsu = reader.bool(),
            doraIndicators = MjCodecs.tiles(reader),
            uraIndicators = MjCodecs.tiles(reader),
            claimTile = MjCodecs.tileOrNull(reader),
            claimFrom = reader.int().takeIf { it >= 0 },
            options = reader.options(),
            tsumo = reader.nullable { score() },
            riichiTiles = MjCodecs.tiles(reader),
            kongs = MjCodecs.tiles(reader),
            discardable = MjCodecs.tiles(reader),
            canAbort = reader.bool(),
            furiten = reader.bool(),
            rinshan = reader.bool(),
            wallSize = reader.int(),
            drawnFront = reader.int(),
            drawnBack = reader.int(),
            result = reader.nullable { result() },
            history = reader.list { result() },
            ready = reader.list { bool() },
            log = reader.list { event() },
        )
    }
}
