package game.bot.scripts

import api.attr.Attr
import api.attr.getValue
import api.attr.setValue
import api.bot.action.BotCombatActionHandler
import api.bot.action.BotNpcDialogues
import api.bot.script.BotScript
import api.bot.script.BotScriptData
import com.google.gson.JsonObject
import game.content.goblinDiplomacy.GoblinDiplomacy as Quest
import game.content.goblinDiplomacy.GoblinGenerals as Generals
import game.skill.crafting.dyeing.AggieDye
import game.skill.crafting.dyeing.Dye
import game.skill.crafting.dyeing.Dye.Companion.BLUE_GOBLIN_MAIL
import game.skill.crafting.dyeing.Dye.Companion.GOBLIN_MAIL
import game.skill.crafting.dyeing.Dye.Companion.ORANGE_GOBLIN_MAIL
import io.luna.game.model.EntityState
import io.luna.game.model.Position
import io.luna.game.model.item.Equipment
import io.luna.game.model.item.GroundItem
import io.luna.game.model.item.Item
import io.luna.game.model.mob.Npc
import io.luna.game.model.mob.Skill
import io.luna.game.model.mob.bot.Bot
import io.luna.game.model.mob.controller.PlayerController
import io.luna.game.model.mob.dialogue.DialogueInterface
import io.luna.game.model.mob.dialogue.OptionDialogue
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Follows the Rusty Anchor rumour through the three trials, acquiring ordinary dyes and goblin mail.
 *
 * @author lare96
 */
class GoblinDiplomacyBotScript(bot: Bot, private val data: Data = Data()) : BotScript(bot) {
    companion object {
        private const val ATTEMPT_MS = 25 * 60_000L
        private const val RETRY_MS = 30 * 60_000L
        private const val AGGIE = 922
        private const val WYSON = 36
        private const val ONION_PLANT = 3366
        private const val COINS = 995
        private const val WOAD_PRICE = 20
        private const val BUY_WOAD = "Yes please, I need woad leaves."
        private const val WOAD_OFFER = "How about 20 coins?"

        private val tavern = Position(3045, 3257)
        private val village = Position(2957, 3511)
        private val goblins = setOf(298, 299)
        private val camp = Position(2955, 3499)
        private val aggieHome = Position(3086, 3259)
        private val park = Position(3027, 3379)
        private val redberries = Position(3271, 3370)
        private val onionField = Position(2950, 3251)
        private val trialMail = listOf(ORANGE_GOBLIN_MAIL, BLUE_GOBLIN_MAIL, GOBLIN_MAIL)
        private var Bot.goblinDiplomacyRetryAt by Attr.long().persist("goblin_diplomacy_bot_retry_at")

        private fun owned(bot: Bot, id: Int): Long =
            bot.inventory.computeAmountForId(id).toLong() + bot.bank.computeAmountForId(id)

        private fun trials(stage: Int) = trialMail.drop((stage - Quest.NEED_ORANGE).coerceIn(0, trialMail.size))

        private fun missingMail(bot: Bot, stage: Int) =
            trials(stage).count { it == GOBLIN_MAIL || owned(bot, it) == 0L }

        private fun recipes(orange: Boolean, blue: Boolean, has: (Int) -> Boolean): List<AggieDye> =
            AggieDye.values().filter { recipe ->
                !has(recipe.dye) && if (recipe == AggieDye.BLUE) blue else orange && !has(Dye.ORANGE.id)
            }

        private fun canFight(bot: Bot) =
            bot.equipment[Equipment.WEAPON] != null && !bot.combat.weapon.isRanged && !bot.combat.magic.isCasting &&
                bot.combat.styleDef.exp.all { BotCombatActionHandler.mayGainExperience(bot, it) }

        fun eligible(bot: Bot): Boolean {
            val stage = Quest.stage(bot)
            if (stage == Quest.COMPLETE || bot.state != EntityState.ACTIVE || !ActionSafety.NON_COMBAT.allows(bot) ||
                bot.overlays.hasWindow() || bot.controllers.primary.javaClass != PlayerController::class.java ||
                System.currentTimeMillis() < bot.goblinDiplomacyRetryAt ||
                BotCombatActionHandler.limitExperience(bot, bot.skill(Skill.CRAFTING), Quest.CRAFTING_XP) < Quest.CRAFTING_XP)
                return false
            if (stage == 0 && !bot.position.isWithinDistance(tavern, 25)) return false
            if (owned(bot, GOBLIN_MAIL) < missingMail(bot, stage) && !canFight(bot)) return false
            val remaining = trials(stage)
            val recipes = recipes(ORANGE_GOBLIN_MAIL in remaining && owned(bot, ORANGE_GOBLIN_MAIL) == 0L,
                BLUE_GOBLIN_MAIL in remaining && owned(bot, BLUE_GOBLIN_MAIL) == 0L) { owned(bot, it) > 0L }
            val price = recipes.size * AggieDye.DYE_PRICE +
                if (AggieDye.BLUE in recipes && owned(bot, AggieDye.BLUE.ingredient) < 2L) WOAD_PRICE else 0
            return owned(bot, COINS) >= price
        }
    }

