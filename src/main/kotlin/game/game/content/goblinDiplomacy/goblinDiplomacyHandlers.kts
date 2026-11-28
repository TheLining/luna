package game.content.goblinDiplomacy

import api.predef.*
import game.content.treasureTrails.TrailClues
import io.luna.game.event.impl.DefaultTabOpenedEvent
import io.luna.game.model.mob.overlay.GameTabSet.TabIndex

on(DefaultTabOpenedEvent::class).filter { tab == TabIndex.QUEST }.then { GoblinDiplomacy.refresh(plr) }
button(GoblinDiplomacy.JOURNAL_BUTTON) { GoblinDiplomacy.journal(plr) }
npc1(GoblinDiplomacy.BENTNOZE) {
    if (TrailClues.all.none { it.npcId == targetNpc.id && plr.inventory.contains(it.id) }) {
        GoblinGenerals.talk(plr, targetNpc.id)
    }
}
npc1(GoblinDiplomacy.WARTFACE) { GoblinGenerals.talk(plr, targetNpc.id) }
