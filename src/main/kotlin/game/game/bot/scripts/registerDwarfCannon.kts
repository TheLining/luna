package game.bot.scripts

import api.predef.*
import game.bot.scripts.DwarfCannonBotScript.DwarfCannonData
import io.luna.game.event.impl.ServerStateChangedEvent.ServerLaunchEvent

on(ServerLaunchEvent::class) {
    world.botManager.scriptManager.addScript<DwarfCannonData>(DwarfCannonBotScript::class) { bot, data ->
        DwarfCannonBotScript(bot, data)
    }
}