    /**
     * Preserves a bounded attempt across bot sessions. The quest varp and carried items describe actual progress.
     *
     * @author lare96
     */
    class Data : BotScriptData() {
        var deadline = System.currentTimeMillis() + ATTEMPT_MS

        override fun load(data: JsonObject) {
            deadline = data.get("deadline").asLong
        }

        override fun save(data: JsonObject) {
            data.addProperty("deadline", deadline)
        }
    }

    override val actionSafety = ActionSafety.COMBAT
    override val allowsCombatResponse = false
    var failure: String? = null
        private set

    override fun snapshot() = data

    override fun allowsLoot(item: GroundItem) =
        item.id == GOBLIN_MAIL && count(GOBLIN_MAIL) < missingMail(bot, Quest.stage(bot)) ||
            item.id == AggieDye.RED.ingredient && count(item.id) < AggieDye.RED.amount

    override suspend fun run(): Boolean {
        var objective = "hear the bartender's rumour"
        val finished = withTimeoutOrNull(data.deadline - System.currentTimeMillis()) {
            while (live() && Quest.stage(bot) != Quest.COMPLETE) {
                val stage = Quest.stage(bot)
                objective = when (stage) {
                    0 -> "hear the bartender's rumour"
                    Quest.STARTED -> "offer to help the generals"
                    Quest.NEED_ORANGE -> "prepare the armour and present the orange mail"
                    Quest.NEED_BLUE -> "present the blue mail"
                    Quest.NEED_BROWN -> "present the brown mail"
                    else -> "hear the generals' decision"
                }
                val advanced = when (stage) {
                    0 -> startQuest()
                    Quest.STARTED -> talkToGenerals(stage)
                    Quest.GAVE_BROWN -> ensureInventory(emptyList(), 1) && talkToGenerals(stage)
                    else -> prepareTrials(stage) && talkToGenerals(stage)
                }
                if (!advanced) return@withTimeoutOrNull false
            }
            Quest.stage(bot) == Quest.COMPLETE
        } == true
        if (!finished && Quest.stage(bot) != Quest.COMPLETE) {
            failure = "Could not $objective at ${bot.position}, quest stage ${Quest.stage(bot)}."
            bot.log("Goblin Diplomacy: $failure")
            bot.goblinDiplomacyRetryAt = System.currentTimeMillis() + RETRY_MS
        }
        return true
    }

    private suspend fun startQuest(): Boolean {
        repeat(3) {
            val bartender = approach(Quest.BARTENDER, tavern) ?: return@repeat
            val previousPage = bot.overlays.getOverlay(DialogueInterface::class.java)
            if (!handler.interactions.interact(1, bartender, ::peaceful) || !await {
                    val page = bot.overlays.getOverlay(DialogueInterface::class.java) as? OptionDialogue
                    page != null && page !== previousPage && Quest.ASK_RUMOUR in page.options
                }) return@repeat
            if (BotNpcDialogues.respond(bot, bartender, emptyList(), allowed = ::peaceful,
                    completed = { Quest.stage(bot) >= Quest.STARTED }, advanceSpeech = true,
                    playerSpeech = setOf(Quest.ASK_RUMOUR, Quest.offerToInvestigate.joinToString(" "))) {
                    handler.widgets.clickDialogueOption(Quest.ASK_RUMOUR)
                }) return true
        }
        return false
    }

    private suspend fun talkToGenerals(stage: Int): Boolean {
        val target = if (stage == Quest.NEED_BROWN) Quest.COMPLETE else stage + 1
        repeat(3) {
            if (Quest.stage(bot) >= target) return true
            val wartface = approach(Quest.WARTFACE, village) ?: return@repeat
            val responses = if (stage == Quest.STARTED) listOf(Generals.OFFER) else emptyList()
            if (BotNpcDialogues.respond(bot, wartface, responses, timeout = 50.seconds,
                    allowed = ::peaceful, advanceSpeech = true,
                    playerSpeech = setOf(Generals.DIFFERENT_COLOUR, Generals.SHOW_ORANGE, Generals.SHOW_BLUE,
                        Generals.SHOW_BROWN, Generals.blueReply.joinToString(" ")),
                    textPages = setOf(Generals.HAND_IN), additionalSpeakers = setOf(Quest.BENTNOZE),
                    completed = {
                        Quest.stage(bot) >= target &&
                            (target == Quest.COMPLETE || bot.overlays.getOverlay(DialogueInterface::class.java) == null)
                    }) { handler.interactions.interact(1, wartface, ::peaceful) }) return true
        }
        return Quest.stage(bot) >= target
    }

