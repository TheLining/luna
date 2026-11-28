package game.bot.scripts

import api.attr.Attr
import api.attr.getValue
import api.attr.setValue
import api.bot.action.BotNpcDialogues
import api.bot.script.BotScript
import api.bot.script.BotScriptData
import com.google.gson.JsonObject
import game.content.dwarfCannon.DwarfCannon as Quest
import game.content.dwarfMulticannon.DwarfMulticannon.AMMO_MOULD
import game.content.dwarfMulticannon.DwarfMulticannon.NULODION
import game.obj.entrances.Entrance
import io.luna.game.model.EntityState
import io.luna.game.model.Position
import io.luna.game.model.item.Item
import io.luna.game.model.mob.bot.Bot
import io.luna.game.model.mob.controller.PlayerController
import io.luna.game.model.mob.dialogue.OptionDialogue
import io.luna.game.model.mob.interact.InteractionPolicy
import io.luna.game.model.`object`.GameObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Follows the Black Guard's assignments through normal world, dialogue and inventory interactions.
 *
 * @author lare96
 */
class DwarfCannonBotScript(bot: Bot, private val data: DwarfCannonData = DwarfCannonData()) : BotScript(bot) {
    companion object {
        private const val ATTEMPT_MS = 25 * 60_000L
        private const val RETRY_MS = 60 * 60_000L
        private const val REPAIR_ATTEMPTS = 24
        private const val UP_LADDER = 1747
        private const val DOWN_LADDER = 1746
        private val lowerLadder = Position(2570, 3441)
        private val middleLadder = Position(2570, 3443, 1)
        private val storyItems = setOf(Quest.RAILING, Quest.TOOLKIT, Quest.REMAINS, Quest.NOTES, AMMO_MOULD)
        private var Bot.dwarfCannonRetryAt by Attr.long().persist("dwarf_cannon_bot_retry_at")

        fun eligible(bot: Bot): Boolean {
            val stage = Quest.stage(bot)
            return stage < Quest.COMPLETE && (stage > 0 || Quest.commander.isWithinDistance(bot.position, 48)) &&
                bot.state == EntityState.ACTIVE && ActionSafety.NON_COMBAT.allows(bot) &&
                !bot.overlays.hasWindow() && bot.controllers.primary.javaClass == PlayerController::class.java &&
                System.currentTimeMillis() >= bot.dwarfCannonRetryAt
        }
    }

    /**
     * Keeps the overall deadline across a script save or pause.
     *
     * @author lare96
     */
    class DwarfCannonData : BotScriptData() {
        var deadline = System.currentTimeMillis() + ATTEMPT_MS

        override fun load(data: JsonObject) {
            deadline = data.get("deadline").asLong
        }

        override fun save(data: JsonObject) {
            data.addProperty("deadline", deadline)
        }
    }

    var failure: String? = null
        private set

    override fun snapshot() = data

    override suspend fun run(): Boolean {
        if (stage() == Quest.COMPLETE) return true
        val finished = withTimeoutOrNull(data.deadline - System.currentTimeMillis()) {
            while (live() && stage() < Quest.COMPLETE) {
                val before = stage()
                bot.log("Dwarf Cannon: stage $before.")
                if (!step(before) && stage() == before) {
                    delay(1_200)
                    if (!live() || !step(before) && stage() == before) return@withTimeoutOrNull false
                }
            }
            stage() == Quest.COMPLETE
        } == true
        if (!finished) {
            failure = "Could not continue Dwarf Cannon at stage ${stage()} from ${bot.position}."
            bot.dwarfCannonRetryAt = System.currentTimeMillis() + RETRY_MS
            bot.log(failure!!)
        }
        return true
    }

