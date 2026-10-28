package engine.bot.speech

import api.attr.Attr
import api.attr.getValue
import api.attr.setValue
import com.google.gson.JsonElement
import io.luna.game.model.mob.bot.Bot
import io.luna.game.model.mob.bot.speech.BotSpeechPool
import io.luna.game.model.mob.bot.speech.BotSpeechStack

/** Complete alternating exchanges: the assertive miner speaks first, except for two grumblers.
 * @author TheLining
 */
object BotMiningArgumentPool : BotSpeechPool<BotMiningArgumentPool.Opponent>(
    "mining_arguments.jsonc", Opponent::class.java
) {
    enum class Opponent { KIND, MEAN, COMPLAINER, POWER_MINER, IRON_TRAINER, BALANCED, GRUMBLERS }

    private data class Exchange(val opponent: Opponent, val lines: List<String>)
    private var exchanges: List<Exchange> = emptyList()
    private var Bot.lastMiningArgument by Attr.int { -1 }

    override fun read(data: JsonElement) {
        val loaded = data.asJsonObject.entrySet().flatMap { (key, value) ->
            val opponent = Opponent.valueOf(key)
            value.asJsonArray.map { dialogue ->
                val lines = dialogue.asJsonArray.map { it.asString }
                require(lines.size in 4..8 && lines.size % 2 == 0 &&
                    lines.all { it.isNotBlank() && it.length <= 80 }) { "Invalid mining argument in $key" }
                Exchange(opponent, lines.map(BotSpeechStack::normalizeChat))
            }
        }.distinct()
        exchanges = loaded
    }

    fun select(first: Bot, second: Bot, opponent: Opponent): List<String>? {
        val matching = exchanges.indices.filter {
            exchanges[it].opponent == opponent ||
                opponent == Opponent.IRON_TRAINER && exchanges[it].opponent == Opponent.POWER_MINER
        }
        val choices = matching.filter { it != first.lastMiningArgument && it != second.lastMiningArgument }
            .ifEmpty { matching }
        val selected = choices.randomOrNull() ?: return null
        first.lastMiningArgument = selected
        second.lastMiningArgument = selected
        return exchanges[selected].lines
    }
}