    private suspend fun prepareTrials(stage: Int): Boolean {
        val remaining = trials(stage)
        if (remaining.all { count(it) > 0 }) return true
        val orange = ORANGE_GOBLIN_MAIL in remaining && owned(bot, ORANGE_GOBLIN_MAIL) == 0L
        val blue = BLUE_GOBLIN_MAIL in remaining && owned(bot, BLUE_GOBLIN_MAIL) == 0L
        val recipes = recipes(orange, blue) { owned(bot, it) > 0L }
        val supplies = mutableListOf<Item>()
        fun bring(id: Int, limit: Int = 1) {
            val amount = minOf(owned(bot, id), limit.toLong()).toInt()
            if (amount > 0) supplies += Item(id, amount)
        }
        for (mail in remaining) if (mail != GOBLIN_MAIL) bring(mail)
        bring(GOBLIN_MAIL, missingMail(bot, stage))
        if (orange) {
            if (owned(bot, Dye.ORANGE.id) > 0L) bring(Dye.ORANGE.id)
            else {
                bring(Dye.RED.id)
                bring(Dye.YELLOW.id)
            }
        }
        if (blue) bring(Dye.BLUE.id)
        for (recipe in recipes) bring(recipe.ingredient, recipe.amount)
        val buyLeaves = AggieDye.BLUE in recipes && owned(bot, AggieDye.BLUE.ingredient) < AggieDye.BLUE.amount
        val expense = recipes.size * AggieDye.DYE_PRICE + if (buyLeaves) WOAD_PRICE else 0
        if (owned(bot, COINS) < expense) return false
        bring(COINS, expense)
        // Allow room for the missing raw materials and the remaining unstackable trial pieces.
        if (!ensureInventory(supplies, 8)) return false
        for (recipe in recipes) {
            if (count(recipe.ingredient) < recipe.amount && !collectIngredient(recipe)) return false
        }
        if (recipes.isNotEmpty() && !buyDyes(recipes)) return false
        if (orange && count(Dye.ORANGE.id) == 0 && !use(Dye.RED.id, Dye.YELLOW.id, Dye.ORANGE.id)) return false
        if (count(GOBLIN_MAIL) < missingMail(bot, stage) && !collectMail(missingMail(bot, stage))) return false
        if (orange && !use(Dye.ORANGE.id, GOBLIN_MAIL, ORANGE_GOBLIN_MAIL)) return false
        if (blue && !use(Dye.BLUE.id, GOBLIN_MAIL, BLUE_GOBLIN_MAIL)) return false
        return remaining.all { count(it) > 0 }
    }

    private suspend fun ensureInventory(supplies: List<Item>, freeSlots: Int): Boolean {
        val missing = supplies.mapNotNull { item ->
            val amount = item.amount - count(item.id)
            if (amount > 0) Item(item.id, amount) else null
        }
        if (missing.isEmpty() && bot.inventory.computeRemainingSize() >= freeSlots) return true
        val bank = handler.webWalkToNearestBank(attempts = 2, fallback = false) ?: return false
        if (!handler.banking.open(bank)) return false
        try {
            val keep = supplies.map { it.id }.toSet()
            for (item in bot.inventory.filterNotNull()) {
                if (bot.inventory.hasSpaceForAll(missing) && bot.inventory.computeRemainingSize() >= freeSlots) break
                if (item.id !in keep && !handler.banking.deposit(item)) return false
            }
            return bot.inventory.computeRemainingSize() >= freeSlots &&
                missing.all { handler.banking.withdrawUnnoted(it, ::peaceful) }
        } finally {
            if (bot.bank.isOpen) bot.overlays.closeWindows()
        }
    }

    private suspend fun collectIngredient(recipe: AggieDye): Boolean = when (recipe) {
        AggieDye.RED -> handler.webWalkTo(redberries, radius = 5, attempts = 2, fallback = false) &&
            await(45_000, { count(recipe.ingredient) >= recipe.amount }) {
            val item = bot.world.locator.findItems(bot, 16, true) {
                it.id == recipe.ingredient && handler.loot.canPickup(it)
            }.firstOrNull()
            if (item != null) handler.loot.pickup(item, ::peaceful)
        }
        AggieDye.YELLOW -> handler.webWalkTo(onionField, radius = 4, attempts = 2, fallback = false) &&
            await(45_000, { count(recipe.ingredient) >= recipe.amount }) {
            val onion = bot.world.locator.findNearestObject(bot.position, 12) { it.id == ONION_PLANT }
            if (onion != null && handler.interactions.interact(2, onion, ::peaceful)) delay(1_800)
        }
        AggieDye.BLUE -> buyWoadLeaves(recipe)
    }

