package game.skill.crafting.dyeing

import api.predef.*
import game.skill.crafting.dyeing.Dye.Companion.GOBLIN_MAIL
import io.luna.game.model.item.Item

// Mixing two primary dyes makes a secondary one.
for (dye in Dye.values()) {
    val (first, second) = dye.mixedFrom ?: continue
    useItem(first).onItem(second) {
        if (plr.inventory.remove(Item(first)) && plr.inventory.remove(Item(second))) {
            plr.inventory.add(Item(dye.id))
            val article = if (dye.colour[0] in "aeiou") "an" else "a"
            plr.sendMessage("You mix the two dyes and make $article ${dye.colour} one.")
        }
    }
}

for ((dye, mail) in listOf(Dye.ORANGE to Dye.ORANGE_GOBLIN_MAIL, Dye.BLUE to Dye.BLUE_GOBLIN_MAIL)) {
    useItem(dye.id).onItem(GOBLIN_MAIL) {
        val inventory = plr.inventory
        val mailSlot = if (usedItemId == GOBLIN_MAIL) usedItemIndex else targetItemIndex
        val dyeSlot = if (usedItemId == dye.id) usedItemIndex else targetItemIndex
        if (inventory[mailSlot]?.id == GOBLIN_MAIL && inventory[dyeSlot]?.id == dye.id) {
            inventory[dyeSlot] = null
            inventory[mailSlot] = Item(mail)
            plr.sendMessage("You dye the goblin armour ${dye.colour}.")
        }
    }
}

// Any dye turns a cape of another colour into its own.
for (dye in Dye.values()) {
    for ((cape, colour) in Dye.CAPES) {
        if (cape == dye.cape) continue
        useItem(dye.id).onItem(cape) {
            if (plr.inventory.remove(Item(dye.id)) && plr.inventory.remove(Item(cape))) {
                plr.inventory.add(Item(dye.cape))
                plr.crafting.addExperience(Dye.CAPE_EXPERIENCE)
                plr.sendMessage("You dye the $colour cape ${dye.colour}.")
            }
        }
    }
}
