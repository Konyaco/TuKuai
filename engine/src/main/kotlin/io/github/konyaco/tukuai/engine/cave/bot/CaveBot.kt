package io.github.konyaco.tukuai.engine.cave.bot

import io.github.konyaco.tukuai.engine.cave.CaveAction
import io.github.konyaco.tukuai.engine.cave.CaveCard
import io.github.konyaco.tukuai.engine.cave.CaveView
import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import kotlin.random.Random

/**
 * A computer in the cave. It weighs what one more card would likely bring against what it
 * stands to drop, from the cards it knows are still face down, and guesses how many of the
 * others go on with it. The hard one also looks at the scores: near the end it takes risks
 * when behind and none when it has the game won. The easy one goes by a hunch.
 */
class CaveBot(private val level: BotLevel, seed: Long) : Bot<CaveView, CaveAction> {
    private val random = Random(seed)

    override fun act(view: CaveView): CaveAction {
        val me = view.seat ?: return CaveAction.Deeper
        val unseen = view.unseen()
        // With nothing left to turn up the round ends safely anyway.
        if (unseen.isEmpty()) return CaveAction.Deeper
        val danger = view.danger()
        if (danger == 0.0) return CaveAction.Deeper
        val pack = view.pack[me]
        if (level == BotLevel.EASY) {
            val nerve = 0.15 + random.nextDouble() * 0.3
            return if (danger * 2 + pack / 40.0 > nerve * 2 + 0.4) CaveAction.Back else CaveAction.Deeper
        }
        val others = view.inside.count { it } - 1
        // The more dangerous it gets, the more of the others turn back.
        val goOn = (0.8 - danger * 1.5).coerceIn(0.2, 0.8)
        val staying = 1 + others * goOn
        val leaving = 1 + others * (1 - goOn)
        val gain = unseen.sumOf { (it as? CaveCard.Vein)?.value ?: 0 } / staying / unseen.size
        val leaveNow = pack + view.leftover / leaving
        val goDeeper = (1 - danger) * (pack + gain + view.leftover / leaving)
        if (level == BotLevel.HARD) standing(view, me, leaveNow)?.let { return it }
        val noise = if (level == BotLevel.NORMAL) (random.nextDouble() - 0.5) * 2 else 0.0
        return if (goDeeper + noise > leaveNow) CaveAction.Deeper else CaveAction.Back
    }

    /**
     * In the last round, the scores can settle it: go back when what is already won is enough
     * to stay ahead whatever the others bring out, and press on when going back now could not
     * win anyway. Null when the scores do not decide.
     */
    private fun standing(view: CaveView, me: Int, leaveNow: Double): CaveAction? {
        if (view.round < view.rules.rounds) return null
        val mine = view.chest[me] + leaveNow
        val others = view.chest.indices.filter { it != me }
        // The most anyone else could still end with, if they all came out with what they carry and more.
        val best = others.maxOfOrNull { view.chest[it] + view.pack[it] + if (view.inside[it]) view.leftover + 10 else 0 } ?: 0
        if (mine > best) return CaveAction.Back
        val safe = others.maxOfOrNull { view.chest[it] } ?: 0
        if (mine < safe && view.inside.count { it } > 1) return CaveAction.Deeper
        return null
    }
}