    private suspend fun buyWoadLeaves(recipe: AggieDye): Boolean {
        repeat(3) {
            val wyson = approach(WYSON, park) ?: return@repeat
            if (BotNpcDialogues.respond(bot, wyson, listOf(BUY_WOAD, WOAD_OFFER), timeout = 20.seconds,
                    allowed = ::peaceful, advanceSpeech = true,
                    completed = { count(recipe.ingredient) >= recipe.amount }) {
                    handler.interactions.interact(1, wyson, ::peaceful)
                }) return true
        }
        return false
    }

    private suspend fun buyDyes(recipes: List<AggieDye>): Boolean {
        val aggie = approach(AGGIE, aggieHome) ?: return false
        for (recipe in recipes) {
            repeat(3) {
                if (count(recipe.dye) > 0) return@repeat
                BotNpcDialogues.respond(bot, aggie, emptyList(), timeout = 15.seconds, allowed = ::peaceful,
                    completed = { count(recipe.dye) > 0 }, playerSpeech = setOf(recipe.request), advanceSpeech = true) {
                    handler.inventory.useItem(recipe.ingredient).onNpc(aggie, ::peaceful)
                }
            }
            if (count(recipe.dye) == 0) return false
            if (bot.overlays.hasWindow() && !handler.widgets.clickCloseInterface()) return false
        }
        return true
    }

    private suspend fun collectMail(target: Int): Boolean {
        if (!canFight(bot) || !handler.webWalkTo(camp, radius = 5, attempts = 2, fallback = false)) return false
        while (live() && count(GOBLIN_MAIL) < target) {
            if (bot.healthPercent <= 45 && !handler.inventory.eatAnyFood()) return false
            val drop = bot.world.locator.findItems(bot, 25, true) {
                it.id == GOBLIN_MAIL && handler.loot.canPickup(it)
            }.firstOrNull()
            if (drop != null && handler.loot.pickup(drop, ::live)) continue

            val enemies = bot.world.locator.findNpcs(camp, 18) {
                it.id in goblins && it.isAlive && (!it.combat.inCombat() || it.combat.target === bot)
            }.sortedBy { it.position.computeLongestDistance(bot.position) }
            if (enemies.none { fight(it) }) delay(600)
        }
        return count(GOBLIN_MAIL) >= target && ActionSafety.NON_COMBAT.await(bot)
    }

    private suspend fun fight(goblin: Npc): Boolean = withTimeoutOrNull(30.seconds) {
        val interactions = handler.interactions
        val allowed = { live() && goblin.isAlive && goblin.state == EntityState.ACTIVE }
        if (!interactions.approach(goblin, interactions.interactionPolicy(3, goblin),
                timeout = 10.seconds, allowOutOfView = true, canContinue = allowed) ||
            !interactions.interactLocally(3, goblin, timeout = 8.seconds, allowed = allowed)) return@withTimeoutOrNull false
        while (allowed()) {
            if (bot.healthPercent <= 45 && !handler.inventory.eatAnyFood()) return@withTimeoutOrNull false
            delay(300)
        }
        // The death action places loot after its animation.
        delay(1_800)
        live()
    } == true

    private suspend fun approach(id: Int, position: Position): Npc? {
        val npc = bot.world.locator.findNpcs(position, 12) { it.id == id && it.isAlive }.firstOrNull() ?: return null
        return npc.takeIf {
            handler.interactions.approach(it, timeout = 230.seconds, allowOutOfView = true, canContinue = ::peaceful)
        }
    }

    private suspend fun use(first: Int, second: Int, product: Int): Boolean =
        handler.inventory.useItem(first).onItem(second) && await { count(product) > 0 }

    private suspend fun await(timeoutMs: Long = 12_000, condition: () -> Boolean): Boolean =
        await(timeoutMs, condition) {}

    private suspend fun await(timeoutMs: Long, condition: () -> Boolean, poll: suspend () -> Unit): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (live() && !condition()) {
                poll()
                delay(200)
            }
            condition()
        } == true

    private fun count(id: Int) = bot.inventory.computeAmountForId(id)

    private fun live() = bot.state == EntityState.ACTIVE && bot.isAlive

    private fun peaceful() = live() && !bot.combat.inCombat()
}
