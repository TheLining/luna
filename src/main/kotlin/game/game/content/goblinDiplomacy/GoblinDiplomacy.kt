/* Adapted from LostCityRS/Content quest_gobdip at 44eab09081ef0f6c5f3eaabd1897a588ad7d9e50. */
package game.content.goblinDiplomacy

import api.predef.ext.playJingle
import api.quest.QuestProgress
import game.player.Jingles
import game.player.Messages
import game.player.QuestJournalInterface
import io.luna.game.model.item.Item
import io.luna.game.model.mob.Player
import io.luna.game.model.mob.Skill
import io.luna.game.model.mob.overlay.StandardInterface
import io.luna.game.model.mob.varp.PersistentVarp
import io.luna.net.msg.out.WidgetColourMessageWriter
import io.luna.net.msg.out.WidgetItemModelMessageWriter

/**
 * Saves the generals' armour trials and presents the journal and completion reward.
 *
 * @author lare96
 */
object GoblinDiplomacy {
    const val BENTNOZE = 296
    const val WARTFACE = 297
    const val BARTENDER = 734
    const val JOURNAL_BUTTON = 7338
    const val GOLD_BAR = 2357
    const val CRAFTING_XP = 200.0
    const val STARTED = 1
    const val NEED_ORANGE = 2
    const val NEED_BLUE = 3
    const val NEED_BROWN = 4
    const val GAVE_BROWN = 5
    const val COMPLETE = 6

    const val ASK_RUMOUR = "Not very busy in here today, is it?"
    val offerToInvestigate = listOf("Well, if I have time, I'll see if I can go and knock some", "sense into them.")

    private val progress = QuestProgress(PersistentVarp.GOBLIN_DIPLOMACY, COMPLETE, 5)

    fun stage(player: Player): Int = progress.stage(player)

    fun start(player: Player) {
        if (player.isAlive && progress.advance(player, STARTED)) refresh(player)
    }

    internal fun promiseArmour(player: Player) {
        if (player.isAlive && stage(player) == STARTED) progress.advance(player, NEED_ORANGE)
    }

    internal fun handIn(player: Player, expectedStage: Int, armour: Int): Boolean {
        if (!player.isAlive || stage(player) != expectedStage || !player.inventory.remove(armour)) return false
        return progress.advance(player, expectedStage + 1)
    }

    internal fun finish(player: Player) {
        if (!player.isAlive || stage(player) != GAVE_BROWN) return
        val reward = Item(GOLD_BAR)
        if (!player.inventory.hasSpaceFor(reward)) {
            player.sendMessage(Messages.inventoryFull())
            return
        }
        if (!progress.complete(player, GAVE_BROWN)) return

        check(player.inventory.add(reward))
        player.skill(Skill.CRAFTING).let { it.experience += CRAFTING_XP }
        refresh(player)
        player.playJingle(Jingles.QUEST_COMPLETE_1)
        player.overlays.open(object : StandardInterface(12140) {
            override fun onOpen(plr: Player) {
                plr.sendText("You have completed the\\nGoblin Diplomacy Quest!", 12144)
                plr.queue(WidgetItemModelMessageWriter(12145, 250, GOLD_BAR))
                plr.sendText("${progress.questPoints} Quest Points", 12150)
                plr.sendText("200 Crafting XP", 12151)
                plr.sendText("A Gold Bar", 12152)
                for (id in 12153..12155) plr.sendText("", id)
            }
        })
    }

    fun refresh(player: Player) {
        val colour = when (stage(player)) {
            0 -> 0xff0000
            COMPLETE -> 0x00ff00
            else -> 0xffff00
        }
        player.queue(WidgetColourMessageWriter(JOURNAL_BUTTON, colour))
    }

    fun journal(player: Player) {
        val stage = stage(player)
        val journal = QuestJournalInterface("Goblin Diplomacy")
        if (stage >= STARTED) {
            journal.addLine("@str@The Rusty Anchor bartender told me the goblins were")
            journal.addLine("@str@arguing over the colour of their armour.")
        }
        if (stage >= NEED_ORANGE) {
            journal.addLine("@str@I offered to find a colour both generals could agree on.")
        }
        if (stage >= NEED_BLUE) {
            journal.addLine("@str@General Bentnoze and General Wartface rejected orange.")
        }
        if (stage >= NEED_BROWN) {
            journal.addLine("@str@They rejected blue and asked for their original brown armour.")
        }
        if (stage >= GAVE_BROWN) {
            journal.addLine("@str@I gave the generals some of their original brown armour.")
        }
        if (stage > 0) journal.newLine()
        when (stage) {
            0 -> {
                journal.addLine("@dbl@I can start by talking to the @dre@Bartender@dbl@ at the")
                journal.addLine("@dre@Rusty Anchor@dbl@ in @dre@Port Sarim@dbl@.")
            }
            STARTED -> journal.addLine("@dbl@I should visit the generals in the @dre@Goblin Village@dbl@.")
            NEED_ORANGE -> journal.addLine("@dbl@I should bring the generals @dre@orange goblin armour@dbl@.")
            NEED_BLUE -> journal.addLine("@dbl@I should bring the generals @dre@blue goblin armour@dbl@.")
            NEED_BROWN -> journal.addLine("@dbl@I should bring the generals @dre@brown goblin armour@dbl@.")
            GAVE_BROWN -> journal.addLine("@dbl@I should see what the generals thought of the brown armour.")
            COMPLETE -> {
                journal.addLine("@str@The generals agreed to keep their brown armour.")
                journal.newLine()
                journal.addLine("@red@QUEST COMPLETE!")
            }
        }
        player.overlays.open(journal)
    }
}
