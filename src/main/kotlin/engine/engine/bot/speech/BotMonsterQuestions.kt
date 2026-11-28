package engine.bot.speech

import api.attr.Attr
import api.attr.getValue
import api.attr.setValue
import api.bot.script.BotGoals
import api.predef.*
import engine.bot.speech.BotReactionSpeechPool.ReactionSpeech
import io.luna.game.model.def.NpcCombatDefinition
import io.luna.game.model.def.NpcDefinition
import io.luna.game.model.mob.Mob
import io.luna.game.model.mob.Player
import io.luna.game.model.mob.bot.Bot
import io.luna.game.model.mob.bot.brain.BotAttention
import io.luna.game.model.mob.bot.brain.BotAttention.Notice
import io.luna.game.model.mob.bot.memory.BotKnowHowMemory.Seen
import io.luna.game.model.mob.bot.speech.BotSpeech
import io.luna.game.model.mob.combat.damage.CombatDamageType

/**
 * Bots answering a real player who asks about a monster: how hard it hits, whether it is hard to kill and what to
 * pray against it.
 *
 * A bot answers from what it has seen of the monster itself. It names the biggest hit it has taken, not the monster's
 * real maximum, and says so if the monster has killed it. A question put to a bot by name is answered by that bot,
 * even if it has never fought the monster. A question to everyone gets one answer, from a bot that has fought the
 * monster if one can see the speaker, is free to chat and passes a roll on its social trait.
 *
 * A message only counts as a question if what it asks about is the name of a monster. Answers never repeat the
 * speaker's words: the monster is named from the game's own data.
 *
 * Each answer goes to the bot's [BotAttention], like a greeting, so it waits a few ticks and is dropped if the speaker
 * has gone.
 *
 * @author TheLining
 */
object BotMonsterQuestions : BotChatHandler {

    /**
     * What a question wants to know about a monster.
     */
    internal enum class About {

        /**
         * How hard it hits.
         */
        HIT,

        /**
         * Whether it is hard to kill.
         */
        HARD,

        /**
         * How it attacks, or what to pray against it.
         */
        STYLE
    }

    /**
     * A question read from chat.
     *
     * @property monster The monster's name in lowercase, as the game has it.
     * @property about What the speaker wants to know.
     */
    internal data class Question(val monster: String, val about: About)

    /**
     * What fills the tags of a bot's answer.
     *
     * @property monster The monster, in the plural: "hill giants".
     * @property hit The biggest hit the bot has taken from one, or [BotGoals.MISSING].
     * @property times How often one has killed the bot ("once", "twice", "3 times"), or [BotGoals.MISSING].
     * @property style How it attacks ("melee", "range", "mage"), or [BotGoals.MISSING].
     */
    class Line(val monster: String, val hit: String, val times: String, val style: String)

    /**
     * How many ticks pass before bots hear the same player ask again.
     */
    private const val SPEAKER_COOLDOWN = 50L

    /**
     * How many ticks pass before a bot answers another question about monsters.
     */
    private const val ANSWER_COOLDOWN = 300L

    /**
     * How many ticks an answer may still be said in, if the bot is busy when its delay ends.
     */
    private const val REPLY_LIFETIME = 15

    /**
     * How many kills without a death make a monster easy in a bot's eyes.
     */
    private const val EASY_KILLS = 10

    /**
     * How often a bot that has never fought the monster says so, when no bot that has is there to answer.
     */
    private const val UNKNOWN_ANSWER_CHANCE = 0.5

    private const val ARTICLE = "(?:a |an |the )?"

    /**
     * How players ask what a monster hits: "what do hill giants hit", "how hard does a moss giant hit", "whats a
     * lesser demons max", "max hit of hill giants", "hill giant max hit".
     */
    private val HIT_QUESTIONS = listOf(
        Regex("^(?:what|wat|wut|wot) (?:do|does|can|did) $ARTICLE(.+?) (?:hit|max|max hit)" +
                  "(?: for| like| up to| on (?:you|u|ya))?$"),
        Regex("^how (?:hard|high|much|big) (?:do|does|can) $ARTICLE(.+?) hit$"),
        Regex("^(?:whats|what s|what is|wats|wuts) $ARTICLE(.+?) max(?: hit)?$"),
        Regex("^(?:whats |what s |what is |wats )?(?:the )?max hit (?:of|for|on|from) $ARTICLE(.+)$"),
        Regex("^(.+?) max hit$"))

    /**
     * How players ask whether a monster is hard: "are hill giants hard", "how hard are moss giants", "anyone killed
     * lesser demons".
     */
    private val HARD_QUESTIONS = listOf(
        Regex("^(?:are|r|is) $ARTICLE(.+?) (?:hard|tough|dangerous|strong|easy|safe|scary)" +
                  "(?: to (?:kill|fight|train on))?$"),
        Regex("^how (?:hard|tough|dangerous|strong|easy|safe) (?:are|r|is) $ARTICLE(.+)$"),
        Regex("^(?:has |have |did )?(?:anyone|any1|any 1|someone|some1|you|u|ya) (?:here )?(?:ever )?" +
                  "(?:killed|kill|fought|fight|tried|done) $ARTICLE(.+?)(?: before)?$"))