    private suspend fun step(before: Int): Boolean {
        if (inCave() && before != Quest.RESCUE_CHILD) {
            return click(Entrance.DWARF_CANNON_CAVE_OUT.id, Quest.caveExit) { !inCave() }
        }
        if (inShed() && before !in Quest.INSPECT_CANNON..Quest.REPAIR_CANNON) {
            return crossShed(inside = false)
        }
        return when (before) {
            0 -> makeSpace(6) && commander(before, Quest.ACCEPT_ASSIGNMENT)
            Quest.REPAIR_RAILINGS -> repairRailings() && commander(before)
            Quest.CHECK_TOWER -> findRemains() && leaveTower() && commander(before)
            Quest.FIND_CAVE -> click(Entrance.DWARF_CANNON_CAVE_IN.id, Quest.caveEntrance) {
                stage() == Quest.RESCUE_CHILD && inCave()
            }
            Quest.RESCUE_CHILD -> rescueChild()
            Quest.REPORT_RESCUE -> makeSpace(1) && commander(before, Quest.ACCEPT_REPAIR)
            Quest.INSPECT_CANNON -> crossShed(inside = true) &&
                click(Quest.BROKEN_CANNON, Quest.brokenCannon) { stage() > before }
            Quest.REPAIR_CANNON -> repairCannon()
            Quest.REPORT_REPAIR -> commander(before, Quest.ACCEPT_ENGINEER)
            Quest.VISIT_ENGINEER -> engineer { stage() == Quest.RETURN_SUPPLIES }
            Quest.RETURN_SUPPLIES -> if (Quest.hasSupplies(bot)) commander(before) else engineer { Quest.hasSupplies(bot) }
            else -> false
        }
    }

    private suspend fun repairRailings(): Boolean {
        for ((id, position) in Quest.railings) {
            if (Quest.repairedRailing(bot, id)) continue
            if (!has(Quest.RAILING) && (!makeSpace(1) || !talk(Quest.COMMANDER, Quest.commander) { has(Quest.RAILING) })) {
                return false
            }
            val railing = objectAt(id, position) ?: return false
            for (attempt in 1..REPAIR_ATTEMPTS) {
                if (!live()) return false
                if (bot.health <= 3 && (!handler.inventory.eatAnyFood() || bot.health <= 3)) return false
                if (choose(railing, Quest.REPLACE_RAILING) { Quest.repairedRailing(bot, id) }) break
            }
            if (!Quest.repairedRailing(bot, id)) return false
            bot.log("Dwarf Cannon: repaired railing $id.")
        }
        return true
    }

    private suspend fun findRemains(): Boolean {
        if (has(Quest.REMAINS)) return true
        if (!makeSpace(1)) return false
        if (bot.z == 0 && !click(UP_LADDER, lowerLadder) { bot.z == 1 }) return false
        if (bot.z == 1 && !click(UP_LADDER, middleLadder) { bot.z == 2 }) return false
        return withTimeoutOrNull(70.seconds) {
            while (live()) {
                val item = bot.world.locator.findItems(Quest.remains, 0) {
                    it.id == Quest.REMAINS && it.state == EntityState.ACTIVE && it.view.isViewableFor(bot)
                }.firstOrNull()
                if (item != null && handler.interactions.interactLocally(1, item, allowed = ::live) &&
                    await(3_600) { has(Quest.REMAINS) }) return@withTimeoutOrNull true
                delay(600)
            }
            false
        } == true
    }

    private suspend fun leaveTower(): Boolean {
        if (!Quest.remains.isWithinDistance(Position(bot.x, bot.y, 2), 15)) return true
        if (bot.z == 2 && !click(DOWN_LADDER, middleLadder.translate(0, 0, 1)) { bot.z == 1 }) return false
        return bot.z != 1 || click(DOWN_LADDER, lowerLadder.translate(0, 0, 1)) { bot.z == 0 }
    }

    private suspend fun rescueChild(): Boolean {
        if (!inCave() && !click(Entrance.DWARF_CANNON_CAVE_IN.id, Quest.caveEntrance, ::inCave)) return false
        val crate = objectAt(Quest.CHILD_CRATE, Quest.childCrate) ?: return false
        if (!approach(crate)) return false
        return BotNpcDialogues.respondVirtual(bot, Quest.CHILD, emptyList(), timeout = 25.seconds,
            allowed = ::live, completed = { stage() > Quest.RESCUE_CHILD }) {
            bot.output.sendObjectInteraction(1, crate)
        }
    }

    private suspend fun repairCannon(): Boolean {
        if (!has(Quest.TOOLKIT)) {
            if (!crossShed(inside = false) || !makeSpace(1) ||
                !talk(Quest.COMMANDER, Quest.commander) { has(Quest.TOOLKIT) }) return false
        }
        if (!crossShed(inside = true)) return false
        val cannon = objectAt(Quest.BROKEN_CANNON, Quest.brokenCannon) ?: return false
        for ((part, name) in Quest.parts.withIndex()) {
            if (Quest.repairedPart(bot, part)) continue
            for (attempt in 1..REPAIR_ATTEMPTS) {
                if (!live()) return false
                if (choose(cannon, name) { Quest.repairedPart(bot, part) }) break
            }
            if (!Quest.repairedPart(bot, part)) return false
            bot.log("Dwarf Cannon: repaired the ${name.lowercase()}.")
        }
        return click(Quest.BROKEN_CANNON, Quest.brokenCannon) { stage() > Quest.REPAIR_CANNON }
    }

