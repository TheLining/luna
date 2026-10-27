package engine.bot.speech

import api.attr.Attr
import api.attr.getValue
import api.attr.setValue
import api.bot.social.BotConversations
import api.predef.rand
import api.predef.world
import io.luna.game.model.mob.bot.Bot
import io.luna.game.model.mob.bot.speech.BotSpeech
import io.luna.game.model.mob.bot.speech.BotSpeechPool

/** Contextual mining reactions with a per-bot cooldown that survives mining-script changes.
 * @author TheLining
 */
object BotMiningSpeechPool : BotSpeechPool<BotMiningSpeechPool.Reaction>("mining.jsonc", Reaction::class.java) {
    enum class Reaction { YIELD, CONTEST, CROWDED, LOST_ROCK }

    private var Bot.nextMiningReaction by Attr.long()

    fun react(bot: Bot, reaction: Reaction) {
        if (bot.localHumans.isEmpty() || bot.speechStack.isAllDisabled || BotConversations.engaged(bot)) return
        val now = world.currentTick
        if (now < bot.nextMiningReaction) return
        val complaining = reaction == Reaction.CROWDED || reaction == Reaction.LOST_ROCK
        bot.nextMiningReaction = now + if (complaining) rand(150, 250) else rand(300, 500)
        if (!rand(bot.personality.social.coerceIn(0.0, 1.0) * 0.65)) return
        val text = take(bot, reaction) ?: return
        bot.speechStack.pushHead(BotSpeech(text, rand(2, 5)))
    }
}
