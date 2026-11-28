package game.bot.scripts

import api.predef.*
import game.bot.scripts.GoblinDiplomacyBotScript.Data
import io.luna.game.event.impl.ServerStateChangedEvent.ServerLaunchEvent

on(ServerLaunchEvent::class) {
    world.botManager.scriptManager.addScript<Data>(GoblinDiplomacyBotScript::class) { bot, data ->
        GoblinDiplomacyBotScript(bot, data)
    }
}
