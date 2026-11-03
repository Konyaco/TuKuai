package io.github.konyaco.tukuai.fabric.smoke

import io.github.konyaco.tukuai.smoke.SmokeTest
import net.fabricmc.api.ClientModInitializer

class FabricSmokeTest : ClientModInitializer {
    override fun onInitializeClient() = SmokeTest.start()
}
