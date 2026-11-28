package game.content.dwarfCannon

import game.content.dwarfCannon.DwarfCannon as Quest
import game.content.dwarfMulticannon.DwarfMulticannon.NULODION
import io.luna.game.model.mob.Player

/**
 * The Commander's assignments and Nulodion's story hand-off.
 *
 * @author lare96
 */
object DwarfCannonDialogue {

    fun commander(player: Player) {
        val dialogue = player.newDialogue()
        val stage = Quest.stage(player)
        when (stage) {
            0 -> dialogue.npc(Quest.COMMANDER, "Hello traveller. The Black Guard could use another",
                              "pair of hands. Can you help us protect the mines?")
                .options("I'm sorry, I'm too busy mining.", {},
                         Quest.ACCEPT_ASSIGNMENT, { Quest.start(it) })
            Quest.REPAIR_RAILINGS -> {
                if (Quest.railings.keys.all { Quest.repairedRailing(player, it) }) {
                    dialogue.npc(Quest.COMMANDER, "The goblins have stopped getting in. Good work!")
                        .npc(Quest.COMMANDER, "Our guard should have reported in by now.",
                             "Could you check the watchtower to the south?")
                        .then { Quest.advance(it, Quest.REPAIR_RAILINGS) }
                } else {
                    dialogue.npc(Quest.COMMANDER, "There are still broken railings around the camp.")
                        .npc(Quest.COMMANDER, "We've got more railings if you run out.")
                        .then { Quest.giveReplacementRailing(it) }
                }
            }
            Quest.CHECK_TOWER -> {
                if (player.inventory.contains(Quest.REMAINS)) {
                    dialogue.npc(Quest.COMMANDER, "What's this? Oh no, it can't be!",
                                 "Gilob's son was with him.")
                        .npc(Quest.COMMANDER, "The goblins must have taken the lad.",
                             "Please find their hideout and bring him home.")
                        .then { Quest.reportRemains(it) }
                } else {
                    dialogue.npc(Quest.COMMANDER, "Gilob never leaves his post. Look for him and his",
                                 "son in the watchtower just south of camp.")
                }
            }
            Quest.FIND_CAVE -> dialogue.npc(Quest.COMMANDER, "Have you found where the goblins are hiding?")
            Quest.RESCUE_CHILD -> dialogue.npc(Quest.COMMANDER, "Keep looking for Gilob's son in the cave.")
            Quest.REPORT_RESCUE -> dialogue.npc(Quest.COMMANDER, "The lad is back safely, thanks to you!",
                                              "The Black Guard sent us a cannon, but it's broken.",
                                              "Could you fix it? You'll find it in our shed.")
                .options(Quest.ACCEPT_REPAIR, { Quest.giveToolkit(it, Quest.REPORT_RESCUE) },
                         "Sorry, I've done enough for today.", {})
            Quest.INSPECT_CANNON, Quest.REPAIR_CANNON -> dialogue.npc(
                Quest.COMMANDER, "Let me know when you've got the cannon working."
            ).npc(Quest.COMMANDER, "If you've lost the tool kit, I've got a spare.")
                .then { Quest.giveToolkit(it, stage) }
            Quest.REPORT_REPAIR -> dialogue.npc(Quest.COMMANDER, "Well, I don't believe it. It works!",
                                              "The Black Guard forgot to send instructions.",
                                              "Could you ask Nulodion what it fires?")
                .npc(Quest.COMMANDER, "He's our cannon engineer. You'll find him at the",
                     "Black Guard base just south of Ice Mountain.")
                .options("Sorry, I've really done enough.", {},
                         Quest.ACCEPT_ENGINEER, { Quest.advance(it, Quest.REPORT_REPAIR) })
            Quest.VISIT_ENGINEER -> dialogue.npc(Quest.COMMANDER, "Any word from the cannon engineer?",
                                                "His base is just south of Ice Mountain.")
            Quest.RETURN_SUPPLIES -> {
                if (Quest.hasSupplies(player)) {
                    dialogue.npc(Quest.COMMANDER, "A mould and the instructions! Of course, we",
                                 "have to make the ammunition.")
                        .npc(Quest.COMMANDER, "Now we can defend ourselves.",
                             "Thank you for all your help!")
                        .then { Quest.finish(it) }
                } else {
                    dialogue.npc(Quest.COMMANDER, "I'm still waiting for the mould and instructions.",
                                 "Please speak to Nulodion if you've lost them.")
                }
            }
            Quest.COMPLETE -> dialogue.npc(Quest.COMMANDER, "The goblins keep their distance now the",
                                          "cannon is working. Thanks again, traveller.")
        }
        dialogue.open()
    }

    fun engineer(player: Player) {
        if (Quest.stage(player) !in Quest.VISIT_ENGINEER..Quest.RETURN_SUPPLIES) return
        player.newDialogue()
            .npc(NULODION, "Of course! We forgot to send an ammo mould.")
            .npc(NULODION, "Take this mould and these notes to the Commander.",
                 "They explain how to make the cannonballs.")
            .then { Quest.giveSupplies(it) }
            .open()
    }
}