    /**
     * How players ask how a monster attacks: "what do i pray at hill giants", "what prayer for dagannoths", "what do
     * wizards attack with", "do ice warriors use mage".
     */
    private val STYLE_QUESTIONS = listOf(
        Regex("^(?:what|wat|wut|which) (?:do|should|shud|would) (?:i|u|you|we) pray " +
                  "(?:at|on|for|against|vs|with) $ARTICLE(.+)$"),
        Regex("^(?:what|wat|wut|which) (?:prayer|pray|protect|protection)(?: do i use| to use| should i use)? " +
                  "(?:at|on|for|against|vs) $ARTICLE(.+)$"),
        Regex("^(?:what|wat|wut) (?:do|does) $ARTICLE(.+?) (?:attack with|use|hit with|fight with)$"),
        Regex("^(?:do|does) $ARTICLE(.+?) (?:use|hit with|attack with) (?:mage|magic|range|ranged|melee)$"))

    /**
     * Short names players use for monsters.
     */
    private val NICKNAMES = mapOf("kbd" to "king black dragon", "kq" to "kalphite queen",
                                  "lessers" to "lesser demon", "lesser" to "lesser demon",
                                  "greaters" to "greater demon", "greater" to "greater demon")

    /**
     * "drag" and "drags", as players shorten dragons.
     */
    private val DRAG = Regex("\\bdrag(s?)\\b")

    /**
     * The name in lowercase of every monster that can be fought, by that name as it reads in chat, where punctuation
     * is dropped ("tz kih" for Tz-Kih).
     */
    private val monsters: Map<String, String> by lazy {
        NpcCombatDefinition.ALL.mapNotNull { combat ->
            NpcDefinition.ALL.get(combat.id()).map { it.name?.lowercase() }.orElse(null)
        }.filter { it.isNotBlank() && it != "null" }.associateBy { BotGreetings.words(it).joinToString(" ") }
    }

    /**
     * The world tick from which bots hear this player's questions again.
     */
    private var Player.nextMonsterQuestionHeard by Attr.long()

    /**
     * The world tick from which this bot answers another question about monsters.
     */
    private var Bot.nextMonsterAnswer by Attr.long()

    /**
     * What fills the tags of this bot's answer.
     */
    var Bot.monsterLine by Attr.obj { Line("null", BotGoals.MISSING, BotGoals.MISSING, BotGoals.MISSING) }

    override fun handle(message: BotChatMessage): Boolean {
        val speaker = message.speaker
        val now = world.currentTick
        if (speaker.isBot || now < speaker.nextMonsterQuestionHeard) {
            return false
        }
        val words = BotGreetings.words(message.text)
        val rest = words.drop(BotGreetings.greetingWords(words))
        val question = parse(rest) { monsters[it] } ?: return false
        speaker.nextMonsterQuestionHeard = now + SPEAKER_COOLDOWN
        val named = message.hearers.filter { BotGreetings.names(rest, it.username) }
        val willing = if (named.isNotEmpty()) {
            named.filter { BotGreetings.onSpeakingTerms(it, speaker) && rand(0.5 + 0.5 * social(it)) }
        } else {
            val free = message.hearers.filter {
                now >= it.nextMonsterAnswer && BotGreetings.onSpeakingTerms(it, speaker)
            }
            free.filter { helps(question.about, record(it, question.monster)) && rand(social(it)) }.ifEmpty {
                if (rand(UNKNOWN_ANSWER_CHANCE)) free.filter { rand(social(it)) } else emptyList()
            }
        }
        val answer = BotAttention.Reaction { bot, source -> say(bot, source, question) }
        for (bot in BotReactions.pickResponders(willing, 1)) {
            val delay = BotGreetings.replyTicks(bot.personality.dexterity)
            if (bot.attention.offer(Notice.QUESTION, speaker, delay, REPLY_LIFETIME, answer)) {
                bot.nextMonsterAnswer = now + ANSWER_COOLDOWN
                return true
            }
        }
        return false
    }

    /**
     * Reads a question about a monster from a message. A bot's name before or after the question is skipped.
     *
     * @param words The words of the message in lowercase, without a leading greeting.
     * @param monster Gives a monster's name as the game has it, or `null` if the name isn't a monster's.
     * @return The question, or `null` if the message doesn't ask about a monster.
     */
    internal fun parse(words: List<String>, monster: (String) -> String?): Question? {
        if (words.size < 3) {
            return null
        }
        val readings = listOf(words, words.drop(1), words.dropLast(1), words.drop(2), words.dropLast(2))
        for (reading in readings) {
            if (reading.size < 3) {
                continue
            }
            val text = reading.joinToString(" ")
            for ((about, questions) in listOf(About.HIT to HIT_QUESTIONS, About.HARD to HARD_QUESTIONS,
                                              About.STYLE to STYLE_QUESTIONS)) {
                for (question in questions) {
                    val asked = question.matchEntire(text)?.groupValues?.get(1) ?: continue
                    val name = singulars(asked).firstNotNullOfOrNull(monster) ?: continue
                    return Question(name, about)
                }
            }
        }
        return null
    }

