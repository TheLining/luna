package game.skill.crafting.dyeing

/**
 * The ordinary primary dye recipes offered by Aggie in Draynor Village.
 *
 * @property colour The dye's colour.
 * @property dye The dye's item id.
 * @property ingredient The item Aggie uses to make it.
 * @property amount The number of ingredients she needs.
 * @property ingredientName The ingredient name used in messages.
 * @property recipe How Aggie describes the ingredients and price.
 * @author lare96
 */
enum class AggieDye(val colour: String, val dye: Int, val ingredient: Int, val amount: Int,
                    val ingredientName: String, vararg val recipe: String) {
    RED("red", Dye.RED.id, 1951, 3, "berries", "3 lots of redberries and 5 coins to you."),
    YELLOW("yellow", Dye.YELLOW.id, 1957, 2, "onions",
        "Yellow is a strange colour to get, comes from onion",
        "skins. I need 2 onions and 5 coins to make yellow dye."),
    BLUE("blue", Dye.BLUE.id, 1793, 2, "woad leaves", "2 woad leaves and 5 coins to you.");

    val request = "Okay, make me some $colour dye please."

    companion object {
        const val DYE_PRICE = 5
    }
}
