package io.luna.game.model.mob.bot.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.luna.game.model.mob.combat.damage.CombatDamageType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a bot has learned about the game by playing it: the monsters it has fought, what its own trades went for and
 * the tricks it has fallen for.
 * <p>
 * <b>Monsters</b> are held by {@link Kind}, the name and combat level a player tells them apart by. A bot remembers the
 * biggest hit a kind has dealt it, how it attacks, how many it has killed and how often it died to one. Kills build
 * {@link #experience(Kind) experience}, which fills in what the bot's intelligence left it guessing about how hard
 * the kind hits. A death builds {@link #respect(Kind) respect}: for a day or more of play the bot expects more from
 * that kind than it has seen, so it eats and prays sooner. See {@link #expectedMaxHit(Kind, int, double)}.
 * <p>
 * <b>Prices</b> are what the bot itself last paid and last got for an item in a trade with another player. They are
 * about the world, so they go by real time, and one older than {@link #PRICE_KEPT} is forgotten.
 * <p>
 * <b>Lessons</b> are tricks the bot fell for. Each time makes it {@link #wiseTo(Lesson) wiser} to that trick whoever
 * tries it next, and the lesson wears off over months of play.
 * <p>
 * The number of monsters and prices is limited by how intelligent the bot is. When a limit is reached, the monster it
 * remembers least or the oldest price makes room.
 *
 * @author TheLining
 */
public final class BotKnowHowMemory implements BotMemoryPart {

    /**
     * A kind of monster, as a player tells them apart.
     *
     * @param name The monster's name.
     * @param level Its combat level.
     */
    public record Kind(String name, int level) {

        public Kind {
            name = name == null ? "" : name;
            level = Math.max(0, level);
        }

        @Override
        public String toString() {
            return name + " (level-" + level + ")";
        }
    }

    /**
     * A trick a bot can fall for and learn from.
     */
    public enum Lesson {

        /**
         * Handing items over in a trade for a promise.
         */
        TRADE_SCAM(0.5, hours(720)),

        /**
         * Following someone into the Wilderness to be killed.
         */
        WILDERNESS_LURE(0.5, hours(720)),

        /**
         * Taking Zamorak's wine from under its guardians. Once burned, never forgotten.
         */
        WINE_THEFT(1.0, 0);

        /**
         * The share of what the bot has left to learn that falling for the trick once teaches it.
         */
        private final double share;

        /**
         * The time online over which the lesson halves for a bot of average intelligence, or {@code 0} if it never
         * wears off.
         */
        private final long halfLife;

        /**
         * The name the lesson is saved under.
         */
        private final String key = name().toLowerCase(Locale.ROOT);

        Lesson(double share, long halfLife) {
            this.share = share;
            this.halfLife = halfLife;
        }
    }

    /**
     * What a bot can say about a kind of monster.
     *
     * @param kind The kind.
     * @param biggestHit The biggest hit one has dealt the bot, or {@code 0} if none has hurt it.
     * @param style How the kind usually attacks, or {@code null} if the bot has not seen enough to tell.
     * @param kills How many the bot has killed.
     * @param deaths How many times one has killed the bot.
     */
    public record Seen(Kind kind, int biggestHit, CombatDamageType style, int kills, int deaths) {
    }

    /**
     * The latest price a bot traded an item at.
     *
     * @param price The price of one, in coins.
     * @param bought Whether the bot paid it, rather than was paid it.
     * @param age How long ago the trade was, in milliseconds of real time.
     */
    public record Trade(long price, boolean bought, long age) {
    }

    /**
     * One kind of monster the bot has fought.
     */
    private static final class Monster {

        private final Kind kind;

        /**
         * The biggest hit one has dealt the bot.
         */
        private int biggestHit;

        /**
         * How many attacks of each style the bot has seen from the kind, by {@link CombatDamageType#ordinal()}.
         */
        private final int[] attacks = new int[STYLES.length];

        private int kills;

        private int deaths;

        /**
         * The experience held at {@link #experiencedAt}.
         */
        private double experience;

        /**
         * When {@link #experience} was last set, in time online.
         */
        private long experiencedAt;

        /**
         * The respect held at {@link #respectedAt}.
         */
        private double respect;

        /**
         * When {@link #respect} was last set, in time online.
         */
        private long respectedAt;

        /**
         * When the bot last fought one, in time online.
         */
        private long foughtAt;

        private Monster(Kind kind) {
            this.kind = kind;
        }
    }

    /**
     * What the bot's own trades of one item went for.
     */
    private static final class Price {

        private final int item;

        /**
         * The price of one the bot last paid, or {@code 0} if it has not bought any.
         */
        private long paid;

        /**
         * When it paid {@link #paid}, in real time.
         */
        private long paidAt;

        /**
         * The price of one the bot last got, or {@code 0} if it has not sold any.
         */
        private long got;

        /**
         * When it got {@link #got}, in real time.
         */
        private long gotAt;

        private Price(int item) {
            this.item = item;
        }

        private long latest() {
            return Math.max(paid > 0 ? paidAt : Long.MIN_VALUE, got > 0 ? gotAt : Long.MIN_VALUE);
        }
    }

    private static final CombatDamageType[] STYLES = CombatDamageType.values();

    private static final Lesson[] LESSONS = Lesson.values();

    /**
     * The time online over which experience with a monster halves, for a bot of average intelligence.
     */
    public static final long EXPERIENCE_HALF_LIFE = hours(720);

    /**
     * How many kills take a bot half of the way to knowing all a kind of monster can do.
     */
    public static final int KILLS_TO_KNOW = 10;

    /**
     * The share of what a bot has left to learn about a kind that one kill teaches it.
     */
    private static final double KILL_SHARE = 1.0 - Math.pow(0.5, 1.0 / KILLS_TO_KNOW);

    /**
     * The share of what a bot has left to learn about a kind that dying to one teaches it.
     */
    private static final double DEATH_SHARE = 0.5;

    /**
     * The time online over which respect for a monster halves, for a bot of average confidence.
     */
    public static final long RESPECT_HALF_LIFE = hours(48);

    /**
     * How much more than it knows of a bot expects from a monster it fully respects, as a share.
     */
    public static final double MOST_RESPECT = 0.5;

    /**
     * How many attacks a bot must have seen from a kind before it knows how the kind usually attacks.
     */
    public static final int STYLE_KNOWN_AFTER = 3;

    /**
     * How many attacks from a kind are counted before the older ones start to count for less.
     */
    private static final int MOST_ATTACKS = 64;

    /**
     * The real time a traded price is kept for.
     */
    public static final long PRICE_KEPT = hours(720);

    /**
     * A lesson at least this strong is one the bot {@link #knows(Lesson) knows}.
     */
    public static final double KNOWN_LESSON = 0.5;

    /**
     * A strength weaker than this is gone.
     */
    private static final double FORGOTTEN = 0.02;

    /**
     * Taken off before rounding up, so a product that is a whole number is not pushed to the next one.
     */
    private static final double ROUNDING = 1e-9;

    /**
     * The most a count or a hit can be.
     */
    private static final int MOST_COUNT = 9_999;

    /**
     * How many kinds of monster the least intelligent bot keeps track of.
     */
    private static final int FEWEST_MONSTERS = 24;

    /**
     * How many more kinds the most intelligent bot keeps track of.
     */
    private static final int EXTRA_MONSTERS = 24;

    /**
     * How many prices the least intelligent bot keeps track of.
     */
    private static final int FEWEST_PRICES = 16;

    /**
     * How many more prices the most intelligent bot keeps track of.
     */
    private static final int EXTRA_PRICES = 16;

    /**
     * The memory this part belongs to.
     */
    private final BotMemory memory;

    /**
     * Every kind of monster the bot has fought.
     */
    private final Map<Kind, Monster> monsters = new LinkedHashMap<>();

    /**
     * What the bot's own trades went for, by item id.
     */
    private final Map<Integer, Price> prices = new LinkedHashMap<>();

    /**
     * How wise the bot was to each lesson at its {@link #learnedAt} time, by {@link Lesson#ordinal()}.
     */
    private final double[] lessons = new double[LESSONS.length];

    /**
     * When each lesson was last set, in time online.
     */
    private final long[] learnedAt = new long[LESSONS.length];

    BotKnowHowMemory(BotMemory memory) {
        this.memory = memory;
    }

    /**
     * Records an attack a monster made on the bot. A miss still shows how the monster attacks.
     *
     * @param kind The kind of monster.
     * @param style How it attacked, or {@code null} if that could not be told.
     * @param amount The damage dealt, or {@code 0} for a miss.
     */
    public void hit(Kind kind, CombatDamageType style, int amount) {
        if (amount < 0) {
            return;
        }
        Monster monster = monster(kind);
        if (monster == null) {
            return;
        }
        monster.foughtAt = memory.now();
        monster.biggestHit = Math.max(monster.biggestHit, Math.min(amount, MOST_COUNT));
        if (style == null) {
            return;
        }
        if (attacks(monster) >= MOST_ATTACKS) {
            for (int index = 0; index < monster.attacks.length; index++) {
                monster.attacks[index] /= 2;
            }
        }
        monster.attacks[style.ordinal()]++;
    }

    /**
     * Records that the bot killed a monster.
     *
     * @param kind The kind of monster.
     */
    public void killed(Kind kind) {
        Monster monster = monster(kind);
        if (monster == null) {
            return;
        }
        long now = memory.now();
        monster.kills = Math.min(MOST_COUNT, monster.kills + 1);
        learn(monster, KILL_SHARE, now);
    }

    /**
     * Records that a monster killed the bot. The less confident the bot, the more respect the death leaves.
     *
     * @param kind The kind of monster.
     */
    public void killedBy(Kind kind) {
        Monster monster = monster(kind);
        if (monster == null) {
            return;
        }
        long now = memory.now();
        monster.deaths = Math.min(MOST_COUNT, monster.deaths + 1);
        learn(monster, DEATH_SHARE, now);
        double respect = respect(monster, now);
        double share = Math.clamp(0.5 * (1.5 - personal(memory.personality().getConfidence())), 0.25, 0.75);
        monster.respect = clamp(respect + (1.0 - respect) * share);
        monster.respectedAt = now;
    }

    /**
     * @param kind The kind of monster.
     * @return The biggest hit one has dealt the bot, or {@code 0} if none has hurt it.
     */
    public int biggestHit(Kind kind) {
        Monster monster = monsters.get(kind);
        return monster == null ? 0 : monster.biggestHit;
    }

    /**
     * Estimates the most a monster can hit the bot for.
     * <p>
     * Before the bot has fought the kind it knows a share of the real maximum, set by its intelligence. Experience
     * fills in the rest, the biggest hit it has taken wins over both, and respect for a kind that has killed it adds
     * up to half again on top.
     *
     * @param kind The kind of monster.
     * @param maximumHit The most the monster can really hit.
     * @param knowledge How much of that the bot knows about before it has fought one, from {@code 0.0} to
     * {@code 1.0}.
     * @return The expected maximum hit, at least {@code 1}.
     */
    public int expectedMaxHit(Kind kind, int maximumHit, double knowledge) {
        double knows = personal(knowledge);
        int maximum = Math.max(0, maximumHit);
        Monster monster = monsters.get(kind);
        if (monster == null) {
            return Math.max(1, (int) Math.round(maximum * knows));
        }
        long now = memory.now();
        knows += (1.0 - knows) * experience(monster, now);
        int expected = Math.max(monster.biggestHit, (int) Math.round(maximum * knows));
        double respect = respect(monster, now);
        if (respect >= FORGOTTEN) {
            expected = (int) Math.ceil(expected * (1.0 + MOST_RESPECT * respect) - ROUNDING);
        }
        return Math.max(1, expected);
    }

    /**
     * @param kind The kind of monster.
     * @return How the kind usually attacks, or {@code null} if the bot has not seen enough of its attacks to tell.
     */
    public CombatDamageType usualStyle(Kind kind) {
        Monster monster = monsters.get(kind);
        return monster == null ? null : usualStyle(monster);
    }

    /**
     * @param kind The kind of monster.
     * @return How well the bot knows the kind from fighting it, from {@code 0.0} to {@code 1.0}.
     */
    public double experience(Kind kind) {
        Monster monster = monsters.get(kind);
        return monster == null ? 0.0 : experience(monster, memory.now());
    }

    /**
     * @param kind The kind of monster.
     * @return How wary the bot is of the kind for having died to it, from {@code 0.0} to {@code 1.0}.
     */
    public double respect(Kind kind) {
        Monster monster = monsters.get(kind);
        return monster == null ? 0.0 : respect(monster, memory.now());
    }

    /**
     * @return Every kind of monster the bot has fought, the one it remembers best first.
     */
    public List<Seen> monsters() {
        long now = memory.now();
        List<Seen> seen = new ArrayList<>(monsters.size());
        monsters.values().stream()
                .sorted(Comparator.comparingDouble((Monster monster) -> weight(monster, now)).reversed())
                .forEach(monster -> seen.add(new Seen(monster.kind, monster.biggestHit, usualStyle(monster),
                        monster.kills, monster.deaths)));
        return seen;
    }

    /**
     * Records the price the bot traded an item at with another player.
     *
     * @param item The item's id.
     * @param price The price of one, in coins.
     * @param bought Whether the bot paid it, rather than was paid it.
     */
    public void traded(int item, long price, boolean bought) {
        if (item <= 0 || price <= 0) {
            return;
        }
        long realTime = memory.realTime();
        Price known = prices.get(item);
        if (known == null) {
            prices.values().removeIf(old -> stale(old, realTime));
            if (prices.size() >= priceCapacity()) {
                prices.remove(oldest().item);
            }
            known = new Price(item);
            prices.put(item, known);
        }
        if (bought) {
            known.paid = price;
            known.paidAt = realTime;
        } else {
            known.got = price;
            known.gotAt = realTime;
        }
    }

    /**
     * @param item The item's id.
     * @return The latest price the bot traded the item at, or {@code null} if it has none it still remembers.
     */
    public Trade price(int item) {
        Price known = prices.get(item);
        long realTime = memory.realTime();
        if (known == null || stale(known, realTime)) {
            return null;
        }
        boolean bought = known.paid > 0 && (known.got <= 0 || known.paidAt >= known.gotAt);
        return new Trade(bought ? known.paid : known.got, bought,
                Math.max(0, realTime - (bought ? known.paidAt : known.gotAt)));
    }

    /**
     * Records that the bot fell for a trick.
     *
     * @param lesson The trick.
     */
    public void learn(Lesson lesson) {
        double wise = wiseTo(lesson);
        lessons[lesson.ordinal()] = clamp(wise + (1.0 - wise) * lesson.share);
        learnedAt[lesson.ordinal()] = memory.now();
    }

    /**
     * @param lesson The trick.
     * @return How wise the bot is to it, from {@code 0.0} for a bot that never fell for it to {@code 1.0}.
     * Intelligent bots keep a lesson longer.
     */
    public double wiseTo(Lesson lesson) {
        double wise = lessons[lesson.ordinal()];
        if (wise <= 0.0 || lesson.halfLife <= 0) {
            return Math.max(0.0, wise);
        }
        return wise * Math.pow(0.5, Math.max(0, memory.now() - learnedAt[lesson.ordinal()]) / lasting(lesson.halfLife));
    }

    /**
     * @param lesson The trick.
     * @return Whether the bot has learned not to fall for it.
     */
    public boolean knows(Lesson lesson) {
        return wiseTo(lesson) >= KNOWN_LESSON;
    }

    /**
     * @return How many kinds of monster the bot can keep track of, by how intelligent it is.
     */
    public int monsterCapacity() {
        return FEWEST_MONSTERS + (int) Math.round(EXTRA_MONSTERS * personal(memory.personality().getIntelligence()));
    }

    /**
     * @return How many prices the bot can keep track of, by how intelligent it is.
     */
    public int priceCapacity() {
        return FEWEST_PRICES + (int) Math.round(EXTRA_PRICES * personal(memory.personality().getIntelligence()));
    }

    /**
     * @return How many kinds of monster the bot remembers.
     */
    public int monsterCount() {
        return monsters.size();
    }

    /**
     * @return How many prices the bot remembers.
     */
    public int priceCount() {
        return prices.size();
    }

    @Override
    public String key() {
        return "knowhow";
    }

    @Override
    public JsonElement save() {
        long now = memory.now();
        long realTime = memory.realTime();
        JsonArray fought = new JsonArray();
        for (Monster monster : monsters.values()) {
            if (recency(monster, now) < FORGOTTEN) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", monster.kind.name());
            entry.addProperty("level", monster.kind.level());
            if (monster.biggestHit > 0) {
                entry.addProperty("hit", monster.biggestHit);
            }
            for (CombatDamageType style : STYLES) {
                if (monster.attacks[style.ordinal()] > 0) {
                    entry.addProperty(key(style), monster.attacks[style.ordinal()]);
                }
            }
            if (monster.kills > 0) {
                entry.addProperty("kills", monster.kills);
            }
            if (monster.deaths > 0) {
                entry.addProperty("deaths", monster.deaths);
            }
            double experience = experience(monster, now);
            if (experience >= FORGOTTEN) {
                entry.addProperty("experience", rounded(experience));
            }
            double respect = respect(monster, now);
            if (respect >= FORGOTTEN) {
                entry.addProperty("respect", rounded(respect));
            }
            entry.addProperty("idle", Math.max(0, now - monster.foughtAt));
            fought.add(entry);
        }
        JsonArray traded = new JsonArray();
        for (Price price : prices.values()) {
            if (stale(price, realTime)) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("item", price.item);
            if (price.paid > 0) {
                entry.addProperty("paid", price.paid);
                entry.addProperty("paid_at_real", price.paidAt);
            }
            if (price.got > 0) {
                entry.addProperty("got", price.got);
                entry.addProperty("got_at_real", price.gotAt);
            }
            traded.add(entry);
        }
        JsonObject learned = new JsonObject();
        for (Lesson lesson : LESSONS) {
            double wise = wiseTo(lesson);
            if (wise >= FORGOTTEN) {
                learned.addProperty(lesson.key, rounded(wise));
            }
        }
        JsonObject data = new JsonObject();
        if (!fought.isEmpty() || !learned.entrySet().isEmpty()) {
            // Fading has no memory of its own, so the faded strengths and the time they were taken say everything.
            data.addProperty("as_of", now);
        }
        if (!fought.isEmpty()) {
            data.add("monsters", fought);
        }
        if (!traded.isEmpty()) {
            data.add("prices", traded);
        }
        if (!learned.entrySet().isEmpty()) {
            data.add("lessons", learned);
        }
        return data;
    }

    @Override
    public void load(JsonElement data) {
        clear();
        if (data == null || !data.isJsonObject()) {
            return;
        }
        JsonObject root = data.getAsJsonObject();
        long now = memory.now();
        long realTime = memory.realTime();
        long asOf = (long) number(root, "as_of", now);
        JsonElement fought = root.get("monsters");
        if (fought != null && fought.isJsonArray()) {
            for (JsonElement element : fought.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                String name = text(entry, "name");
                if (name == null || name.isEmpty()) {
                    continue;
                }
                Monster monster = new Monster(new Kind(name, count(entry, "level")));
                monster.biggestHit = count(entry, "hit");
                for (CombatDamageType style : STYLES) {
                    monster.attacks[style.ordinal()] = Math.min(MOST_ATTACKS, count(entry, key(style)));
                }
                monster.kills = count(entry, "kills");
                monster.deaths = count(entry, "deaths");
                monster.experience = clamp(number(entry, "experience", 0.0));
                monster.experiencedAt = asOf;
                monster.respect = clamp(number(entry, "respect", 0.0));
                monster.respectedAt = asOf;
                monster.foughtAt = asOf - Math.max(0L, (long) number(entry, "idle", 0.0));
                if (recency(monster, now) >= FORGOTTEN) {
                    monsters.put(monster.kind, monster);
                }
            }
        }
        JsonElement traded = root.get("prices");
        if (traded != null && traded.isJsonArray()) {
            for (JsonElement element : traded.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                int item = (int) Math.max(0.0, Math.min(Integer.MAX_VALUE, number(entry, "item", 0.0)));
                if (item <= 0) {
                    continue;
                }
                Price price = new Price(item);
                price.paid = Math.max(0L, (long) number(entry, "paid", 0.0));
                price.paidAt = (long) number(entry, "paid_at_real", realTime);
                price.got = Math.max(0L, (long) number(entry, "got", 0.0));
                price.gotAt = (long) number(entry, "got_at_real", realTime);
                if ((price.paid > 0 || price.got > 0) && !stale(price, realTime)) {
                    prices.put(item, price);
                }
            }
        }
        JsonElement learned = root.get("lessons");
        if (learned != null && learned.isJsonObject()) {
            for (Lesson lesson : LESSONS) {
                double wise = clamp(number(learned.getAsJsonObject(), lesson.key, 0.0));
                lessons[lesson.ordinal()] = wise >= FORGOTTEN ? wise : 0.0;
                learnedAt[lesson.ordinal()] = asOf;
            }
        }
        int capacity = monsterCapacity();
        while (monsters.size() > capacity) {
            monsters.remove(weakest(now).kind);
        }
        capacity = priceCapacity();
        while (prices.size() > capacity) {
            prices.remove(oldest().item);
        }
    }

    @Override
    public void clear() {
        monsters.clear();
        prices.clear();
        for (Lesson lesson : LESSONS) {
            lessons[lesson.ordinal()] = 0.0;
            learnedAt[lesson.ordinal()] = 0L;
        }
    }

    @Override
    public List<String> describe() {
        long now = memory.now();
        long realTime = memory.realTime();
        List<String> lines = new ArrayList<>();
        lines.add("Monsters: " + monsters.size() + " of " + monsterCapacity());
        monsters.values().stream()
                .sorted(Comparator.comparingDouble((Monster monster) -> weight(monster, now)).reversed())
                .forEach(monster -> {
                    StringBuilder line = new StringBuilder(monster.kind.toString()).append(": ");
                    line.append(monster.biggestHit > 0 ? "biggest hit " + monster.biggestHit : "never hurt by one");
                    CombatDamageType style = usualStyle(monster);
                    if (style != null) {
                        line.append(", uses ").append(key(style));
                    }
                    line.append(", kills ").append(monster.kills);
                    if (monster.deaths > 0) {
                        line.append(", deaths ").append(monster.deaths);
                    }
                    line.append(", experience ").append(rounded(experience(monster, now)));
                    double respect = respect(monster, now);
                    if (respect >= FORGOTTEN) {
                        line.append(", respect ").append(rounded(respect));
                    }
                    lines.add(line.toString());
                });
        lines.add("Prices: " + prices.size() + " of " + priceCapacity());
        for (Price price : prices.values()) {
            if (stale(price, realTime)) {
                continue;
            }
            StringBuilder line = new StringBuilder("Item ").append(price.item).append(':');
            if (price.paid > 0) {
                line.append(" paid ").append(price.paid).append(' ')
                        .append(Math.max(0, realTime - price.paidAt) / 3_600_000).append(" h ago");
            }
            if (price.got > 0) {
                line.append(price.paid > 0 ? "," : "").append(" got ").append(price.got).append(' ')
                        .append(Math.max(0, realTime - price.gotAt) / 3_600_000).append(" h ago");
            }
            lines.add(line.toString());
        }
        for (Lesson lesson : LESSONS) {
            double wise = wiseTo(lesson);
            if (wise >= FORGOTTEN) {
                lines.add("Lesson " + lesson.key + ": " + rounded(wise) + (wise >= KNOWN_LESSON ? ", known" : ""));
            }
        }
        return lines;
    }

    /**
     * Finds a kind of monster, making room for it first if it is new.
     *
     * @param kind The kind.
     * @return The monster, or {@code null} if there is no name to hold it under.
     */
    private Monster monster(Kind kind) {
        if (kind == null || kind.name().isEmpty()) {
            return null;
        }
        Monster known = monsters.get(kind);
        if (known != null) {
            return known;
        }
        long now = memory.now();
        monsters.values().removeIf(monster -> recency(monster, now) < FORGOTTEN);
        if (monsters.size() >= monsterCapacity()) {
            monsters.remove(weakest(now).kind);
        }
        Monster monster = new Monster(kind);
        monster.foughtAt = now;
        monsters.put(kind, monster);
        return monster;
    }

    /**
     * Moves a monster's experience a share of the way to knowing it fully, and marks it as just fought.
     */
    private void learn(Monster monster, double share, long now) {
        double experience = experience(monster, now);
        monster.experience = clamp(experience + (1.0 - experience) * share);
        monster.experiencedAt = now;
        monster.foughtAt = now;
    }

    /**
     * @return The kind of monster the bot remembers least.
     */
    private Monster weakest(long now) {
        Monster weakest = null;
        double least = Double.MAX_VALUE;
        for (Monster monster : monsters.values()) {
            double weight = weight(monster, now);
            if (weight < least) {
                least = weight;
                weakest = monster;
            }
        }
        return weakest;
    }

    /**
     * @return The price the bot traded at longest ago.
     */
    private Price oldest() {
        Price oldest = null;
        for (Price price : prices.values()) {
            if (oldest == null || price.latest() < oldest.latest()) {
                oldest = price;
            }
        }
        return oldest;
    }

    /**
     * @return How much a monster is worth keeping: one fought lately, known well or respected outweighs one met once
     * long ago.
     */
    private double weight(Monster monster, long now) {
        return (1.0 + experience(monster, now) + respect(monster, now)) * recency(monster, now);
    }

    /**
     * @return How fresh the bot's last fight with a monster is, halving once for every half-life since.
     */
    private double recency(Monster monster, long now) {
        return Math.pow(0.5, Math.max(0, now - monster.foughtAt) / lasting(EXPERIENCE_HALF_LIFE));
    }

    /**
     * @return A monster's experience after halving once for every half-life since it was set.
     */
    private double experience(Monster monster, long now) {
        if (monster.experience <= 0.0) {
            return 0.0;
        }
        return monster.experience *
                Math.pow(0.5, Math.max(0, now - monster.experiencedAt) / lasting(EXPERIENCE_HALF_LIFE));
    }

    /**
     * @return A monster's respect after halving once for every half-life since it was set. Confident bots let go
     * sooner: from one day of play for the most confident to three for the least.
     */
    private double respect(Monster monster, long now) {
        if (monster.respect <= 0.0) {
            return 0.0;
        }
        double halfLife = RESPECT_HALF_LIFE * (1.5 - personal(memory.personality().getConfidence()));
        return monster.respect * Math.pow(0.5, Math.max(0, now - monster.respectedAt) / halfLife);
    }

    /**
     * @return A half-life stretched for an intelligent bot and shortened for a dumb one.
     */
    private double lasting(long halfLife) {
        return halfLife * (0.6 + 0.8 * personal(memory.personality().getIntelligence()));
    }

    private static int attacks(Monster monster) {
        int attacks = 0;
        for (int count : monster.attacks) {
            attacks += count;
        }
        return attacks;
    }

    private static CombatDamageType usualStyle(Monster monster) {
        if (attacks(monster) < STYLE_KNOWN_AFTER) {
            return null;
        }
        CombatDamageType usual = STYLES[0];
        for (CombatDamageType style : STYLES) {
            if (monster.attacks[style.ordinal()] > monster.attacks[usual.ordinal()]) {
                usual = style;
            }
        }
        return usual;
    }

    private static boolean stale(Price price, long realTime) {
        return realTime - price.latest() > PRICE_KEPT;
    }

    private static String key(CombatDamageType style) {
        return style.name().toLowerCase(Locale.ROOT);
    }

    /**
     * @return A personality trait from {@code 0.0} to {@code 1.0}, or the middle if it is not a number.
     */
    private static double personal(double trait) {
        return Double.isFinite(trait) ? clamp(trait) : 0.5;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double rounded(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }

    private static long hours(long hours) {
        return hours * 3_600_000L;
    }

    private static int count(JsonObject entry, String key) {
        return (int) Math.max(0.0, Math.min(MOST_COUNT, number(entry, key, 0.0)));
    }

    private static double number(JsonObject entry, String key, double otherwise) {
        JsonElement value = entry.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return otherwise;
        }
        double number = value.getAsDouble();
        return Double.isFinite(number) ? number : otherwise;
    }

    private static String text(JsonObject entry, String key) {
        JsonElement value = entry.get(key);
        return value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() ? null :
                value.getAsString();
    }
}