    private suspend fun engineer(done: () -> Boolean): Boolean =
        makeSpace(listOf(Quest.NOTES, AMMO_MOULD).count { !has(it) }) &&
            talk(NULODION, Quest.engineer, Quest.ASK_ENGINEER, done = done)

    private suspend fun commander(before: Int, vararg options: String): Boolean =
        talk(Quest.COMMANDER, Quest.commander, *options) { stage() > before }

    private suspend fun talk(id: Int, spawn: Position, vararg options: String, done: () -> Boolean): Boolean {
        val npc = bot.world.locator.findNpcs(spawn, 12) {
            it.id == id && it.state == EntityState.ACTIVE && it.isAlive
        }.minByOrNull { it.position.computeLongestDistance(spawn) } ?: return false
        if (!handler.interactions.approach(npc, timeout = 6.minutes, allowOutOfView = true, canContinue = ::live)) {
            bot.log("Dwarf Cannon: could not reach NPC $id.")
            return false
        }
        repeat(3) {
            if (!await(6_000) { ActionSafety.NON_COMBAT.allows(bot) }) return false
            if (BotNpcDialogues.respond(bot, npc, options.toList(), timeout = 35.seconds, allowed = ::live,
                    completed = done, advanceSpeech = true) {
                    val previous = bot.dialogues
                    handler.interactions.interactLocally(1, npc, timeout = 12.seconds, allowed = ::live) &&
                        await(5_000) { done() || bot.dialogues?.let { it !== previous } == true }
                }) {
                return true
            }
            delay(1_200)
        }
        bot.log("Dwarf Cannon: dialogue with NPC $id did not resolve at stage ${stage()}.")
        return false
    }

    private suspend fun click(id: Int, at: Position, done: () -> Boolean): Boolean {
        val obj = objectAt(id, at) ?: return false
        return approach(obj) && bot.output.sendObjectInteraction(1, obj) && await { done() && !bot.isLocked }
    }

    private suspend fun crossShed(inside: Boolean): Boolean =
        inShed() == inside || click(Quest.SHED_DOOR, Quest.shedDoor) { inShed() == inside }

    private suspend fun choose(obj: GameObject, response: String, done: () -> Boolean): Boolean {
        if (!approach(obj)) return false
        val previous = bot.overlays.getOverlay(OptionDialogue::class.java)
        if (!bot.output.sendObjectInteraction(1, obj)) return false
        if (!await(2_400) {
                val page = bot.overlays.getOverlay(OptionDialogue::class.java)
                page != null && page !== previous && response in page.options
            }) return false
        return handler.widgets.clickDialogueOption(response) && await(2_400, done)
    }

    private suspend fun approach(obj: GameObject): Boolean =
        handler.interactions.approach(obj, InteractionPolicy.STANDARD_SIZE, timeout = 6.minutes, canContinue = ::live)

    private fun objectAt(id: Int, at: Position): GameObject? =
        bot.world.locator.findObjectsOnTile(at) { it.id == id && it.state == EntityState.ACTIVE }.firstOrNull()

    private suspend fun makeSpace(slots: Int): Boolean {
        if (bot.inventory.computeRemainingSize() >= slots) return true
        val bank = handler.webWalkToNearestBank(attempts = 2, fallback = false) ?: return false
        if (!handler.banking.open(bank)) return false
        try {
            while (bot.inventory.computeRemainingSize() < slots) {
                val item = bot.inventory.filterNotNull().firstOrNull { it.id !in storyItems } ?: return false
                if (!handler.banking.deposit(Item(item.id, item.amount))) return false
            }
            return true
        } finally {
            if (bot.bank.isOpen) bot.overlays.closeWindows()
        }
    }

    private suspend fun await(timeout: Long = 12_000, done: () -> Boolean): Boolean = withTimeoutOrNull(timeout) {
        while (live() && !done()) delay(120)
        live() && done()
    } == true

    private fun stage() = Quest.stage(bot)
    private fun has(id: Int) = bot.inventory.contains(id)
    private fun live() = bot.state == EntityState.ACTIVE && bot.isAlive
    private fun inCave() = bot.z == 0 && bot.x in 2554..2623 && bot.y in 9780..9860
    private fun inShed() = bot.z == 0 && bot.x in 2576..2579 && bot.y in 3460..3463
}
