package game.bot.scripts.skills

import game.skill.mining.Ore
import io.luna.game.model.Position
import io.luna.game.model.mob.bot.brain.BotPersonality
import kotlin.time.Duration.Companion.seconds

/**
 * Session preferences for ordinary 377 mining. Competition changes the choice of rock, never its ownership or rolls.
 * Search grows only after the local cluster has no acceptable rocks, and contracts after mining starts.
 *
 * @author TheLining
 */
internal class MiningBehavior(personality: BotPersonality, preferred: Set<Ore>, ores: Set<Ore>) {
    enum class Competition { YIELDING, BALANCED, ASSERTIVE }

    private val dexterity = personality.dexterity.coerceIn(0.0, 1.0)
    private val tiers = preferred.associateWith { 0 } +
            (ores - preferred).sortedByDescending { it.level }.mapIndexed { index, ore -> ore to index + 1 }

    val competition = when {
        personality.kindness - personality.confidence >= 0.25 -> Competition.YIELDING
        personality.confidence - personality.kindness >= 0.25 -> Competition.ASSERTIVE
        else -> Competition.BALANCED
    }
    val searchTimeout = (90 + 90 * (1.0 - dexterity)).seconds
    var radius = LOCAL_RADIUS
        private set

    /** Lower scores win. Balanced miners prefer free fallback ore to a contested preferred rock. */
    fun score(ore: Ore, competitors: Int, distance: Int): Double? {
        val tier = tiers[ore] ?: return null
        val penalty = when (competition) {
            Competition.YIELDING -> if (competitors > 0) return null else 0
            Competition.BALANCED -> if (competitors > 1) return null else 1_000
            Competition.ASSERTIVE -> if (competitors > 2) return null else 45
        }
        return tier * 100.0 + competitors * penalty + distance * (0.5 + 1.5 * dexterity)
    }

    /** Returns false once the bot has checked its entire visible neighborhood. */
    fun expand(): Boolean {
        if (radius == Position.VIEWING_DISTANCE) return false
        radius = minOf(Position.VIEWING_DISTANCE, radius + 4)
        return true
    }

    fun settle() { radius = LOCAL_RADIUS }

    companion object {
        const val LOCAL_RADIUS = 6
    }
}
