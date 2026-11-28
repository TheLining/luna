package game.skill.magic.teleportSpells

import api.predef.*
import game.content.randomEvents.RandomEvents
import game.skill.magic.Magic.findLandingTile
import game.skill.magic.teleportSpells.TeleportAction.Companion.teleportDelay
import io.luna.util.StringUtils

/**
 * Spellbook teleports with general-event entry points in Lost City's supplied teleport script.
 * Item, travel and other spellbook teleports retain their existing general timer.
 */
val generalEventSpells = setOf(TeleportSpell.VARROCK, TeleportSpell.LUMBRIDGE, TeleportSpell.FALADOR,
    TeleportSpell.CAMELOT, TeleportSpell.ARDOUGNE, TeleportSpell.WATCHTOWER)

/**
 * How far from a spell's destination the player may land.
 */
val LANDING_RADIUS = 2

/* Intercept all button clicks.*/
for (spell in TeleportSpell.VALUES) {
    button(spell.button) {
        if (plr.teleportDelay.ready(2)) { // So player can't button spam.
            plr.submitAction(object : TeleportAction(plr,
                                                     spell.level,
                                                     spell.xp,
                                                     findLandingTile(spell.destination, LANDING_RADIUS),
                                                     spell.style,
                                                     spell.requirements) {
                override fun beforeLock(): Boolean = super.beforeLock() &&
                    (spell !in generalEventSpells || !RandomEvents.offerGeneral(plr))

                override fun onTeleport() {
                    plr.sendMessage("You teleport to ${StringUtils.capitalize(spell.displayName)}.")
                }
            })
        }
    }
}
