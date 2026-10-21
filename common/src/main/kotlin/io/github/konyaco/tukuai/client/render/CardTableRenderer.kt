package io.github.konyaco.tukuai.client.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.SeatPlace
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TablePointer
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.item.DyeColor
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2

/**
 * Draws the dyed cloth, the cards and the display in the middle of a card table. It is
 * attached to the table's middle block and draws across all of its blocks.
 */
open class CardTableRenderer(@Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<CardTableBlockEntity, CardTableRenderer.State> {

    class State : BlockEntityRenderState() {
        var feltColor: Int = DyeColor.GREEN.textureDiffuseColor
        var cards: List<TableCard> = emptyList()
        var back: Int = 0
        var lines: List<FormattedCharSequence> = emptyList()
        var turnSide: Direction? = null

        /** Blocks from the middle block to the edge: 1 for a 3x3 table, 2 for a 5x5 one. */
        var radius: Int = 1
    }

    /** Each table's cards on their way to where the latest update puts them. */
    private val motions = HashMap<BlockPos, CardMotion>()

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
        state.cards = emptyList()
        state.lines = emptyList()
        state.turnSide = null
        if (client == null) {
            motions.remove(table.blockPos)
            return
        }
        val facing = table.blockState.getValue(CardTableBlock.FACING)
        state.radius = (table.blockState.block as? CardTableBlock)?.radius ?: 1
        val seats = SeatPlace.of(facing, state.radius, client.snapshot.seats.size)
        val now = ClientTables.ticks + partialTick.toDouble()
        val cards = motions.getOrPut(table.blockPos) { CardMotion() }.cards(client.scene(seats), now)
        state.cards = TablePointer.dress(table.blockPos, cards)
        val snapshot = client.snapshot
        val lines = if (snapshot.status == TableStatus.LOBBY || client.view == null) {
            listOf(TableScreen.gameName(snapshot.gameId), TableScreen.presetName(snapshot.gameId, snapshot.presetId))
        } else {
            client.ui?.displayLines(client) ?: emptyList()
        }
        state.lines = lines.map { it.visualOrderText }
        state.turnSide = client.ui?.turnSeat(client)?.let { seats.getOrNull(it)?.side }
    }

    override fun submit(state: State, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val light = state.lightCoords
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(CardArt.ATLAS)) { pose, buffer ->
            drawFelt(buffer, pose, light, ARGB_OPAQUE or lighten(state.feltColor), state.radius)
            drawFeltTrim(buffer, pose, light, state.feltColor, state.radius)
            drawDisplay(buffer, pose, light, state.turnSide)
            for (card in state.cards) drawCard(buffer, pose, light, card, state.back)
        }
        if (state.lines.isNotEmpty()) drawDisplayText(poseStack, collector, state.lines)
    }

    /** One tile of the cloth per block, cut short at the wooden rim. */
    private fun drawFelt(buffer: VertexConsumer, pose: PoseStack.Pose, light: Int, color: Int, radius: Int) {
        val tile = CardArt.FELT_SIZE.toFloat()
        for (bx in -radius..radius) for (bz in -radius..radius) {
            val x0 = if (bx == -radius) RIM else 0f
            val x1 = if (bx == radius) 1f - RIM else 1f
            val z0 = if (bz == -radius) RIM else 0f
            val z1 = if (bz == radius) 1f - RIM else 1f
            val uv = floatArrayOf(
                (CardArt.FELT_U + x0 * tile) / CardArt.TEXTURE_WIDTH,
                (CardArt.FELT_V + z0 * tile) / CardArt.TEXTURE_HEIGHT,
                (CardArt.FELT_U + x1 * tile) / CardArt.TEXTURE_WIDTH,
                (CardArt.FELT_V + z1 * tile) / CardArt.TEXTURE_HEIGHT,
            )
            val c = floatArrayOf(bx + (x0 + x1) / 2, TOP + 0.0005f, bz + (z0 + z1) / 2)
            side(buffer, pose, light, c, UP, 0f, EAST, (x1 - x0) / 2, NORTH, (z1 - z0) / 2, uv, color)
        }
    }

    /**
     * A shadow along the rim, where the cloth tucks under the wood, and a fine pale line
     * a hand's width in from it around the playing area, as on a card table's cloth.
     */
    private fun drawFeltTrim(buffer: VertexConsumer, pose: PoseStack.Pose, light: Int, felt: Int, radius: Int) {
        val lo = -radius + RIM
        val hi = radius + 1f - RIM
        fun frame(inset: Float, width: Float, y: Float, color: Int) {
            val a = lo + inset
            val b = hi - inset
            val mid = (a + b) / 2
            val half = (b - a) / 2
            val w = width / 2
            // Along the north and south, then the west and east between them.
            for (z in floatArrayOf(a + w, b - w)) side(buffer, pose, light, floatArrayOf(mid, y, z), UP, 0f, EAST, half, NORTH, w, EDGE, color)
            for (x in floatArrayOf(a + w, b - w)) side(buffer, pose, light, floatArrayOf(x, y, mid), UP, 0f, EAST, w, NORTH, half - width, EDGE, color)
        }
        frame(0f, SHADOW_WIDTH, TOP + 0.0008f, ARGB_OPAQUE or shade(lighten(felt), 0.72f))
        frame(LINE_INSET, LINE_WIDTH, TOP + 0.0008f, ARGB_OPAQUE or lighten(lighten(lighten(felt))))
    }

    /** A dark box in the middle of the table, its top edge lit on the side whose turn it is. */
    private fun drawDisplay(buffer: VertexConsumer, pose: PoseStack.Pose, light: Int, turn: Direction?) {
        val c = floatArrayOf(0.5f, TOP + DISPLAY_HEIGHT / 2, 0.5f)
        val w = DISPLAY_HALF_WIDTH
        val h = DISPLAY_HEIGHT / 2
        side(buffer, pose, light, c, UP, h, EAST, w, NORTH, w, EDGE, DISPLAY_COLOR)
        side(buffer, pose, light, c, SOUTH, w, EAST, w, UP, h, EDGE, DISPLAY_COLOR)
        side(buffer, pose, light, c, NORTH, w, WEST, w, UP, h, EDGE, DISPLAY_COLOR)
        side(buffer, pose, light, c, EAST, w, NORTH, w, UP, h, EDGE, DISPLAY_COLOR)
        side(buffer, pose, light, c, WEST, w, SOUTH, w, UP, h, EDGE, DISPLAY_COLOR)
        if (turn == null) return
        // A lamp strip along the top edge facing the player to act.
        val strip = floatArrayOf(
            0.5f + turn.stepX * (w - TURN_STRIP / 2),
            TOP + DISPLAY_HEIGHT + 0.001f,
            0.5f + turn.stepZ * (w - TURN_STRIP / 2),
        )
        val halfX = if (turn.stepX == 0) w else TURN_STRIP / 2
        val halfZ = if (turn.stepZ == 0) w else TURN_STRIP / 2
        side(buffer, pose, FULL_BRIGHT, strip, UP, 0f, EAST, halfX, NORTH, halfZ, EDGE, TURN_COLOR)
    }

    /** The display's lines on each of its four sides, facing outwards, so every seat can read one. */
    private fun drawDisplayText(poseStack: PoseStack, collector: SubmitNodeCollector, lines: List<FormattedCharSequence>) {
        val font = Minecraft.getInstance().font
        val widest = lines.maxOf { font.width(it) }.coerceAtLeast(1)
        val scale = minOf(TEXT_SCALE, (DISPLAY_HALF_WIDTH * 2 - 0.04f) / widest)
        val top = -(lines.size * LINE_HEIGHT - 1) / 2f
        for (side in Direction.Plane.HORIZONTAL) {
            poseStack.pushPose()
            poseStack.translate(0.5f, TOP + DISPLAY_HEIGHT / 2, 0.5f)
            poseStack.mulPose(Axis.YP.rotation(atan2(side.stepX.toFloat(), side.stepZ.toFloat())))
            poseStack.translate(0f, 0f, DISPLAY_HALF_WIDTH + 0.002f)
            poseStack.scale(scale, -scale, scale)
            lines.forEachIndexed { i, line ->
                collector.submitText(
                    poseStack, -font.width(line) / 2f, top + i * LINE_HEIGHT, line, false,
                    Font.DisplayMode.POLYGON_OFFSET, FULL_BRIGHT, TEXT_COLOR, 0, 0,
                )
            }
            poseStack.popPose()
        }
    }

    /**
     * A card is a thin box: its face on the front, the chosen back behind, paper on the
     * four edges. (r, u, n) are the card's right, up and outward directions. A card with an
     * [TableCard.outline] gets a lit frame around its face, a little larger than the card.
     */
    private fun drawCard(buffer: VertexConsumer, pose: PoseStack.Pose, light: Int, card: TableCard, back: Int) {
        if (card.cube) return drawCube(buffer, pose, light, card)
        val f = CardFrame.of(card)
        val c = f.c
        val r = f.r
        val u = f.u
        val n = f.n
        val neg = { v: FloatArray -> floatArrayOf(-v[0], -v[1], -v[2]) }
        val face = sprite(card.sprite)
        val backUv = sprite(if (card.backSprite >= 0) card.backSprite else back)
        side(buffer, pose, light, c, n, f.halfT, r, f.halfW, u, f.halfH, face, card.tint)
        side(buffer, pose, light, c, neg(n), f.halfT, neg(r), f.halfW, u, f.halfH, backUv)
        side(buffer, pose, light, c, r, f.halfW, neg(n), f.halfT, u, f.halfH, EDGE)
        side(buffer, pose, light, c, neg(r), f.halfW, n, f.halfT, u, f.halfH, EDGE)
        side(buffer, pose, light, c, u, f.halfH, r, f.halfW, neg(n), f.halfT, EDGE)
        side(buffer, pose, light, c, neg(u), f.halfH, r, f.halfW, n, f.halfT, EDGE)
        if (card.outline != 0) {
            // Just behind the face, so only the rim that sticks out past the card shows.
            side(buffer, pose, FULL_BRIGHT, c, n, f.halfT * 0.5f, r, f.halfW + OUTLINE, u, f.halfH + OUTLINE, EDGE, card.outline)
        }
    }

    /** A little block standing on the table, see [TableCard.cube]. */
    private fun drawCube(buffer: VertexConsumer, pose: PoseStack.Pose, light: Int, card: TableCard) {
        val half = CardFrame.CARD_WIDTH * card.scale / 2
        val c = floatArrayOf(card.x + 0.5f, TOP + card.lift + half, card.z + 0.5f)
        val sideUv = square(card.sprite)
        for ((nx, nz) in listOf(card.upX to card.upZ, -card.upZ to card.upX, -card.upX to -card.upZ, card.upZ to -card.upX)) {
            // a × up = normal, so each side faces out with its texture upright.
            side(buffer, pose, light, c, floatArrayOf(nx, 0f, nz), half, floatArrayOf(nz, 0f, -nx), half, UP, half, sideUv, card.tint)
        }
        side(buffer, pose, light, c, UP, half, EAST, half, NORTH, half, square(card.backSprite), card.tint)
    }

    /** u0, v0, u1, v1 of the square top of a card's cell in the atlas. */
    private fun square(sprite: Int): FloatArray {
        val u0 = CardArt.u(sprite).toFloat() / CardArt.TEXTURE_WIDTH
        val v0 = CardArt.v(sprite).toFloat() / CardArt.TEXTURE_HEIGHT
        return floatArrayOf(u0, v0, u0 + CardArt.WIDTH.toFloat() / CardArt.TEXTURE_WIDTH, v0 + CardArt.WIDTH.toFloat() / CardArt.TEXTURE_HEIGHT)
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
        color: Int = -1,
    ) {
        fun corner(sa: Float, sb: Float, tu: Float, tv: Float) {
            buffer.addVertex(
                pose,
                c[0] + normal[0] * d + a[0] * ha * sa + b[0] * hb * sb,
                c[1] + normal[1] * d + a[1] * ha * sa + b[1] * hb * sb,
                c[2] + normal[2] * d + a[2] * ha * sa + b[2] * hb * sb,
            )
                .setColor(color)
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

    companion object {
        private fun shade(rgb: Int, k: Float): Int {
            fun channel(shift: Int) = ((rgb shr shift and 0xFF) * k).toInt().coerceIn(0, 255) shl shift
            return channel(16) or channel(8) or channel(0)
        }

        /** Dye colours are dark for cloth seen from above, so mix in a quarter of white. */
        private fun lighten(rgb: Int): Int {
            fun channel(shift: Int) = ((rgb shr shift and 0xFF) * 3 + 255) / 4 shl shift
            return channel(16) or channel(8) or channel(0)
        }

        /** Thickness of a card at scale 1, in blocks. */
        const val CARD_THICKNESS = CardFrame.CARD_THICKNESS

        /** How far the frame around an outlined card reaches past its edges, in blocks. */
        private const val OUTLINE = 0.008f

        /** The paper patch: one spot of plain colour, for card edges and anything tinted. */
        private val EDGE = floatArrayOf(
            CardArt.EDGE_U.toFloat() / CardArt.TEXTURE_WIDTH,
            CardArt.EDGE_V.toFloat() / CardArt.TEXTURE_HEIGHT,
            CardArt.EDGE_U.toFloat() / CardArt.TEXTURE_WIDTH,
            CardArt.EDGE_V.toFloat() / CardArt.TEXTURE_HEIGHT,
        )

        private val UP = floatArrayOf(0f, 1f, 0f)
        private val NORTH = floatArrayOf(0f, 0f, -1f)
        private val SOUTH = floatArrayOf(0f, 0f, 1f)
        private val EAST = floatArrayOf(1f, 0f, 0f)
        private val WEST = floatArrayOf(-1f, 0f, 0f)

        private const val ARGB_OPAQUE = 0xFF000000.toInt()
        private const val FULL_BRIGHT = 0xF000F0

        /** Half the width of the display box in the middle of the table, in blocks. */
        const val DISPLAY_HALF_WIDTH = 0.35f

        /** Height of the display box; games may lay cards on its top. */
        const val DISPLAY_HEIGHT = 0.2f
        private const val DISPLAY_COLOR = 0xFF33373D.toInt()
        private const val TURN_STRIP = 0.05f
        private const val TURN_COLOR = 0xFFFFC23A.toInt()
        private const val TEXT_COLOR = 0xFFFFE6A0.toInt()

        /** Blocks per font pixel at most; long lines shrink to fit the display. */
        private const val TEXT_SCALE = 0.0085f
        private const val LINE_HEIGHT = 10f

        private const val TOP = CardTableBlock.TOP_HEIGHT

        private const val SHADOW_WIDTH = 0.035f
        private const val LINE_INSET = 0.2f
        private const val LINE_WIDTH = 0.018f

        /** Width of the bare wooden rim around the cloth, in blocks. */
        private const val RIM = 2f / 16f
    }
}
