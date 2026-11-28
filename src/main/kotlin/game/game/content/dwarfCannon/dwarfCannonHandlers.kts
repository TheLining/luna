package game.content.dwarfCannon

import api.predef.*
import api.predef.ext.*
import game.content.dwarfCannon.DwarfCannon as Quest
import game.player.QuestJournalInterface
import io.luna.game.event.impl.DefaultTabOpenedEvent
import io.luna.game.model.EntityState
import io.luna.game.model.Position
import io.luna.game.model.mob.Npc
import io.luna.game.model.mob.Player
import io.luna.game.model.mob.Skill
import io.luna.game.model.mob.overlay.GameTabSet.TabIndex

on(DefaultTabOpenedEvent::class).filter { tab == TabIndex.QUEST }.then { Quest.refresh(plr) }
button(Quest.JOURNAL_BUTTON) { Quest.journal(plr) }
npc1(Quest.COMMANDER) { DwarfCannonDialogue.commander(plr) }
npc1(Quest.CHILD) { speakToChild(plr, targetNpc) }

object1(Quest.PLAIN_RAILING) {
    plr.sendMessage(if (Quest.stage(plr) == Quest.REPAIR_RAILINGS)
        "You search the railing but find nothing of interest." else "Nothing interesting happens.")
}

for (id in Quest.railings.keys) {
    object1(id) {
        when {
            Quest.stage(plr) != Quest.REPAIR_RAILINGS -> plr.sendMessage("Nothing interesting happens.")
            Quest.repairedRailing(plr, id) -> plr.sendMessage("You have already fixed this railing.")
            else -> {
                plr.sendMessage("One railing is broken and needs to be replaced.")
                plr.newDialogue()
                    .options(Quest.REPLACE_RAILING, { repairRailing(it, id) }, "Leave it be.", {})
                    .open()
            }
        }
    }
}

object1(Quest.CHILD_CRATE) {
    if (gameObject.position != Quest.childCrate || Quest.stage(plr) != Quest.RESCUE_CHILD) {
        plr.sendMessage("You search the crate but it's empty.")
        return@object1
    }
    val child = world.locator.findNpcs(Quest.childSpawn, 5) {
        it.id == Quest.CHILD && it.state == EntityState.ACTIVE
    }.firstOrNull() ?: world.addNpc(Quest.CHILD, Quest.childSpawn.x, Quest.childSpawn.y).also { spawned ->
        world.scheduleOnce(120) {
            if (spawned.state == EntityState.ACTIVE) world.removeNpc(spawned)
        }
    }
    plr.sendMessage("Inside the crate you find a dwarf child, tied up. You untie him.")
    speakToChild(plr, child)
}

object1(Quest.BROKEN_CANNON) {
    if (gameObject.position == Quest.brokenCannon) inspectCannon(plr)
}

item1(Quest.NOTES) {
    val notes = QuestJournalInterface("Nulodion's notes")
    notes.addLine("@dbl@Ammo for the dwarf multicannon is made from steel bars.")
    notes.addLine("@dbl@Heat the steel in a furnace and pour it into the ammo mould.")
    plr.overlays.open(notes)
}

fun speakToChild(player: Player, child: Npc) {
    if (Quest.stage(player) != Quest.RESCUE_CHILD) return
    child.face(player.position)
    player.newDialogue()
        .npc(Quest.CHILD, "Thank the heavens, you saved me!",
             "I thought I'd be goblin lunch for sure!")
        .npc(Quest.CHILD, "I'd better run home. Thanks again, adventurer.")
        .then {
            if (Quest.advance(it, Quest.RESCUE_CHILD)) {
                it.sendMessage("The dwarf child runs off into the caverns.")
                if (child.state == EntityState.ACTIVE) {
                    child.navigator.navigate(Position(2567, 9848), false)
                    world.scheduleOnce(4) {
                        if (child.state == EntityState.ACTIVE) world.removeNpc(child)
                    }
                }
            }
        }
        .open()
}

fun repairRailing(player: Player, objectId: Int) {
    if (Quest.stage(player) != Quest.REPAIR_RAILINGS || Quest.repairedRailing(player, objectId)) return
    if (!player.inventory.contains(Quest.RAILING)) {
        player.sendMessage("Ask the Dwarf Commander for another railing.")
        return
    }
    if (roll(player, Skill.CRAFTING, 60, 150)) {
        Quest.markRailing(player, objectId)
        player.sendMessage("You replace the broken railing.")
    } else {
        player.sendMessage("You fail to replace the railing and cut yourself trying.")
        player.damage((player.hitpoints.staticLevel / 10).coerceAtMost(3))
    }
}

fun inspectCannon(player: Player) {
    when (Quest.stage(player)) {
        Quest.INSPECT_CANNON -> {
            player.sendMessage("You inspect the cannon. The tool kit should help you fix it.")
            Quest.advance(player, Quest.INSPECT_CANNON)
        }
        Quest.REPAIR_CANNON -> {
            if (Quest.parts.indices.all { Quest.repairedPart(player, it) }) {
                player.sendMessage("The cannon seems to be in working order.")
                Quest.advance(player, Quest.REPAIR_CANNON)
            } else if (!cannonRoll(player)) {
                player.sendMessage("You can't quite find the problem. Try inspecting it again.")
            } else {
                player.sendMessage("The cannon has four damaged components.")
                player.newDialogue().options(
                    Quest.parts[0], { repairPart(it, 0) }, Quest.parts[1], { repairPart(it, 1) },
                    Quest.parts[2], { repairPart(it, 2) }, Quest.parts[3], { repairPart(it, 3) },
                    "None", {}
                ).open()
            }
        }
        else -> player.sendMessage("It's a strange dwarf contraption.")
    }
}

fun repairPart(player: Player, part: Int) {
    if (Quest.stage(player) != Quest.REPAIR_CANNON) return
    when {
        Quest.repairedPart(player, part) -> player.sendMessage("You've already fixed this part.")
        !player.inventory.contains(Quest.TOOLKIT) -> player.sendMessage("You need the tool kit to repair the cannon.")
        cannonRoll(player) -> {
            Quest.markPart(player, part)
            player.sendMessage("After some tinkering, you fix the ${Quest.parts[part].lowercase()}.")
        }
        else -> player.sendMessage("You fail to fix the part. Try again.")
    }
}

fun cannonRoll(player: Player): Boolean =
    roll(player, Skill.SMITHING, 80, 300) || roll(player, Skill.CRAFTING, 80, 350)

fun roll(player: Player, skill: Int, low: Int, high: Int): Boolean {
    val level = player.skill(skill).level.coerceIn(1, 99)
    val success = low * (99 - level) / 98 + high * (level - 1) / 98 + 1
    return rand(255) < success
}
