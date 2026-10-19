package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.client.ddz.DdzUi
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
    }

    fun tick() = ClientTables.tick()
}
