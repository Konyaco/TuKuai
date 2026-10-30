package io.github.konyaco.tukuai

import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.engine.bluff.Bluff
import io.github.konyaco.tukuai.engine.core.GameRegistry
import io.github.konyaco.tukuai.engine.doudizhu.DouDizhu
import io.github.konyaco.tukuai.engine.guandan.Guandan
import io.github.konyaco.tukuai.engine.mahjong.sichuan.SichuanMahjong
import io.github.konyaco.tukuai.engine.paodekuai.PaoDeKuai
import io.github.konyaco.tukuai.engine.tutu.Tutu
import io.github.konyaco.tukuai.engine.uno.Uno
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object TuKuai {
    const val MOD_ID = "tukuai"

    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)

    /** Runs once on both sides when the mod loads, before any world opens. */
    fun init() {
        for (game in listOf(DouDizhu, Uno, PaoDeKuai, Guandan, Tutu, Bluff, SichuanMahjong)) if (GameRegistry[game.id] == null) GameRegistry.register(game)
    }
}
