package io.github.konyaco.tukuai.compat

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f

// Client-only shims; nothing on a dedicated server touches this file.

/** 26.1 draws screens by extracting what to draw; 1.21.1 draws straight away with [GuiGraphics]. */
typealias GuiGraphicsExtractor = GuiGraphics

fun GuiGraphics.text(font: Font, text: Component, x: Int, y: Int, color: Int, shadow: Boolean = true) {
    drawString(font, text, x, y, color, shadow)
}

fun GuiGraphics.text(font: Font, text: FormattedCharSequence, x: Int, y: Int, color: Int, shadow: Boolean = true) {
    drawString(font, text, x, y, color, shadow)
}

fun GuiGraphics.text(font: Font, text: String, x: Int, y: Int, color: Int, shadow: Boolean = true) {
    drawString(font, text, x, y, color, shadow)
}

fun GuiGraphics.centeredText(font: Font, text: Component, x: Int, y: Int, color: Int) = drawCenteredString(font, text, x, y, color)

fun GuiGraphics.centeredText(font: Font, text: FormattedCharSequence, x: Int, y: Int, color: Int) = drawCenteredString(font, text, x, y, color)

fun GuiGraphics.centeredText(font: Font, text: String, x: Int, y: Int, color: Int) = drawCenteredString(font, text, x, y, color)

/** Shows [text] by the mouse once the whole screen is drawn, over everything on it. */
@Suppress("UNUSED_PARAMETER")
fun GuiGraphics.setTooltipForNextFrame(font: Font, text: Component, x: Int, y: Int) {
    Minecraft.getInstance().screen?.setTooltipForNextRenderPass(text)
}

/**
 * Draws the [regionWidth] by [regionHeight] pixels at ([u], [v]) of a [textureWidth] by
 * [textureHeight] texture as a [width] by [height] rectangle at ([x], [y]).
 */
fun GuiGraphics.blitTexture(
    texture: ResourceLocation,
    x: Int,
    y: Int,
    u: Float,
    v: Float,
    width: Int,
    height: Int,
    regionWidth: Int,
    regionHeight: Int,
    textureWidth: Int,
    textureHeight: Int,
) = blit(texture, x, y, width, height, u, v, regionWidth, regionHeight, textureWidth, textureHeight)

/** A key press, as 26.1 hands it to screens. */
class KeyEvent(private val key: Int, private val scancode: Int, private val modifiers: Int) {
    fun key(): Int = key

    fun scancode(): Int = scancode

    fun modifiers(): Int = modifiers
}

/** A mouse button going down or up at a point, as 26.1 hands it to screens. */
class MouseButtonEvent(private val x: Double, private val y: Double, private val button: Int) {
    fun x(): Double = x

    fun y(): Double = y

    fun button(): Int = button
}

/**
 * A screen with 26.1's methods: it draws in [extractRenderState] over what
 * [extractBackground] put down, and gets the mouse and keys as events.
 */
abstract class CompatScreen(title: Component) : Screen(title) {
    protected val mc: Minecraft get() = Minecraft.getInstance()

    /** Set while the game's own [render] runs, which would draw the background a second time. */
    private var drawingWidgets = false

    open fun extractBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.renderBackground(g, mouseX, mouseY, partialTick)
    }

    /** Draws the screen's widgets; screens draw their own things first and then call this. */
    open fun extractRenderState(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawingWidgets = true
        try {
            super.render(g, mouseX, mouseY, partialTick)
        } finally {
            drawingWidgets = false
        }
    }

    final override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        extractBackground(g, mouseX, mouseY, partialTick)
        extractRenderState(g, mouseX, mouseY, partialTick)
    }

    final override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (!drawingWidgets) extractBackground(g, mouseX, mouseY, partialTick)
    }

    open fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean = super.mouseClicked(event.x(), event.y(), event.button())

    open fun mouseReleased(event: MouseButtonEvent): Boolean = super.mouseReleased(event.x(), event.y(), event.button())

    open fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean =
        super.mouseDragged(event.x(), event.y(), event.button(), dragX, dragY)

    open fun keyPressed(event: KeyEvent): Boolean = super.keyPressed(event.key(), event.scancode(), event.modifiers())

    final override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean = mouseClicked(MouseButtonEvent(mouseX, mouseY, button), false)

    final override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean = mouseReleased(MouseButtonEvent(mouseX, mouseY, button))

    final override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean =
        mouseDragged(MouseButtonEvent(mouseX, mouseY, button), dragX, dragY)

    final override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean = keyPressed(KeyEvent(keyCode, scanCode, modifiers))
}

/** Hides and holds the cursor ([org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED]) or shows it again at ([x], [y]). */
fun grabMouse(minecraft: Minecraft, mode: Int, x: Double, y: Double) {
    InputConstants.grabOrReleaseMouse(minecraft.window.window, mode, x, y)
}

/** 1.21.1 can only turn the vertical mouse around. */
@Suppress("UNUSED_PARAMETER")
fun invertMouseX(options: Options): Boolean = false

fun invertMouseY(options: Options): Boolean = options.invertYMouse().get()

fun Camera.position(): Vec3 = position

/**
 * The view rotation and projection of the last frame, kept by the card table renderer
 * while the world is drawn: 1.21.1 does not let anyone else work out the projection.
 */
object ViewMatrices {
    val viewRotationProjection = Matrix4f()
}

/** The camera's rotation and projection, with the camera at the origin. */
@Suppress("UnusedReceiverParameter")
fun Camera.viewRotationProjection(dest: Matrix4f): Matrix4f = dest.set(ViewMatrices.viewRotationProjection)
