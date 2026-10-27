package game.bot.scripts.skills

import io.luna.game.model.mob.bot.Bot
import api.bot.script.BotScriptData
import api.bot.Suspendable.waitFor
import api.bot.script.ZonedBotScript.Companion.ZonedBotScriptData
import api.bot.skill.SkillingBotScript
import api.bot.skill.SkillingTool
import api.bot.zone.SubZone
import api.bot.webwalk.plan.BankAccess
import engine.bot.gear.BotGearLocator
import engine.bot.gear.BotItemTracker.Companion.itemTracker
import game.content.craftingGuild.CraftingGuild
import io.luna.game.model.item.Equipment
import api.predef.*
import com.google.gson.JsonObject
import engine.bot.coordinator.skill.RunecraftingScriptFactory
import engine.bot.coordinator.skill.SmithingScriptFactory
import game.skill.mining.Ore
import game.skill.mining.Pickaxe
import game.skill.mining.mineOre.MineOreAction
import game.content.miningGuild.MiningGuild
import io.luna.game.model.EntityState
import io.luna.game.model.Position
import io.luna.game.model.mob.interact.InteractionPolicy
import io.luna.game.model.`object`.GameObject
import java.util.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [SkillingBotScript] that trains Mining by mining configured ore types inside selected zones.
 *
 * This script supplies the Mining-specific pieces required by [SkillingBotScript], including valid rock object ids,
 * pickaxe selection, emergency pickaxe fallback, level requirements, and serialization data.
 *
 * Prefers configured ores within a small cluster, uses fallback ores when they are unavailable or contested, and
 * widens its visible search before scouting or moving mines. Kindness and confidence govern competition; dexterity
 * governs patience. Active mining is never interrupted just because another rock becomes available.
 * Crafting Guild sessions reserve the chest slot for an owned brown apron and recheck
 * the native entry requirements before every work-zone trip, including return travel after banking.
 *
 * @param bot The bot running this script.
 * @param ores The ore types this bot is allowed to mine.
 * @param duration How long this script should continue running before completing normally.
 * @param zones The candidate zones this script may operate in.
 * @param preferredOres Preferred ores; other configured ores are fallbacks, ordered by their Mining requirement.
 *
 * @author lare96
 */
