package game.bot.scripts.skills

import api.attr.Attr
import api.attr.getValue
import api.attr.setValue
import api.bot.social.BotConversations
import api.bot.zone.SubZone
import api.predef.rand
import api.predef.world
import engine.bot.speech.BotMiningArgumentPool
import engine.bot.speech.BotMiningArgumentPool.Opponent
import game.bot.scripts.skills.MiningBehavior.Style
import game.skill.mining.Ore
import game.skill.mining.mineOre.MineOreAction
import io.luna.game.model.EntityState
import io.luna.game.model.mob.bot.Bot
import io.luna.game.model.mob.bot.io.BotOutputMessageHandler.ChatColor
import io.luna.game.model.mob.bot.io.BotOutputMessageHandler.ChatEffect
import io.luna.game.model.mob.bot.speech.BotSpeech
import io.luna.game.model.`object`.GameObject
import java.util.EnumMap

/** Rare, mutually reserved bot-to-bot arguments prompted by an actual contested, depleting rock.
 * @author TheLining
 */
internal object MiningArguments {
    private var Bot.nextMiningArgumentCheck by Attr.long()
    private var Bot.nextMiningArgument by Attr.long()
    private val nextMineArgument = EnumMap<SubZone, Long>(SubZone::class.java)

    fun consider(bot: Bot, rock: GameObject) {
        val ore = Ore.ROCK_MAP[rock.id] ?: return
        if (ore.respawnTicks == null || rock.state != EntityState.ACTIVE || bot.localHumans.isEmpty()) return
        val script = bot.scriptStack.current() as? MineBotScript ?: return
        val zone = script.miningZone ?: return
        val now = world.currentTick
        if (now < bot.nextMiningArgumentCheck || now < bot.nextMiningArgument ||
            now < (nextMineArgument[zone] ?: 0L)) return
        // First exposure only schedules a roll, avoiding a burst on login or a server restart.
        if (bot.nextMiningArgumentCheck == 0L) {
            bot.nextMiningArgumentCheck = now + rand(300, 600)
            return
        }
        if (!BotConversations.availableWhileWorking(bot)) return
        bot.nextMiningArgumentCheck = now + rand(300, 600)
        val rivals = world.locator.findPlayers(bot.position, 8) { player ->
            player is Bot && player !== bot && player.state == EntityState.ACTIVE &&
                player.actions.first(MineOreAction::class.java)?.rockObj === rock
        }.filterIsInstance<Bot>().filter { rival ->
            val other = rival.scriptStack.current() as? MineBotScript
            other != null && other.miningZone == zone && now >= rival.nextMiningArgument &&
                (script.behavior.style == Style.MEAN || other.behavior.style == Style.MEAN ||
                    script.behavior.style == Style.COMPLAINER && other.behavior.style == Style.COMPLAINER) &&
                BotConversations.availableWhileWorking(rival)
        }
        val rival = rivals.randomOrNull() ?: return
        // At most one 1.5% roll per 3–6 minutes of observed competition, never one roll per swing.
        if (!rand(0.015)) return
        val other = rival.scriptStack.current() as MineBotScript
        val first = if (script.behavior.style == Style.MEAN) bot else rival
        val second = if (first === bot) rival else bot
        val secondScript = if (second === bot) script else other
        val opponent = when {
            script.behavior.style == Style.COMPLAINER && other.behavior.style == Style.COMPLAINER -> Opponent.GRUMBLERS
            secondScript.powerMining -> Opponent.IRON_TRAINER
            else -> Opponent.valueOf(secondScript.behavior.style.name)
        }
        val lines = BotMiningArgumentPool.select(first, second, opponent) ?: return
        val conversation = BotConversations.start(first, second, lines, whileWorking = true,
            speechFactory = { text, ticks ->
                if (text.startsWith("hop worlds", ignoreCase = true))
                    BotSpeech(text, ChatColor.RED, ChatEffect.SHAKE, ticks)
                else BotSpeech(text, ticks)
            }) ?: return
        // Both participants cool down for 60–90 minutes, even if movement interrupts their exchange.
        first.nextMiningArgument = now + rand(6_000, 9_000)
        second.nextMiningArgument = now + rand(6_000, 9_000)
        // A busy guild cannot turn hundreds of rare individual rolls into constant public arguments.
        nextMineArgument[zone] = now + rand(3_000, 4_500)
        conversation.tick()
    }
}
