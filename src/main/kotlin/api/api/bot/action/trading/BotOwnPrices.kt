package api.bot.action.trading

import io.luna.game.model.mob.bot.Bot

/**
 * What a bot's own trades with other players went for, kept in its know-how.
 *
 * Only a trade that states a price is remembered: coins alone on one side and a single kind of item on the other.
 * A gift, a swap or a mixed basket says nothing about what one item is worth. The prices are for the bot to speak
 * from; what it bids and asks still comes from the guide prices.
 *
 * @author TheLining
 */
object BotOwnPrices {

    /**
     * The coins item id.
     */
    private const val COINS = 995

    /**
     * A trade that states a price.
     *
     * @property item The item traded, by its unnoted id.
     * @property price The coins paid for each one.
     * @property bought Whether the bot was the one paying.
     */
    internal data class Deal(val item: Int, val price: Long, val bought: Boolean)

    /**
     * Reads the price a completed trade states.
     *
     * @param given What the bot handed over, as amounts by unnoted item id.
     * @param received What the bot was handed, in the same form.
     * @return The deal, or `null` if the trade doesn't state the price of one item.
     */
    internal fun read(given: Map<Int, Int>, received: Map<Int, Int>): Deal? {
        val gave = given.filterValues { it > 0 }
        val got = received.filterValues { it > 0 }
        if (gave.size != 1 || got.size != 1) {
            return null
        }
        val bought = COINS in gave
        if (bought == (COINS in got)) {
            return null
        }
        val (item, amount) = (if (bought) got else gave).entries.first()
        val coins = (if (bought) gave else got).getValue(COINS).toLong()
        val price = coins / amount
        return if (price < 1) null else Deal(item, price, bought)
    }

    /**
     * Remembers the price a completed trade states, if it states one.
     *
     * @param bot The bot that traded.
     * @param given What it handed over, as amounts by unnoted item id.
     * @param received What it was handed, in the same form.
     */
    fun remember(bot: Bot, given: Map<Int, Int>, received: Map<Int, Int>) {
        val deal = read(given, received) ?: return
        bot.memory.knowHow.traded(deal.item, deal.price, deal.bought)
    }
}
