package io.github.konyaco.tukuai.neoforge.smoke

import io.github.konyaco.tukuai.smoke.SmokeTest
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod

@Mod(value = "tukuai_smoke", dist = [Dist.CLIENT])
class NeoForgeSmokeTest {
    init {
        SmokeTest.start()
    }
}
