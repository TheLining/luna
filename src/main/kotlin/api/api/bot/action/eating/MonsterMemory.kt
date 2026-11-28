package api.bot.action.eating

import api.attr.Attr
import api.attr.getValue
import api.attr.setValue
import io.luna.game.model.mob.Npc
import io.luna.game.model.mob.bot.Bot
import io.luna.game.model.mob.bot.memory.BotKnowHowMemory
import io.luna.game.model.mob.bot.memory.BotKnowHowMemory.Kind
import io.luna.game.model.mob.combat.damage.CombatDamageType
import java.util.concurrent.ConcurrentHashMap

/**
 * What a bot has felt from monsters: the biggest hit each kind of monster has dealt it and how each kind attacks,
 * and when it last took any hit at all.
 *
 * A player's sense of how hard a monster hits comes from two places, what they already know about it and what it has
 * done to them. [expectedMaxHit] combines both, so a bot that knows little about a monster underestimates it until a
 * big hit, a few kills or a death teaches it otherwise.
 *
 * What a bot learns about monsters is kept in its [BotKnowHowMemory], so it is still there the next time the bot logs
 * in. Only [lastHitTick] belongs to the session.
 *
 * @author lare96
 */
class MonsterMemory(private val knowHow: BotKnowHowMemory) {

    companion object {

        /**
         * The session part of each bot's monster memory, made when it is first needed.
         */
        private var Bot.heldMonsterMemory by Attr.nullableObj(MonsterMemory::class)

        /**
         * The monster memory of each bot.
         */
        val Bot.monsterMemory: MonsterMemory
            get() = heldMonsterMemory ?: MonsterMemory(memory.knowHow).also { heldMonsterMemory = it }

        /**
         * The kind each NPC id is, by the name and combat level a player sees.
         */
        private val kinds = ConcurrentHashMap<Int, Kind>()

        /**
         * How much of a monster's real maximum hit a bot knows about before it has been hit hard by one.
         *
         * @param intelligence The bot's intelligence, from `0.0` to `1.0`.
         * @return The knowledge [expectedMaxHit] works from.
         */
        fun knowledge(intelligence: Double): Double = 0.4 + 0.6 * intelligence

        /**
         * Returns the kind of monster [npc] is. Monsters with the same name and combat level are one kind, whatever
         * their ids.
         */
        fun kind(npc: Npc): Kind = kinds.computeIfAbsent(npc.id) { Kind(npc.def().name, npc.def().combatLevel) }
    }

    /**
     * The tick the bot last took damage from anything, or `-1` if it hasn't been hit this session.
     */
    var lastHitTick = -1L
        private set

    /**
     * Records an attack made on the bot on [tick]. Only attacks from NPCs teach the bot about monsters, and a miss
     * still shows how the monster attacks.
     *
     * @param npc The NPC that attacked, or `null` if something else did.
     * @param style How it attacked.
     * @param amount The damage dealt.
     * @param tick The tick the hit landed.
     */
    fun remember(npc: Npc?, style: CombatDamageType, amount: Int, tick: Long) {
        if (npc != null) {
            knowHow.hit(kind(npc), style, amount)
        }
        if (amount > 0) {
            lastHitTick = tick
        }
    }

    /**
     * Records that the bot killed [npc].
     */
    fun killed(npc: Npc) = knowHow.killed(kind(npc))

    /**
     * Records that [npc] killed the bot.
     */
    fun killedBy(npc: Npc) = knowHow.killedBy(kind(npc))

    /**
     * Returns the biggest hit a monster of [npc]'s kind has dealt the bot, or `0` if none has hit it yet.
     */
    fun biggestHit(npc: Npc): Int = knowHow.biggestHit(kind(npc))

    /**
     * Returns the style [npc]'s kind usually attacks with, as far as the bot has seen for itself, or `null` if it
     * hasn't seen enough to tell.
     */
    fun usualStyle(npc: Npc): CombatDamageType? = knowHow.usualStyle(kind(npc))

    /**
     * Estimates the most [npc] can hit the bot for.
     *
     * A bot with full [knowledge] knows the monster's maximum hit before it fights it. Less knowledge scales that
     * down, the way a player who hasn't looked a monster up only has a rough idea, and experience of the kind fills
     * the gap in. Either way, the biggest hit the bot has actually taken from it wins, and a bot the kind has killed
     * expects a bit more than that for a while.
     *
     * @param npc The monster.
     * @param knowledge How much of the monster's real maximum hit the bot knows about, from `0.0` to `1.0`.
     * @return The expected maximum hit, at least `1`.
     */
    fun expectedMaxHit(npc: Npc, knowledge: Double): Int =
        knowHow.expectedMaxHit(kind(npc), npc.combatDef().maximumHit, knowledge)
}
