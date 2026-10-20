package io.github.konyaco.tukuai.client.render

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.TableCard
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Moves the cards on one table from where they were to where the latest update puts
 * them, instead of letting them jump.
 *
 * Each new card is paired with a card that was shown before: first the card with the same
 * [TableCard.key], for games whose decks hold several cards with one face; then a card
 * with the same face, so a played card leaves the hand it came from; then the nearest card
 * left over, so a hand that shrinks closes up and a hand turned face up flips where it
 * stood. Cards with a key that went away are not paired by face or nearness, since they
 * are known to have gone somewhere else. A card with no partner flies in from its
 * [TableCard.fromX], [TableCard.fromZ], one after the other, so a new game looks dealt
 * from a deck.
 */
class CardMotion {
    private var target: List<TableCard>? = null
    private var start: List<TableCard> = emptyList()
    private var delays: FloatArray = FloatArray(0)
    private var dealt: BooleanArray = BooleanArray(0)
    private var startTime = 0.0

    /** The cards to draw at [now], in ticks, while heading for [scene]. */
    fun cards(scene: List<TableCard>, now: Double): List<TableCard> {
        val previous = target
        if (previous == null) {
            // First sight of this table: show it as it is.
            retarget(scene, scene, now)
        } else if (scene !== previous && scene != previous) {
            retarget(current(now), scene, now)
        }
        return current(now)
    }

    private fun retarget(shown: List<TableCard>, scene: List<TableCard>, now: Double) {
        val partner = arrayOfNulls<TableCard>(scene.size)
        // Cards with a key only ever pair with the card of the same key.
        val used = BooleanArray(shown.size) { shown[it].key >= 0 }
        val byKey = HashMap<Int, Int>()
        shown.forEachIndexed { i, card -> if (card.key >= 0) byKey.putIfAbsent(card.key, i) }
        scene.forEachIndexed { i, card ->
            if (card.key < 0) return@forEachIndexed
            val j = byKey.remove(card.key) ?: return@forEachIndexed
            partner[i] = shown[j]
        }
        // The same face: the same physical card. Backs all look alike, so they wait for the next pass.
        val byFace = HashMap<Int, Int>()
        shown.forEachIndexed { i, card -> if (!used[i] && !CardArt.isBack(card.sprite)) byFace.putIfAbsent(card.sprite, i) }
        scene.forEachIndexed { i, card ->
            if (partner[i] != null || CardArt.isBack(card.sprite)) return@forEachIndexed
            val j = byFace.remove(card.sprite) ?: return@forEachIndexed
            partner[i] = shown[j]
            used[j] = true
        }
        scene.forEachIndexed { i, card ->
            if (partner[i] != null) return@forEachIndexed
            var best = -1
            var bestDistance = NEAR
            shown.forEachIndexed { j, old ->
                if (used[j]) return@forEachIndexed
                val distance = hypot(old.x - card.x, old.z - card.z)
                if (distance < bestDistance) {
                    best = j
                    bestDistance = distance
                }
            }
            if (best >= 0) {
                partner[i] = shown[best]
                used[best] = true
            }
        }

        delays = FloatArray(scene.size)
        dealt = BooleanArray(scene.size) { i -> partner[i] == null && (scene[i].fromX != scene[i].x || scene[i].fromZ != scene[i].z) }
        val pile = dealt.count { it }
        var order = 0
        start = scene.mapIndexed { i, card ->
            partner[i] ?: if (!dealt[i]) {
                card
            } else {
                // Waiting face down in a pile at the spot it comes from, the next card to go on top.
                delays[i] = minOf(order * DEAL_GAP, MAX_DEAL_DELAY)
                card.copy(x = card.fromX, z = card.fromZ, tilt = 0f, layer = 0, lift = (pile - 1 - order++) * PILE_STEP)
            }
        }
        target = scene
        startTime = now
    }

    private fun current(now: Double): List<TableCard> {
        val scene = target ?: return emptyList()
        var moving = false
        val cards = scene.mapIndexed { i, to ->
            val from = start[i]
            val t = ((now - startTime - delays[i]) / DURATION).toFloat().coerceIn(0f, 1f)
            if (t < 1f) moving = true
            if (from === to || t >= 1f) to else between(from, to, ease(t), if (dealt[i] && t < 0.5f) CardArt.back() else to.sprite)
        }
        return if (moving) cards else scene
    }

    private fun between(from: TableCard, to: TableCard, e: Float, sprite: Int): TableCard {
        val fromAngle = atan2(from.upX, from.upZ)
        var turn = atan2(to.upX, to.upZ) - fromAngle
        while (turn > PI) turn -= (2 * PI).toFloat()
        while (turn < -PI) turn += (2 * PI).toFloat()
        val angle = fromAngle + turn * e
        val distance = hypot(to.x - from.x, to.z - from.z)
        val arc = ARC * minOf(distance, 1f) * sin(PI.toFloat() * e)
        return to.copy(
            x = lerp(from.x, to.x, e),
            z = lerp(from.z, to.z, e),
            upX = sin(angle),
            upZ = cos(angle),
            sprite = sprite,
            scale = lerp(from.scale, to.scale, e),
            tilt = lerp(from.tilt, to.tilt, e),
            lift = lerp(from.lift, to.lift, e) + arc,
        )
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Quick off the mark, gentle on landing. */
    private fun ease(t: Float): Float = 1 - (1 - t) * (1 - t) * (1 - t)

    private companion object {
        /** Ticks a card takes to reach its new place. */
        const val DURATION = 7f

        /** Ticks between one dealt card leaving the pile and the next. */
        const val DEAL_GAP = 0.4f
        const val MAX_DEAL_DELAY = 30f

        /** How far apart, in blocks, an old and a new card may be and still count as the same card. */
        const val NEAR = 0.6f

        /** How high a card flying across a whole block rises, in blocks. */
        const val ARC = 0.2f

        /** How much higher each card in the pile of cards waiting to be dealt sits. */
        const val PILE_STEP = 0.003f
    }
}
