package io.github.konyaco.tukuai.client.render

import io.github.konyaco.tukuai.content.SeatEntity
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.resources.ResourceLocation

/** Seats are invisible; this renderer draws nothing. */
class SeatRenderer(context: EntityRendererProvider.Context) : EntityRenderer<SeatEntity>(context) {
    override fun getTextureLocation(entity: SeatEntity): ResourceLocation = InventoryMenu.BLOCK_ATLAS

    override fun shouldRender(entity: SeatEntity, frustum: Frustum, x: Double, y: Double, z: Double): Boolean = false
}
