package game.bot.scripts.combat

import api.bot.action.eating.MonsterMemory.Companion.monsterMemory
import api.bot.action.prayer.MonsterStyles
import api.predef.*
import io.luna.game.event.impl.CombatDamageReceivedEvent
import io.luna.game.model.mob.Npc
import io.luna.game.model.mob.bot.Bot

// Every attack a monster makes on a player shows how that monster fights. Bots also remember the attacks they take,
// so they learn how hard each kind of monster hits, how it attacks and when their hitpoints dropped.
on(CombatDamageReceivedEvent::class) {
    val npc = damage.attacker as? Npc
    if (npc != null) {
        MonsterStyles.record(npc, damage.type)
    }
    val bot = plr as? Bot ?: return@on
    bot.monsterMemory.remember(npc, damage.type, damage.rawAmount, world.currentTick)
}
