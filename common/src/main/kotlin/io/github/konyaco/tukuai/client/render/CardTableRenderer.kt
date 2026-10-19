package io.github.konyaco.tukuai.client.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.world.item.DyeColor
import net.minecraft.world.phys.Vec3

/**
 * Draws the dyed cloth and the cards lying on a card table. It is attached to the
 * table's middle block and draws across all nine.
 */
open class CardTableRenderer(@Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<CardTableBlockEntity, CardTableRenderer.State> {

    class State : BlockEntityRenderState() {
        var feltColor: Int = DyeColor.GREEN.textureDiffuseColor
        var cards: List<TableCard> = emptyList()
    }

    override fun createRenderState(): State = State()

    override fun extractRenderState(
        table: CardTableBlockEntity,
        state: State,
        partialTick: Float,
        cameraPos: Vec3,
        crumbling: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(table, state, crumbling)
        val client = ClientTables[table.blockPos]
        state.feltColor = DyeColor.byId(client?.snapshot?.felt ?: DyeColor.GREEN.id).textureDiffuseColor
        state.cards = if (client == null) {
            emptyList()
        } else {
            val facing = table.blockState.getValue(CardTableBlock.FACING)
            val sides = List(client.snapshot.seats.size) { seat ->
                var side: Direction = facing
                repeat(seat) { side = side.counterClockWise }
                side
            }
            client.scene(sides)
        }
    }

    override fun submit(state: State, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val light = state.lightCoords
        val felt = 0xFF000000.toInt() or lighten(state.feltColor)
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(FELT)) { pose, buffer ->
            // One tile of the cloth texture per block, cut short at the wooden rim.
            val y = 1f + 0.0005f
            for (bx in -1..1) for (bz in -1..1) {
                val x0 = if (bx == -1) RIM else 0f
                val x1 = if (bx == 1) 1f - RIM else 1f
                val z0 = if (bz == -1) RIM else 0f
                val z1 = if (bz == 1) 1f - RIM else 1f
                quad(
                    buffer, pose, light, felt,
                    bx + x0, y, bz + z0, bx + x1, y, bz + z0, bx + x1, y, bz + z1, bx + x0, y, bz + z1,
                    x0, z0, x1, z1,
                )
            }
        }
        if (state.cards.isEmpty()) return
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(CardArt.ATLAS)) { pose, buffer ->
            for (card in state.cards) drawCard(buffer, pose, light, card)
        }
    }

    private fun drawCard(buffer: VertexConsumer, pose: PoseStack.Pose, light: Int, card: TableCard) {
        val halfW = CARD_WIDTH * card.scale / 2
        val halfH = CARD_WIDTH * card.scale * CardArt.HEIGHT / CardArt.WIDTH / 2
        // Right of the card as seen by someone reading it: the "up" direction turned clockwise.
        val rx = -card.upZ
        val rz = card.upX
        // Card positions are measured from the middle of the table.
        val cx = card.x + 0.5f
        val cz = card.z + 0.5f
        val y = 1.002f + card.layer * 0.0004f
        val u0 = CardArt.u(card.sprite).toFloat() / CardArt.TEXTURE_WIDTH
        val v0 = CardArt.v(card.sprite).toFloat() / CardArt.TEXTURE_HEIGHT
        val u1 = u0 + CardArt.WIDTH.toFloat() / CardArt.TEXTURE_WIDTH
        val v1 = v0 + CardArt.HEIGHT.toFloat() / CardArt.TEXTURE_HEIGHT
        // Corners: bottom-left, bottom-right, top-right, top-left.
        val blX = cx - rx * halfW - card.upX * halfH
        val blZ = cz - rz * halfW - card.upZ * halfH
        val brX = cx + rx * halfW - card.upX * halfH
        val brZ = cz + rz * halfW - card.upZ * halfH
        val trX = cx + rx * halfW + card.upX * halfH
        val trZ = cz + rz * halfW + card.upZ * halfH
        val tlX = cx - rx * halfW + card.upX * halfH
        val tlZ = cz - rz * halfW + card.upZ * halfH
        quad(buffer, pose, light, -1, blX, y, blZ, brX, y, brZ, trX, y, trZ, tlX, y, tlZ, u0, v1, u1, v0)
    }

    /** One upward-facing quad; (u0, v0) maps to the first corner and (u1, v1) to the third. */
    private fun quad(
        buffer: VertexConsumer,
        pose: PoseStack.Pose,
        light: Int,
        color: Int,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        x4: Float, y4: Float, z4: Float,
        u0: Float, v0: Float, u1: Float, v1: Float,
    ) {
        vertex(buffer, pose, light, color, x1, y1, z1, u0, v0)
        vertex(buffer, pose, light, color, x2, y2, z2, u1, v0)
        vertex(buffer, pose, light, color, x3, y3, z3, u1, v1)
        vertex(buffer, pose, light, color, x4, y4, z4, u0, v1)
    }

    private fun vertex(buffer: VertexConsumer, pose: PoseStack.Pose, light: Int, color: Int, x: Float, y: Float, z: Float, u: Float, v: Float) {
        buffer.addVertex(pose, x, y, z)
            .setColor(color)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(pose, 0f, 1f, 0f)
    }

    companion object {
        /** Dye colours are dark for cloth seen from above, so mix in a quarter of white. */
        private fun lighten(rgb: Int): Int {
            fun channel(shift: Int) = ((rgb shr shift and 0xFF) * 3 + 255) / 4 shl shift
            return channel(16) or channel(8) or channel(0)
        }

        private val FELT = TuKuai.id("textures/block/card_table_felt.png")

        /** Width of a card on the table at scale 1, in blocks. */
        private const val CARD_WIDTH = 0.3f

        /** Width of the bare wooden rim around the cloth, in blocks. */
        private const val RIM = 2f / 16f
    }
}
