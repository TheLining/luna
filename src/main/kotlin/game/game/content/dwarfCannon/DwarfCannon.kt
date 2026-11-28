/* Adapted from LostCityRS/Content quest_mcannon at 44eab09081ef0f6c5f3eaabd1897a588ad7d9e50. */
package game.content.dwarfCannon

import api.predef.ext.playJingle
import api.quest.QuestProgress
import game.content.dwarfMulticannon.DwarfMulticannon.AMMO_MOULD
import game.player.Jingles
import game.player.QuestJournalInterface
import io.luna.game.model.Position
import io.luna.game.model.item.Item
import io.luna.game.model.mob.Player
import io.luna.game.model.mob.Skill
import io.luna.game.model.mob.overlay.StandardInterface
import io.luna.game.model.mob.varp.PersistentVarp
import io.luna.net.msg.out.WidgetColourMessageWriter
import io.luna.net.msg.out.WidgetItemModelMessageWriter

/**
 * Saves the Black Guard assignments and the individual railing and cannon repairs.
 *
 * @author lare96
 */
object DwarfCannon {
    const val COMMANDER = 208
    const val CHILD = 207
    const val REMAINS = 0
    const val TOOLKIT = 1
    const val NOTES = 3
    const val RAILING = 14
    const val JOURNAL_BUTTON = 7356

    const val REPAIR_RAILINGS = 1
    const val CHECK_TOWER = 2
    const val FIND_CAVE = 3
    const val RESCUE_CHILD = 4
    const val REPORT_RESCUE = 5
    const val INSPECT_CANNON = 6
    const val REPAIR_CANNON = 7
    const val REPORT_REPAIR = 8
    const val VISIT_ENGINEER = 9
    const val RETURN_SUPPLIES = 10
    const val COMPLETE = 11

    const val PLAIN_RAILING = 14
    const val FIRST_BROKEN_RAILING = 15
    const val LAST_BROKEN_RAILING = 20
    const val SHED_DOOR = 4
    const val BROKEN_CANNON = 5
    const val CHILD_CRATE = 1
    const val CANNONBALL = 2

    val commander = Position(2571, 3463)
    val engineer = Position(3011, 3453)
    val remains = Position(2567, 3444, 2)
    val shedDoor = Position(2576, 3461)
    val brokenCannon = Position(2577, 3461)
    val caveEntrance = Position(2622, 3392)
    val caveExit = Position(2621, 9796)
    val childCrate = Position(2571, 9850)
    val childSpawn = Position(2571, 9851)
    val railings = mapOf(
        15 to Position(2556, 3475), 16 to Position(2558, 3472), 17 to Position(2557, 3464),
        18 to Position(2559, 3462), 19 to Position(2564, 3460), 20 to Position(2572, 3460)
    )

    const val ACCEPT_ASSIGNMENT = "Yeah, I'd love to help."
    const val ACCEPT_REPAIR = "Ok, I'll see what I can do."
    const val ACCEPT_ENGINEER = "Ok then, just for you!"
    const val ASK_ENGINEER = "The Dwarf Commander sent me."
    const val REPLACE_RAILING = "Try to replace the railing."

    val parts = listOf("Pipe", "Barrel", "Axle", "Shaft")

    private val progress = QuestProgress(PersistentVarp.DWARF_CANNON, COMPLETE, 1)
    private val supplies = listOf(NOTES, AMMO_MOULD)

    fun stage(player: Player) = progress.stage(player)

    fun repairedRailing(player: Player, objectId: Int): Boolean =
        hasRepair(player, objectId - FIRST_BROKEN_RAILING + 5)

    fun repairedPart(player: Player, part: Int): Boolean = hasRepair(player, part)

    fun hasSupplies(player: Player) = player.inventory.containsAll(NOTES, AMMO_MOULD)

    internal fun advance(player: Player, expected: Int): Boolean {
        if (!player.isAlive || stage(player) != expected || !progress.advance(player, expected + 1)) return false
        refresh(player)
        return true
    }

    internal fun start(player: Player) {
        if (stage(player) != 0) return
        val railings = List(6) { Item(RAILING) }
        if (give(player, railings) && advance(player, 0)) {
            player.sendMessage("The Dwarf Commander gives you six railings.")
        }
    }

    internal fun giveReplacementRailing(player: Player) {
        if (stage(player) == REPAIR_RAILINGS && !player.inventory.contains(RAILING) && give(player, listOf(Item(RAILING)))) {
            player.sendMessage("The Dwarf Commander gives you another railing.")
        }
    }

    internal fun giveToolkit(player: Player, expected: Int) {
        if (stage(player) != expected) return
        if (!player.inventory.contains(TOOLKIT)) {
            if (!give(player, listOf(Item(TOOLKIT)))) return
            player.sendMessage("The Dwarf Commander gives you a tool kit.")
        }
        if (expected == REPORT_RESCUE) advance(player, REPORT_RESCUE)
    }

    internal fun giveSupplies(player: Player) {
        val stage = stage(player)
        if (stage !in VISIT_ENGINEER..RETURN_SUPPLIES) return
        val missing = supplies.filterNot { player.inventory.contains(it) }.map(::Item)
        if (missing.isNotEmpty()) {
            if (!give(player, missing)) return
            player.sendMessage("Nulodion gives you the supplies for the Commander.")
        }
        if (stage == VISIT_ENGINEER) advance(player, VISIT_ENGINEER)
    }

    internal fun reportRemains(player: Player) {
        if (player.inventory.contains(REMAINS) && advance(player, CHECK_TOWER)) {
            player.inventory.remove(REMAINS)
        }
    }

