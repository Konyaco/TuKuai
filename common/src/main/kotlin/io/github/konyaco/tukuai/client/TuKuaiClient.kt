package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.ddz.DdzUi
import io.github.konyaco.tukuai.client.pdk.PdkUi
import io.github.konyaco.tukuai.client.uno.UnoUi
import io.github.konyaco.tukuai.platform.ClientPlatform
import io.github.konyaco.tukuai.platform.Services

/** Client setup shared by both loaders; each loader also registers the renderers and network handlers. */
object TuKuaiClient {
    fun init(platform: ClientPlatform) {
        Services.client = platform
        Services.openTable = TableScreen::open
        Services.receiveTable = ClientTables::receive
        Services.openReplay = ReplayScreen::open
        GameUis.register(DdzUi)
        GameUis.register(UnoUi)
        GameUis.register(PdkUi)
    }

    private var ready = false

    fun tick() {
        if (!ready) {
            val mc = net.minecraft.client.Minecraft.getInstance()
            if (mc.overlay == null && mc.screen != null) {
                ready = true
                // CI waits for this line to know the client finished loading with TuKuai in it.
                TuKuai.LOGGER.info("TuKuai client ready")
            }
        }
        ClientTables.tick()
    }
}
