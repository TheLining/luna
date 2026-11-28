/* Adapted from LostCityRS/Content quest_gobdip at 44eab09081ef0f6c5f3eaabd1897a588ad7d9e50. */
package game.content.goblinDiplomacy

import game.content.goblinDiplomacy.GoblinDiplomacy as Quest
import game.skill.crafting.dyeing.Dye
import io.luna.game.model.mob.Player
import io.luna.game.model.mob.dialogue.DialogueQueueBuilder
import io.luna.game.model.mob.dialogue.DialogueUtils
import io.luna.game.model.mob.dialogue.Expression

/**
 * Gives either general's side of the dispute and handles the three armour trials in order.
 *
 * @author lare96
 */
object GoblinGenerals {
    const val OFFER = "Shall I find a colour you can both agree on?"
    const val DIFFERENT_COLOUR = "Something other than green or red?"
    const val HAND_IN = "You hand the armour to the generals."
    const val SHOW_ORANGE = "I brought you some orange armour."
    const val SHOW_BLUE = "Here's some blue armour to try."
    const val SHOW_BROWN = "I found some brown armour."
    val blueReply = listOf("Isn't brown what you started with?", "All right, I'll bring some.")

    private const val ASK_ARGUMENT = "What's all this fuss over armour?"
    private const val ASK_PEACE = "Can't you settle this peacefully?"
    private const val FIRST_GOBLIN_CHAT_SEQUENCE = 580

    fun talk(player: Player, general: Int) {
        val stage = Quest.stage(player)
        val dialogue = player.newDialogue()
        when (stage) {
            Quest.COMPLETE -> dialogue.goblin(Quest.WARTFACE, "What we do now we got nothing to argue about?")
                .goblin(Quest.BENTNOZE, "Dunno. Bit boring, this.")
            Quest.GAVE_BROWN -> finish(dialogue)
            else -> {
                if (general == Quest.BENTNOZE) {
                    dialogue.goblin(general, "Red is best for armour!")
                        .goblin(Quest.WARTFACE, "Green is better, you daft goblin!")
                } else {
                    dialogue.goblin(general, "Green is best for armour!")
                        .goblin(Quest.BENTNOZE, "No! Red, every time!")
                }
                dialogue.goblin(general, "Busy here, human. Go away.")
                when (stage) {
                    Quest.STARTED -> choices(dialogue)
                    Quest.NEED_ORANGE, Quest.NEED_BLUE, Quest.NEED_BROWN -> trial(player, stage, general, dialogue)
                }
            }
        }
        dialogue.open()
    }

    private fun choices(dialogue: DialogueQueueBuilder) {
        dialogue.options(ASK_ARGUMENT, {
            val reply = it.newDialogue().player(ASK_ARGUMENT)
                .goblin(Quest.BENTNOZE, "New goblin century, new armour colour.",
                    "We had enough of brown.")
                .goblin(Quest.WARTFACE, "Only they want wrong colour.")
            choices(reply)
            reply.open()
        }, ASK_PEACE, {
            val reply = it.newDialogue().player(ASK_PEACE)
                .goblin(Quest.WARTFACE, "We'll have peace when everyone wears green.")
                .goblin(Quest.BENTNOZE, "Green armour look just like skin. You look naked!")
            choices(reply)
            reply.open()
        }, OFFER, {
            it.newDialogue().player(Expression.CONFUSED, OFFER)
                .player(Expression.QUIZZICAL, DIFFERENT_COLOUR)
                .goblin(Quest.WARTFACE, "Bring some here so we can see it.")
                .goblin(Quest.BENTNOZE, "Bring orange. We try that first.")
                .goblin(Quest.WARTFACE, "Orange might work.")
                .then(Quest::promiseArmour)
                .open()
        })
    }

    private fun trial(player: Player, stage: Int, general: Int, dialogue: DialogueQueueBuilder) {
        val (armour, colour, response) = when (stage) {
            Quest.NEED_ORANGE -> Triple(Dye.ORANGE_GOBLIN_MAIL, "orange", SHOW_ORANGE)
            Quest.NEED_BLUE -> Triple(Dye.BLUE_GOBLIN_MAIL, "blue", SHOW_BLUE)
            else -> Triple(Dye.GOBLIN_MAIL, "brown", SHOW_BROWN)
        }
        if (stage == Quest.NEED_ORANGE) dialogue.goblin(general, "Oh, you again.")
        if (!player.inventory.contains(armour)) {
            dialogue.goblin(general, "Did you bring us $colour armour?")
                .player(Expression.BORED, "Err, no.")
                .goblin(general, "Come back when you find some.")
            return
        }
        dialogue.player(Expression.QUIZZICAL, response).text(HAND_IN).onLastOpen {
            if (!Quest.handIn(it, stage, armour)) it.overlays.closeWindows()
        }
        when (stage) {
            Quest.NEED_ORANGE -> dialogue.goblin(Quest.WARTFACE, "Don't like orange much after all.")
                .goblin(Quest.BENTNOZE, "Makes my skin look funny.")
                .goblin(Quest.WARTFACE, "Bring us blue armour instead.")
            Quest.NEED_BLUE -> dialogue.goblin(Quest.WARTFACE, "Blue don't look right either.")
                .goblin(Quest.BENTNOZE, "Too dark, maybe.")
                .goblin(Quest.WARTFACE, "Let's see brown next.")
                .player(Expression.QUIZZICAL, *blueReply.toTypedArray())
            Quest.NEED_BROWN -> finish(dialogue)
        }
    }

    private fun DialogueQueueBuilder.goblin(npc: Int, vararg text: String): DialogueQueueBuilder {
        for (box in DialogueUtils.chatBoxes(*text)) {
            this.npc(npc, FIRST_GOBLIN_CHAT_SEQUENCE + box.size - 1, *box)
        }
        return this
    }

    private fun finish(dialogue: DialogueQueueBuilder) {
        dialogue.goblin(Quest.WARTFACE, "Now that's a good colour.")
            .goblin(Quest.BENTNOZE, "I'd wear that.")
            .goblin(Quest.WARTFACE, "Then we both go with brown.")
            .goblin(Quest.WARTFACE, "Thanks for settling it, human.")
            .then(Quest::finish)
    }
}