    fun canTakeRemains(player: Player): Boolean = stage(player) == CHECK_TOWER && !player.inventory.contains(REMAINS)

    internal fun enterCave(player: Player) {
        advance(player, FIND_CAVE)
    }

    internal fun finish(player: Player) {
        if (!player.isAlive || !hasSupplies(player) || !progress.complete(player, RETURN_SUPPLIES)) return

        for (id in supplies) player.inventory.remove(id)
        player.skill(Skill.CRAFTING).let { it.experience += 750.0 }
        refresh(player)
        player.playJingle(Jingles.QUEST_COMPLETE_1)
        player.sendMessage("You hand the Dwarf Commander the mould and Nulodion's notes.")
        player.overlays.open(object : StandardInterface(12140) {
            override fun onOpen(plr: Player) {
                plr.sendText("You have completed the\nDwarf Cannon Quest!", 12144)
                plr.queue(WidgetItemModelMessageWriter(12145, 200, CANNONBALL))
                plr.sendText("${progress.questPoints} Quest Point", 12150)
                plr.sendText("750 Crafting XP", 12151)
                for (id in 12152..12155) plr.sendText("", id)
            }
        })
    }

    internal fun markRailing(player: Player, objectId: Int) {
        if (stage(player) != REPAIR_RAILINGS || repairedRailing(player, objectId) || !player.inventory.contains(RAILING)) return
        player.inventory.remove(RAILING)
        setRepair(player, objectId - FIRST_BROKEN_RAILING + 5)
    }

    internal fun markPart(player: Player, part: Int) {
        if (stage(player) != REPAIR_CANNON || repairedPart(player, part) || !player.inventory.contains(TOOLKIT)) return
        setRepair(player, part)
        player.skill(Skill.CRAFTING).let { it.experience += 1.2 }
    }

    fun refresh(player: Player) {
        val colour = when (stage(player)) {
            0 -> 0xff0000
            COMPLETE -> 0x00ff00
            else -> 0xffff00
        }
        player.queue(WidgetColourMessageWriter(JOURNAL_BUTTON, colour))
    }

    fun journal(player: Player) {
        val journal = QuestJournalInterface("Dwarf Cannon")
        val stage = stage(player)
        if (stage == 0) {
            journal.addLine("@dbl@I can start this quest by speaking to the @dre@Dwarf Commander@dbl@.")
            journal.addLine("@dbl@His camp is @dre@north-west of the Fishing Guild@dbl@.")
        } else {
            journal.addLine("@str@The Commander recruited me to help the Black Guard.")
            if (stage > REPAIR_RAILINGS) journal.addLine("@str@I repaired the six broken railings around the camp.")
            if (stage > CHECK_TOWER) journal.addLine("@str@I found Gilob's remains in the watchtower.")
            if (stage > RESCUE_CHILD) journal.addLine("@str@I rescued Gilob's son from the goblins.")
            if (stage > REPAIR_CANNON) journal.addLine("@str@I repaired the broken multicannon in the shed.")
            if (stage > VISIT_ENGINEER) journal.addLine("@str@Nulodion explained how to make cannonballs.")
            journal.newLine()
            when (stage) {
                REPAIR_RAILINGS -> {
                    val fixed = railings.keys.count { repairedRailing(player, it) }
                    journal.addLine(if (fixed == 6) "@dbl@I should report the repairs to the @dre@Commander@dbl@."
                                    else "@dbl@I have repaired $fixed of the six broken railings.")
                }
                CHECK_TOWER -> journal.addLine(
                    if (player.inventory.contains(REMAINS)) "@dbl@I should take the remains to the @dre@Commander@dbl@."
                    else "@dbl@I should look for Gilob in the @dre@watchtower@dbl@ south of camp."
                )
                FIND_CAVE -> journal.addLine("@dbl@I should find the @dre@goblin cave@dbl@ near the Fishing Guild.")
                RESCUE_CHILD -> journal.addLine("@dbl@I should search the cave for @dre@Gilob's son@dbl@.")
                REPORT_RESCUE -> journal.addLine("@dbl@I should tell the @dre@Commander@dbl@ the child is safe.")
                INSPECT_CANNON, REPAIR_CANNON ->
                    journal.addLine("@dbl@I should use the toolkit to fix the @dre@cannon in the shed@dbl@.")
                REPORT_REPAIR -> journal.addLine("@dbl@I should tell the @dre@Commander@dbl@ the cannon is working.")
                VISIT_ENGINEER -> {
                    journal.addLine("@dbl@I should ask @dre@Nulodion@dbl@ what the cannon fires.")
                    journal.addLine("@dbl@He is at the Black Guard base south of Ice Mountain.")
                }
                RETURN_SUPPLIES -> journal.addLine("@dbl@I should take the mould and notes to the @dre@Commander@dbl@.")
                COMPLETE -> journal.addLine("@red@QUEST COMPLETE!")
            }
        }
        player.overlays.open(journal)
    }

    private fun give(player: Player, items: List<Item>): Boolean {
        if (!player.isAlive) return false
        if (!player.inventory.hasSpaceForAll(items)) {
            player.sendMessage("You need more free inventory space.")
            return false
        }
        for (item in items) player.inventory.add(item)
        return true
    }

    private fun hasRepair(player: Player, bit: Int): Boolean =
        player.varpManager.getValue(PersistentVarp.DWARF_CANNON_REPAIRS) and (1 shl bit) != 0

    private fun setRepair(player: Player, bit: Int) {
        val varps = player.varpManager
        val current = varps.getValue(PersistentVarp.DWARF_CANNON_REPAIRS)
        varps.setAndSendValue(PersistentVarp.DWARF_CANNON_REPAIRS, current or (1 shl bit))
    }
}
