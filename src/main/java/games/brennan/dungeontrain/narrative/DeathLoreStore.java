package games.brennan.dungeontrain.narrative;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.net.DeathNarrative;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Data-driven pool of death-screen narrative lines. Each entry targets one
 * narrative page ({@link #PAGE_FALL}, {@link #PAGE_DEEDS}, {@link #PAGE_GEAR},
 * {@link #PAGE_LIVES}, {@link #PAGE_PLATFORM}) and carries optional match
 * {@link Condition}s + a weight. At death the server rolls one weighted entry
 * per page, substitutes placeholders, and ships the chosen text to the client
 * inside {@code DeathStatsPacket} (see {@link DeathNarrative}).
 *
 * <p>Two tiers load into one additive pool. <b>Tier 1</b> — the vanilla
 * {@link ResourceManager} server-data channel: bundled files under
 * {@code data/dungeontrain/death_lore/*.json}, which a datapack can override or
 * extend (registered by {@link NarrativeDataLoaders}, so it honours {@code /reload}).
 * <b>Tier 2</b> — per-install filesystem overrides in
 * {@code config/dungeontrain/user/death_lore/*.json} (via {@link UserContentPaths}).
 * Both tiers <em>add</em> entries to the pool (dropping a file in only widens the
 * variety); a datapack override replaces the bundled file <em>of the same id</em>.</p>
 *
 * <p>File format — a JSON array of entries, or {@code {"entries":[...]}}:</p>
 * <pre>
 * [
 *   {
 *     "page": "fall",
 *     "weight": 1,
 *     "conditions": { "cause": ["minecraft:skeleton"], "min_carriage": 100, "min_deaths": 10 },
 *     "question": "What is the Dungeon Train?",
 *     "narration": "Carriage {carriage}. That is where the dark reached you.",
 *     "subline": "(lives page only)",
 *     "epitaph": "(platform page only)"
 *   }
 * ]
 * </pre>
 * Placeholders {@code {carriage} {friends} {books} {mobs} {met} {slain} {loot}
 * {hearts} {deaths}} are substituted as English <em>words</em> ("twenty-eight"),
 * and {@code {distance}} as a numeric metre count. Condition keys:
 * {@code cause, min_carriage, max_carriage, min_friends, max_friends, min_books,
 * max_books, min_mobs, min_slain, min_loot, max_loot, min_deaths, max_deaths} —
 * all optional; absent ones match anything.
 */
public final class DeathLoreStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Both the datapack directory (Tier 1) and the config sub-folder slug (Tier 2). */
    static final String SUBDIR = "death_lore";
    private static final String EXT = ".json";

    public static final String PAGE_FALL = "fall";
    public static final String PAGE_DEEDS = "deeds";
    public static final String PAGE_GEAR = "gear";
    public static final String PAGE_LIVES = "lives";
    public static final String PAGE_PLATFORM = "platform";

    private static final List<DeathLoreEntry> POOL = new ArrayList<>();

    private DeathLoreStore() {}

    /** Optional match conditions for an entry. {@link #ANY} matches every death. */
    public record Condition(List<ResourceLocation> causes, int minCarriage, int maxCarriage,
                            int minFriends, int minBooks, long minDeaths, int minMobs, long maxDeaths,
                            int maxFriends, int minSlain, int maxBooks, int minLoot, int maxLoot) {
        static final Condition ANY = new Condition(List.of(), 0, Integer.MAX_VALUE, 0, 0, 0L, 0, Long.MAX_VALUE,
                Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE);

        boolean matches(Context ctx) {
            if (!causes.isEmpty() && (ctx.cause() == null || !causes.contains(ctx.cause()))) return false;
            if (ctx.carriage() < minCarriage || ctx.carriage() > maxCarriage) return false;
            if (ctx.friends() < minFriends || ctx.friends() > maxFriends) return false;
            if (ctx.books() < minBooks || ctx.books() > maxBooks) return false;
            if (ctx.mobs() < minMobs) return false;
            if (ctx.slain() < minSlain) return false;
            if (ctx.loot() < minLoot || ctx.loot() > maxLoot) return false;
            if (ctx.deaths() < minDeaths || ctx.deaths() > maxDeaths) return false;
            return true;
        }
    }

    /** One pool entry: which page it fills, its weight, conditions, and text slots. */
    public record DeathLoreEntry(String page, int weight, Condition condition,
                                 String question, String narration, String subline, String epitaph) {}

    /**
     * Server-side death context — drives both {@link Condition} matching and
     * placeholder substitution. {@code cause} is the killer entity-type id, or
     * {@code null} for environmental deaths (fall, lava, void, …).
     * {@code hearts} is damage taken expressed in hearts (points / 2).
     */
    public record Context(ResourceLocation cause, int carriage, int friends, int books,
                          int mobs, int met, int slain, int hearts, double distance, long deaths, int loot) {}

    /**
     * Reload the pool from both tiers. <b>Tier 1</b> — the datapack channel via
     * {@code resourceManager} (bundled data + datapack overrides); <b>Tier 2</b> —
     * the {@code config/dungeontrain/user/death_lore/} filesystem overrides. Both
     * are additive. Called by the reload listener at world load / {@code /reload}
     * and by the {@code /dungeontrain narrative reload} command.
     */
    public static synchronized void load(ResourceManager resourceManager) {
        POOL.clear();
        // Tier 1 — datapack channel: bundled data + datapack overrides (override-resolved per id).
        Map<ResourceLocation, Resource> resources =
            resourceManager.listResources(SUBDIR, rl -> rl.getPath().endsWith(EXT));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation file = entry.getKey();
            // Overlay the host-locale variant on the English base when one is bundled/shipped
            // (see NarrativeContentLocale). Tier 2 (filesystem overrides, below) is untouched.
            ResourceLocation baseId = ResourceLocation.fromNamespaceAndPath(
                file.getNamespace(), file.getPath().substring(0, file.getPath().length() - EXT.length()));
            Resource source = NarrativeContentLocale.localized(resourceManager, baseId, SUBDIR).orElse(entry.getValue());
            try (InputStream in = source.open();
                 Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                parseInto(r, file.toString());
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] death_lore: failed reading '{}': {}", file, e.toString());
            }
        }
        // Tier 2 — per-install filesystem overrides (additive; outside the datapack tree).
        for (Path dir : UserContentPaths.searchDirs(SUBDIR)) {
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + EXT)) {
                for (Path file : stream) {
                    if (!Files.isRegularFile(file)) continue;
                    try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        parseInto(r, file.toString());
                    } catch (Exception e) {
                        LOGGER.error("[DungeonTrain] death_lore: failed reading '{}': {}", file, e.toString());
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] death_lore: couldn't scan {}: {}", dir, e.toString());
            }
        }
        LOGGER.info("[DungeonTrain] death_lore pool loaded — {} entries", POOL.size());
    }

    private static void parseInto(Reader reader, String source) {
        JsonElement root = JsonParser.parseReader(reader);
        JsonArray arr;
        if (root.isJsonArray()) {
            arr = root.getAsJsonArray();
        } else if (root.isJsonObject() && root.getAsJsonObject().has("entries")
                && root.getAsJsonObject().get("entries").isJsonArray()) {
            arr = root.getAsJsonObject().getAsJsonArray("entries");
        } else {
            LOGGER.warn("[DungeonTrain] death_lore: {} is neither an array nor {{entries:[...]}} — skipped", source);
            return;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            DeathLoreEntry entry = parseEntry(el.getAsJsonObject());
            if (entry != null) POOL.add(entry);
        }
    }

    private static DeathLoreEntry parseEntry(JsonObject o) {
        String page = str(o, "page", "");
        if (page.isEmpty()) return null;
        page = page.toLowerCase(Locale.ROOT);
        int weight = o.has("weight") && o.get("weight").isJsonPrimitive() ? Math.max(1, o.get("weight").getAsInt()) : 1;
        Condition cond = parseCondition(o.has("conditions") && o.get("conditions").isJsonObject()
                ? o.getAsJsonObject("conditions") : null);
        return new DeathLoreEntry(page, weight, cond,
                str(o, "question", ""), str(o, "narration", ""),
                str(o, "subline", ""), str(o, "epitaph", ""));
    }

    private static Condition parseCondition(JsonObject c) {
        if (c == null) return Condition.ANY;
        List<ResourceLocation> causes = new ArrayList<>();
        if (c.has("cause")) {
            JsonElement ce = c.get("cause");
            if (ce.isJsonArray()) {
                for (JsonElement e : ce.getAsJsonArray()) {
                    if (e.isJsonPrimitive()) {
                        ResourceLocation rl = ResourceLocation.tryParse(e.getAsString());
                        if (rl != null) causes.add(rl);
                    }
                }
            } else if (ce.isJsonPrimitive()) {
                ResourceLocation rl = ResourceLocation.tryParse(ce.getAsString());
                if (rl != null) causes.add(rl);
            }
        }
        int minC = intval(c, "min_carriage", 0);
        int maxC = intval(c, "max_carriage", Integer.MAX_VALUE);
        int minF = intval(c, "min_friends", 0);
        int minB = intval(c, "min_books", 0);
        int minM = intval(c, "min_mobs", 0);
        long minD = c.has("min_deaths") && c.get("min_deaths").isJsonPrimitive() ? c.get("min_deaths").getAsLong() : 0L;
        long maxD = c.has("max_deaths") && c.get("max_deaths").isJsonPrimitive() ? c.get("max_deaths").getAsLong() : Long.MAX_VALUE;
        int maxF = intval(c, "max_friends", Integer.MAX_VALUE);
        int minS = intval(c, "min_slain", 0);
        int maxB = intval(c, "max_books", Integer.MAX_VALUE);
        int minL = intval(c, "min_loot", 0);
        int maxL = intval(c, "max_loot", Integer.MAX_VALUE);
        return new Condition(List.copyOf(causes), minC, maxC, minF, minB, minD, minM, maxD, maxF, minS, maxB, minL, maxL);
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    private static int intval(JsonObject o, String key, int def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : def;
    }

    /** Roll one entry for {@code page} matching {@code ctx}, weighted. Null when none match. */
    private static synchronized DeathLoreEntry roll(String page, Context ctx, RandomSource random) {
        int total = 0;
        List<DeathLoreEntry> matches = new ArrayList<>();
        for (DeathLoreEntry e : POOL) {
            if (!e.page().equals(page)) continue;
            if (!e.condition().matches(ctx)) continue;
            matches.add(e);
            total += e.weight();
        }
        if (matches.isEmpty()) return null;
        int pick = random.nextInt(total);
        for (DeathLoreEntry e : matches) {
            pick -= e.weight();
            if (pick < 0) return e;
        }
        return matches.get(matches.size() - 1);
    }

    /** Build the full per-death narrative (all five pages), substituting placeholders. */
    public static DeathNarrative buildNarrative(Context ctx, RandomSource random) {
        DeathLoreEntry fall = roll(PAGE_FALL, ctx, random);
        DeathLoreEntry deeds = roll(PAGE_DEEDS, ctx, random);
        DeathLoreEntry gear = roll(PAGE_GEAR, ctx, random);
        DeathLoreEntry lives = roll(PAGE_LIVES, ctx, random);
        DeathLoreEntry platform = roll(PAGE_PLATFORM, ctx, random);
        return new DeathNarrative(
                sub(q(fall), ctx), sub(n(fall), ctx),
                sub(q(deeds), ctx), sub(n(deeds), ctx),
                sub(q(gear), ctx), sub(n(gear), ctx),
                sub(q(lives), ctx), sub(lives == null ? "" : lives.subline(), ctx), sub(n(lives), ctx),
                sub(q(platform), ctx), sub(n(platform), ctx), sub(platform == null ? "" : platform.epitaph(), ctx));
    }

    private static String q(DeathLoreEntry e) { return e == null ? "" : e.question(); }
    private static String n(DeathLoreEntry e) { return e == null ? "" : e.narration(); }

    private static String sub(String template, Context ctx) {
        if (template == null) return "";
        if (template.indexOf('{') < 0) return template;
        return template
                .replace("{carriage}", num(ctx.carriage()))
                .replace("{friends}", num(ctx.friends()))
                .replace("{books}", num(ctx.books()))
                .replace("{mobs}", num(ctx.mobs()))
                .replace("{met}", num(ctx.met()))
                .replace("{slain}", num(ctx.slain()))
                .replace("{loot}", num(ctx.loot()))
                .replace("{hearts}", num(ctx.hearts()))
                .replace("{deaths}", num(ctx.deaths()))
                .replace("{distance}", "" + String.format(Locale.ROOT, "%,.0f", ctx.distance()) + "");
    }

    /** Drop the entire pool (called on server stop). */
    public static synchronized void clear() {
        POOL.clear();
    }

    // ---- Number → English words (so narration reads "twenty-eight", not "28") ----

    private static final String[] ONES = {
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen"
    };
    private static final String[] TENS = {
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    };

    /**
     * Wrap a number-word in the sentinels (U+0001 … U+0002) the death screen
     * parses to colour the figure white, keeping the markers out of the
     * narration templates themselves.
     */
    private static String num(long v) {
        return "" + words(v) + "";
    }

    static String words(long n) {
        if (n < 0) return Long.toString(n);
        if (n < 20) return ONES[(int) n];
        if (n < 100) {
            long t = n / 10, r = n % 10;
            return r == 0 ? TENS[(int) t] : TENS[(int) t] + "-" + ONES[(int) r];
        }
        if (n < 1000) {
            long h = n / 100, r = n % 100;
            return r == 0 ? ONES[(int) h] + " hundred" : ONES[(int) h] + " hundred and " + words(r);
        }
        if (n < 1_000_000) {
            long k = n / 1000, r = n % 1000;
            return r == 0 ? words(k) + " thousand" : words(k) + " thousand " + words(r);
        }
        long m = n / 1_000_000, r = n % 1_000_000;
        return r == 0 ? words(m) + " million" : words(m) + " million " + words(r);
    }
}
