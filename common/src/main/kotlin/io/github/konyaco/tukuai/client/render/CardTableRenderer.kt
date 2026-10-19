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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the dyed cloth and the cards lying on a card table. It is attached to the
 * table's middle block and draws across all nine.
 */
open class CardTableRenderer(@Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<CardTableBlockEntity, CardTableRenderer.State> {

    class State : BlockEntityRenderState() {
        var feltColor: Int = DyeColor.GREEN.textureDiffuseColor
        var cards: List<TableCard> = emptyList()
        var back: Int = 0
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
        state.back = CardArt.back()
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
            for (card in state.cards) drawCard(buffer, pose, light, card, state.back)
        }
    }

    /**
     * A card is a thin box: its face on the front, the chosen back behind, paper on the
     * four edges. (r, u, n) are the card's right, up and outward directions.
     */
    private fun drawCard(buffer: VertexConsumer, pose: PoseStack.Pose, light: Int, card: TableCard, back: Int) {
        val halfW = CARD_WIDTH * card.scale / 2
        val halfH = CARD_WIDTH * card.scale * CardArt.HEIGHT / CardArt.WIDTH / 2
        val halfT = CARD_THICKNESS * card.scale / 2
        val tilt = Math.toRadians(card.tilt.toDouble())
        val sin = sin(tilt).toFloat()
        val cos = cos(tilt).toFloat()
        // Right of the card as seen by someone reading it: the "up" direction turned clockwise.
        val r = floatArrayOf(-card.upZ, 0f, card.upX)
        val u = floatArrayOf(card.upX * cos, sin, card.upZ * cos)
        val n = floatArrayOf(-card.upX * sin, cos, -card.upZ * sin)
        // Card positions are measured from the middle of the table. A standing card rests on
        // its bottom edge; flat ones lift a little over the cards before them in their row.
        val c = floatArrayOf(
            card.x + 0.5f,
            1f + sin * halfH + cos * halfT + if (card.tilt == 0f) card.layer * LAYER_LIFT else 0f,
            card.z + 0.5f,
        )
        val neg = { v: FloatArray -> floatArrayOf(-v[0], -v[1], -v[2]) }
        val face = sprite(card.sprite)
        val backUv = sprite(back)
        val edge = floatArrayOf(EDGE_U, EDGE_V, EDGE_U, EDGE_V)
        side(buffer, pose, light, c, n, halfT, r, halfW, u, halfH, face)
        side(buffer, pose, light, c, neg(n), halfT, neg(r), halfW, u, halfH, backUv)
        side(buffer, pose, light, c, r, halfW, neg(n), halfT, u, halfH, edge)
        side(buffer, pose, light, c, neg(r), halfW, n, halfT, u, halfH, edge)
        side(buffer, pose, light, c, u, halfH, r, halfW, neg(n), halfT, edge)
        side(buffer, pose, light, c, neg(u), halfH, r, halfW, n, halfT, edge)
    }

    /** u0, v0, u1, v1 of a card in the atlas. */
    private fun sprite(sprite: Int): FloatArray {
        val u0 = CardArt.u(sprite).toFloat() / CardArt.TEXTURE_WIDTH
        val v0 = CardArt.v(sprite).toFloat() / CardArt.TEXTURE_HEIGHT
        return floatArrayOf(u0, v0, u0 + CardArt.WIDTH.toFloat() / CardArt.TEXTURE_WIDTH, v0 + CardArt.HEIGHT.toFloat() / CardArt.TEXTURE_HEIGHT)
    }

    /**
     * One face of a box centred on [c]: it lies [d] out along [normal] and spans [ha] along
     * [a] and [hb] along [b], where a × b = normal so it faces outwards. The texture's top
     * is towards +b.
     */
    private fun side(
        buffer: VertexConsumer,
        pose: PoseStack.Pose,
        light: Int,
        c: FloatArray,
        normal: FloatArray,
        d: Float,
        a: FloatArray,
        ha: Float,
        b: FloatArray,
        hb: Float,
        uv: FloatArray,
    ) {
        fun corner(sa: Float, sb: Float, tu: Float, tv: Float) {
            buffer.addVertex(
                pose,
                c[0] + normal[0] * d + a[0] * ha * sa + b[0] * hb * sb,
                c[1] + normal[1] * d + a[1] * ha * sa + b[1] * hb * sb,
                c[2] + normal[2] * d + a[2] * ha * sa + b[2] * hb * sb,
            )
                .setColor(-1)
                .setUv(tu, tv)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, normal[0], normal[1], normal[2])
        }
        corner(-1f, -1f, uv[0], uv[3])
        corner(1f, -1f, uv[2], uv[3])
        corner(1f, 1f, uv[2], uv[1])
        corner(-1f, 1f, uv[0], uv[1])
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

        /** Thickness of a card at scale 1, in blocks. */
        private const val CARD_THICKNESS = 0.008f

        /** How much higher each flat card in a row sits than the one before it. */
        private const val LAYER_LIFT = 0.002f

        private val EDGE_U = CardArt.EDGE_U.toFloat() / CardArt.TEXTURE_WIDTH
        private val EDGE_V = CardArt.EDGE_V.toFloat() / CardArt.TEXTURE_HEIGHT

        /** Width of the bare wooden rim around the cloth, in blocks. */
        private const val RIM = 2f / 16f
    }
}