class MineBotScript(bot: Bot, val ores: Set<Ore>, duration: Duration, zones: MutableList<SubZone>,
                    preferredOres: Set<Ore> = ores) :
    SkillingBotScript<GameObject>(bot, duration, zones, bot.mining) {

    /** Other configured ores are fallbacks, ordered from the highest requirement down. */
    val preferredOres: Set<Ore> = preferredOres.intersect(ores).ifEmpty { ores }
    internal val behavior = MiningBehavior(bot.personality, this.preferredOres, ores)
    override val targetSearchTimeout = behavior.searchTimeout
    override val reuseTargetOptions = false

    private var cluster: Position? = null
    private var surveyZone: SubZone? = null
    private var sites: List<MiningSites.Rock> = emptyList()
    private val startingSites = ArrayDeque<MiningSites.Rock>()
    private val surveyed = HashSet<Position>()
    private var startingSitePending = true
    private val retryAfter = HashMap<Position, Long>()

    companion object {

        /**
         * Serializable data used to save and restore a [MineBotScript].
         *
         * This extends [ZonedBotScriptData] with the configured Mining ore set, allowing the script to resume with the
         * same target ores, remaining duration, and candidate zones.
         */
        class MineData : ZonedBotScriptData() {

            /**
             * The ore types this script is allowed to mine.
             */
            var ores = emptySet<Ore>()
            var preferredOres = emptySet<Ore>()

            override fun load(data: JsonObject) {
                super.load(data)
                ores = loadEnumSet("ores", data) { Ore.valueOf(it) }
                // Older saves gave every configured ore equal priority.
                preferredOres = if (data.has("preferredOres"))
                    loadEnumSet("preferredOres", data) { Ore.valueOf(it) }.intersect(ores).ifEmpty { ores }
                else ores
                // Mining uses working stands near the rocks rather than shared village/combat/teleport anchors.
                zones = zones.map { zone ->
                    when (zone) {
                        SubZone.DWARVEN_MINE -> SubZone.DWARVEN_MINE_ROCKS
                        SubZone.SHILO_VILLAGE -> SubZone.SHILO_VILLAGE_MINE
                        SubZone.ESSENCE_MINE -> SubZone.ESSENCE_MINE_ROCKS
                        SubZone.AL_KHARID_MINE -> if (Ore.GOLD in preferredOres &&
                            preferredOres.none { it.level > Ore.GOLD.level }) SubZone.AL_KHARID_GOLD_MINE
                            else SubZone.AL_KHARID_NORTH_MINE
                        else -> zone
                    }
                }.distinct().toMutableList()
            }

            override fun save(data: JsonObject) {
                super.save(data)
                saveEnumSet("ores", data, ores)
                saveEnumSet("preferredOres", data, preferredOres)
            }
        }
    }

    /**
     * Creates a [MineBotScript] from persisted script data.
     *
     * @param bot The bot running this script.
     * @param data The saved Mining script data to restore from.
     */
    constructor(bot: Bot, data: MineData) : this(bot, data.ores, data.duration, data.zones, data.preferredOres)

    /**
     * The rock object ids that match the configured [ores].
     *
     * These ids are precomputed once so [find] can quickly test nearby objects without rebuilding the target id set on
     * every search cycle.
     */
    private var rockObjectIds: Set<Int> = run {
        val ids = HashSet<Int>()
        for (ore in ores) {
            ids.addAll(Ore.ORE_MAP[ore])
        }
        bot.log("Mining ores: $ores")
        ids
    }

    override suspend fun equipment(): BotGearLocator? = super.equipment()?.also {
        if (SubZone.CRAFTING_GUILD_MINE in originalZones && bot.itemTracker.contains(CraftingGuild.BROWN_APRON)) {
            it.equipment[Equipment.CHEST] = CraftingGuild.BROWN_APRON
        }
    }

    override suspend fun travelToWorkZone(zone: SubZone, bank: BankAccess): Boolean {
        if (zone == SubZone.CRAFTING_GUILD_MINE && !CraftingGuild.canEnter(bot)) return false
        if (zone == SubZone.MINING_GUILD && bot.mining.level < MiningGuild.REQUIRED_LEVEL) return false
        return super.travelToWorkZone(zone, bank)
    }

    /** Retains the factory's preferred destination even when the base initialization shuffles candidate zones. */
    override fun selectWorkZone(): SubZone? = originalZones.firstOrNull {
        it in zones && (it != SubZone.MINING_GUILD || bot.mining.level >= MiningGuild.REQUIRED_LEVEL)
    }

    override suspend fun onNewActiveZone(lastZone: SubZone?) {
        surveyZone = null
        resetSearch()
    }

    override suspend fun onBankOpenSkilling(initial: Boolean) { resetSearch() }

    private fun resetSearch() {
        cluster = null
        surveyed.clear()
        startingSitePending = true
        retryAfter.clear()
        behavior.settle()
    }

    /** Observes only visible rocks and players. The mine boundary also excludes neighboring chambers. */
    override suspend fun find(searchBase: Position, searchRadius: Int): MutableCollection<GameObject> {
        if (!isActionSafe() || forceBanking || bot.inventory.isFull) return mutableListOf()
        val zone = activeZone ?: return mutableListOf()
        if (zone == SubZone.MINING_GUILD && bot.mining.level < MiningGuild.REQUIRED_LEVEL) return mutableListOf()
        if (surveyZone != zone) {
            sites = MiningSites.inZone(zone).filter { it.ore in ores }
            startingSites.clear()
            surveyZone = zone
        }
        if (startingSitePending) {
            startingSitePending = false
            // Each eligible starting rock gets a turn across bank trips; bots spread across the whole mine.
            startingSites.removeIf { it.ore.level > bot.mining.level }
            if (startingSites.isEmpty()) {
                val eligible = sites.filter { it.ore.level <= bot.mining.level }
                val preferred = eligible.filter { it.ore in preferredOres }.ifEmpty { eligible }
                startingSites.addAll(preferred.shuffled())
            }
            startingSites.pollFirst()?.let { visitSite(zone, it) }
            if (!isActionSafe() || bot.inventory.isFull) return mutableListOf()
        }
        observeSites()
        val origin = cluster?.takeIf { it.isWithinDistance(bot.position, Position.VIEWING_DISTANCE) }
            ?: bot.position.also { cluster = it; behavior.settle() }
        val now = System.nanoTime()
        retryAfter.entries.removeIf { it.value <= now }
        val miners = visibleMiners()
        val candidates = world.locator.findObjects(bot.position, Position.VIEWING_DISTANCE) { rock ->
            validRock(rock) && rock.position.isWithinDistance(origin, behavior.radius) &&
                    rock.position !in retryAfter
        }.mapNotNull { rock ->
            val score = behavior.score(Ore.ROCK_MAP.getValue(rock.id), miners[rock.position] ?: 0,
                                      bot.position.computeLongestDistance(rock.position))
            score?.let { rock to it }
        }.shuffled().sortedBy { it.second }.mapTo(mutableListOf()) { it.first }
        if (candidates.isNotEmpty()) return candidates
        if (behavior.expand()) {
            bot.log("No suitable rocks in my cluster; expanding mining search to ${behavior.radius} tiles.")
        } else {
            scout(zone)
        }
        return mutableListOf()
    }

    /** find already ranked these rocks; the generic distance/shuffle ordering would lose ore preferences. */
    override fun orderTargetOptions(options: MutableList<GameObject>) = Unit

    /** A working rock remains the focus even if a better ore respawns during an unsuccessful swing. */
    override suspend fun refocus(): Boolean = bot.actions.first(MineOreAction::class.java) == null

    /** Pausing clears the cached focus; an unfinished native action still owns the next rock decision on resume. */
    override suspend fun beforeZoneWork(): Boolean = bot.actions.first(MineOreAction::class.java) != null

    private fun validRock(rock: GameObject): Boolean {
        val zone = activeZone ?: return false
        val ore = Ore.ROCK_MAP[rock.id] ?: return false
        if (rock.state != EntityState.ACTIVE || rock.id !in rockObjectIds || ore.level > bot.mining.level ||
            !zone.area.contains(rock.position) || !bot.position.isViewable(rock.position)) return false
        if (zone == SubZone.MINING_GUILD) return bot.mining.level >= MiningGuild.REQUIRED_LEVEL
        // The Dwarven Mine bounding box touches the separately gated guild chamber.
        return !SubZone.MINING_GUILD.area.contains(rock.position)
    }

    /** Actual mining actions identify competitors, including human players; standing beside a rock doesn't claim it. */
    private fun visibleMiners(): Map<Position, Int> =
        world.locator.findPlayers(bot.position, Position.VIEWING_DISTANCE) {
            it !== bot && it.state == EntityState.ACTIVE && it.position.isViewable(bot.position)
        }.mapNotNull { player -> player.actions.first(MineOreAction::class.java)?.rockObj }
            .filter { it.state == EntityState.ACTIVE && it.position.isViewable(bot.position) }
            .groupingBy { it.position }.eachCount()

    private fun canStart(rock: GameObject): Boolean =
        isActionSafe() && !bot.inventory.isFull && validRock(rock) &&
                behavior.score(Ore.ROCK_MAP.getValue(rock.id), visibleMiners()[rock.position] ?: 0, 0) != null

    override suspend fun onAssignFocus(newFocus: GameObject): Boolean =
        newFocus.position !in retryAfter && canStart(newFocus)

    override suspend fun interactWithTarget(option: Int, target: GameObject): Boolean {
        val experience = bot.mining.experience
        val clicked = handler.interactions.interact(option, target) { canStart(target) }
        // A click can reach the rock without starting mining. Confirm the native action, not an ore success deadline.
        if (clicked) {
            waitFor(3.seconds) {
                bot.actions.first(MineOreAction::class.java)?.rockObj === target ||
                        bot.mining.experience > experience || target.state != EntityState.ACTIVE || !isActionSafe()
            }
        }
        val started = clicked && (bot.actions.first(MineOreAction::class.java)?.rockObj === target ||
                bot.mining.experience > experience)
        if (started) {
            cluster = target.position
            behavior.settle()
        } else if (isActionSafe() && !bot.inventory.isFull && target.state == EntityState.ACTIVE) {
            retryAfter[target.position] = System.nanoTime() + 20.seconds.inWholeNanoseconds
        }
        return started
    }

    /** Remember inspected map locations, including rocks that are currently depleted. */
    private fun observeSites() {
        sites.filter { it.position.isViewable(bot.position) }.forEach { surveyed += it.position }
    }

    /** Inspect the next unvisited rock cluster, using map geography without reading distant live ore states. */
    private suspend fun scout(zone: SubZone) {
        if (!isActionSafe() || forceBanking || bot.inventory.isFull) return
        val next = sites.filter { it.position !in surveyed && it.ore.level <= bot.mining.level }
            .minWithOrNull(compareBy<MiningSites.Rock> { if (it.ore in preferredOres) 0 else 1 }
                .thenBy { bot.position.computeLongestDistance(it.position) }) ?: return
        visitSite(zone, next)
    }

    /** Native interaction geometry supplies legal stands for both 1x1 rocks and the 5x5 essence rocks. */
    private suspend fun visitSite(zone: SubZone, site: MiningSites.Rock) {
        if (!isActionSafe() || forceBanking || bot.inventory.isFull) return
        val rock = site.placement.toGameObject(ctx)
        val stands = handler.interactions.interactionStands(rock, InteractionPolicy.STANDARD_SIZE).filter {
            zone.area.contains(it) &&
                    (zone == SubZone.MINING_GUILD || !SubZone.MINING_GUILD.area.contains(it)) &&
                    !world.collisionManager.isBlocked(it, false)
        }
        // An unreachable site is tried once per trip; the normal unsuccessful-search deadline still applies.
        surveyed += site.position
        val reached = stands.isNotEmpty() &&
                withTimeoutOrNull(45.seconds) { handler.webWalkToAny(stands) } == true
        if (!isActionSafe()) {
            surveyed -= site.position
            return
        }
        if (reached) {
            cluster = bot.position
            behavior.settle()
            observeSites()
            bot.log("Inspecting mining rocks near ${site.position} in $zone; ${surveyed.size}/${sites.size} sites checked.")
        }
    }

    override fun tools(): TreeSet<SkillingTool> =
        Pickaxe.ID_TO_PICKAXE.values.filter { skill.staticLevel >= it.level }
            .mapTo(TreeSet<SkillingTool>()) { SkillingTool(it.id, it.level) }

    override fun emergencyTool(): SkillingTool = SkillingTool(Pickaxe.BRONZE.id, Pickaxe.BRONZE.level)

    override fun levelRequired(): Int = ores.maxOfOrNull { it.level } ?: 0

    override fun snapshot(): BotScriptData {
        val data = MineData()
        data.ores = ores
        data.preferredOres = preferredOres
        data.duration = duration
        data.zones = originalZones.toMutableList()
        return data
    }

    override suspend fun completed() {
        if (ores == setOf(Ore.GEM_ROCK)) return
        // Chance to queue a smelting, smithing, or runecrafting script.
        if (rand(bot.personality.intelligence) || bot.personality.isDextrous) {
            val script =
                if (ores.contains(Ore.PURE_ESSENCE) || ores.contains(Ore.RUNE_ESSENCE))
                    RunecraftingScriptFactory.getScript(bot,
                                                        bot.runecrafting.staticLevel,
                                                        mutableListOf(),
                                                        randBoolean())
                else
                    SmithingScriptFactory.getScript(bot, bot.smithing.staticLevel, mutableListOf(), randBoolean())
            bot.scriptStack.pushTail(script, 2)
        }
    }

}