    /**
     * The names a monster may have in the game's data, for the way a player wrote it: as written, by its nickname,
     * and without a plural ending ("hill giants", "wolves", "zombies", "men").
     */
    internal fun singulars(asked: String): List<String> {
        val name = DRAG.replace(asked.trim().removeSuffix(" s"), "dragon$1")
        val names = mutableListOf<String>()
        NICKNAMES[name]?.let { names += it }
        names += name
        if (name.endsWith("ves")) names += name.dropLast(3) + "f"
        if (name.endsWith("ies")) names += name.dropLast(3) + "y"
        if (name.endsWith("men")) names += name.dropLast(3) + "man"
        if (name.endsWith("es")) names += name.dropLast(2)
        if (name.endsWith("s")) names += name.dropLast(1)
        return names
    }

    /**
     * A monster's name in the plural, for a line that talks about the kind: "hill giants", "wolves", "zombies".
     */
    internal fun plural(name: String): String = when {
        name.endsWith("s") -> name
        name.endsWith("man") -> name.dropLast(3) + "men"
        name.endsWith("wolf") || name.endsWith("dwarf") || name.endsWith("elf") -> name.dropLast(1) + "ves"
        name.endsWith("y") && name.length > 1 && name[name.length - 2] !in "aeiou" -> name.dropLast(1) + "ies"
        name.endsWith("ch") || name.endsWith("sh") || name.endsWith("x") -> name + "es"
        else -> name + "s"
    }

    /**
     * What a bot has seen of every monster with a name, whatever their combat levels: the biggest hit from any of
     * them, all its kills and deaths, and the way the one it remembers best attacks.
     *
     * @param seen Every kind of monster the bot has fought, the one it remembers best first.
     * @param monster The monster's name.
     * @return What it has seen, or `null` if it has never fought one.
     */
    internal fun record(seen: List<Seen>, monster: String): Seen? {
        val kinds = seen.filter { it.kind.name.equals(monster, ignoreCase = true) }
        if (kinds.isEmpty()) {
            return null
        }
        return Seen(kinds.first().kind, kinds.maxOf { it.biggestHit }, kinds.firstNotNullOfOrNull { it.style },
                    kinds.sumOf { it.kills }, kinds.sumOf { it.deaths })
    }

    private fun record(bot: Bot, monster: String): Seen? = record(bot.memory.knowHow.monsters(), monster)

    /**
     * Whether what a bot has seen of a monster answers the question.
     */
    internal fun helps(about: About, record: Seen?): Boolean = record != null && when (about) {
        About.HIT -> record.biggestHit > 0
        About.HARD -> record.kills > 0 || record.deaths > 0 || record.biggestHit > 0
        About.STYLE -> record.style != null
    }

    /**
     * Picks the kind of line that answers a question and what fills its tags.
     *
     * @param question The question.
     * @param record What the bot has seen of the monster, or `null` if it has never fought one.
     */
    internal fun answer(question: Question, record: Seen?): Pair<ReactionSpeech, Line> {
        val line = Line(monster = plural(question.monster),
                        hit = record?.biggestHit?.takeIf { it > 0 }?.toString() ?: BotGoals.MISSING,
                        times = when (val deaths = record?.deaths ?: 0) {
                            0 -> BotGoals.MISSING
                            1 -> "once"
                            2 -> "twice"
                            else -> "$deaths times"
                        },
                        style = when (record?.style) {
                            CombatDamageType.MELEE -> "melee"
                            CombatDamageType.RANGED -> "range"
                            CombatDamageType.MAGIC -> "mage"
                            else -> BotGoals.MISSING
                        })
        val kind = when {
            !helps(question.about, record) -> ReactionSpeech.MONSTER_UNKNOWN
            question.about == About.HIT -> ReactionSpeech.MONSTER_HIT
            question.about == About.STYLE -> ReactionSpeech.MONSTER_STYLE
            record!!.deaths > 0 -> ReactionSpeech.MONSTER_DEADLY
            record.kills >= EASY_KILLS -> ReactionSpeech.MONSTER_EASY
            else -> ReactionSpeech.MONSTER_FINE
        }
        return kind to line
    }

    /**
     * A bot's social trait as a chance from 0 to 1.
     */
    private fun social(bot: Bot): Double {
        val social = bot.personality.social
        return if (social.isFinite()) social.coerceIn(0.0, 1.0) else 0.0
    }

    private fun say(bot: Bot, source: Mob, question: Question): Boolean {
        if (source !is Player) {
            return true
        }
        if (!BotGreetings.canTalk(bot)) {
            return false
        }
        val (kind, line) = answer(question, record(bot, question.monster))
        bot.monsterLine = line
        BotReactionSpeechPool.take(bot, kind, BotGoals::complete)?.let { bot.speechStack.pushHead(BotSpeech(it, 0)) }
        return true
    }
}
