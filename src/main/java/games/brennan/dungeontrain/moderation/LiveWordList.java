package games.brennan.dungeontrain.moderation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The words the Live Eye text filter looks for, and the strike ladder it explains to players.
 * Modelled on {@link games.brennan.dungeontrain.cheat.CheatModList}: two sources merged, read lock-free.
 *
 * <ul>
 *   <li><b>Baked</b> — {@code assets/dungeontrain/moderation/live_words.json} in the jar: a small,
 *       hand-curated floor so the filter works offline and on first launch.</li>
 *   <li><b>Relay</b> — {@code GET /live/moderation/words} (fetched once per session by
 *       {@link games.brennan.dungeontrain.net.relay.LiveWordListFetcher}), cached to
 *       {@code config/dungeontrain-live-words.json} (atomic tmp-then-rename) so an offline boot keeps the
 *       last-known overlay. The relay carries the tunable bulk and never ships in the jar.</li>
 * </ul>
 *
 * <p>{@link #effective()} is the compiled union, rebuilt only when a source changes — a check never pays
 * for compilation. The payload shape is shared by the resource, the cache and the relay:
 * {@code {version, hard[], word[], cjk[], phrases[], allow[], ladder{removeAtStrike, banAfterRemovals,
 * banDays[], strikeDecayDays}}}. Anything malformed is dropped at the boundary, never thrown.</p>
 */
public final class LiveWordList {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String RESOURCE = "/assets/dungeontrain/moderation/live_words.json";
    static final String FILE_NAME = "dungeontrain-live-words.json";
    static final int MAX_TERMS = 2000;

    /** The strike ladder as the player hears it; the relay's answer is authoritative, these are the words to say. */
    public record Ladder(int removeAtStrike, int banAfterRemovals, List<Integer> banDays, int strikeDecayDays) {
        public static final Ladder DEFAULT = new Ladder(4, 3, List.of(7, 30, 0), 30);

        /** How many warnings a player gets before the Live Eye burns. */
        public int warningsBeforeRemoval() {
            return Math.max(1, removeAtStrike - 1);
        }
    }

    /** One parsed list body. */
    public record Payload(int version, List<String> hard, List<String> word, List<String> cjk,
                          List<String> phrases, List<String> allow, Ladder ladder) {
        public static final Payload EMPTY = new Payload(0, List.of(), List.of(), List.of(), List.of(), List.of(), null);

        boolean hasTerms() {
            return !hard.isEmpty() || !word.isEmpty() || !cjk.isEmpty() || !phrases.isEmpty();
        }
    }

    private static volatile Payload baked;
    private static volatile Payload relay = Payload.EMPTY;
    private static volatile boolean loaded;
    private static volatile CompiledWords compiled;

    private LiveWordList() {}

    /** The compiled union of baked and relay lists. Cheap after the first call. */
    public static CompiledWords effective() {
        CompiledWords c = compiled;
        if (c != null) return c;
        synchronized (LiveWordList.class) {
            if (compiled == null) compiled = compile(baked(), relayLoaded());
            return compiled;
        }
    }

    /** The strike ladder: the relay's when it sent one, the baked one otherwise. */
    public static Ladder ladder() {
        Payload r = relayLoaded();
        if (r.ladder() != null) return r.ladder();
        Payload b = baked();
        return b.ladder() != null ? b.ladder() : Ladder.DEFAULT;
    }

    /** Accept a fresh relay body: swap the overlay, recompile, persist. No-throw. */
    public static synchronized void accept(Payload payload) {
        if (payload == null) return;
        loaded = true;
        relay = payload;
        compiled = null;
        saveDiskCache(payload);
    }

    static Payload baked() {
        Payload b = baked;
        if (b != null) return b;
        synchronized (LiveWordList.class) {
            if (baked == null) baked = readBaked();
            return baked;
        }
    }

    private static Payload readBaked() {
        try (InputStream in = LiveWordList.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("[DungeonTrain] live word list: baked resource missing — relay list only");
                return Payload.EMPTY;
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] live word list: baked resource unreadable: {}", e.toString());
            return Payload.EMPTY;
        }
    }

    private static Payload relayLoaded() {
        loadDiskCacheOnce();
        return relay;
    }

    static synchronized void loadDiskCacheOnce() {
        if (loaded) return;
        loaded = true;
        Path file = defaultFile();
        if (file == null || !Files.exists(file)) return;
        try {
            relay = parse(Files.readString(file, StandardCharsets.UTF_8));
            compiled = null;
            LOGGER.debug("[DungeonTrain] live word list: loaded cached overlay from {}", file);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] live word list: could not read {}: {}", file, e.toString());
        }
    }

    static CompiledWords compile(Payload a, Payload b) {
        return new CompiledWords(
            concat(a.word(), b.word()), concat(a.hard(), b.hard()), concat(a.cjk(), b.cjk()),
            concat(a.phrases(), b.phrases()), concat(a.allow(), b.allow()));
    }

    private static List<String> concat(List<String> x, List<String> y) {
        List<String> out = new ArrayList<>(x.size() + y.size());
        out.addAll(x);
        out.addAll(y);
        return out;
    }

    /** Parse a list body. Defensive: a malformed body yields {@link Payload#EMPTY}, never an exception. */
    public static Payload parse(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return Payload.EMPTY;
            JsonObject o = root.getAsJsonObject();
            if (o.has("ok") && o.get("ok").isJsonPrimitive() && !o.get("ok").getAsBoolean()) return Payload.EMPTY;
            int version = o.has("version") && o.get("version").isJsonPrimitive() ? o.get("version").getAsInt() : 0;
            return new Payload(version, terms(o, "hard"), terms(o, "word"), terms(o, "cjk"),
                terms(o, "phrases"), terms(o, "allow"), ladder(o));
        } catch (Exception e) {
            return Payload.EMPTY;
        }
    }

    private static List<String> terms(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(key)) {
            if (out.size() >= MAX_TERMS) break;
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) continue;
            String t = e.getAsString().strip();
            if (t.length() < 2 || t.length() > 64) continue;
            out.add(t);
        }
        return List.copyOf(out);
    }

    static Ladder ladder(JsonObject o) {
        if (!o.has("ladder") || !o.get("ladder").isJsonObject()) return null;
        JsonObject l = o.getAsJsonObject("ladder");
        try {
            int removeAt = intOr(l, "removeAtStrike", Ladder.DEFAULT.removeAtStrike());
            int banAfter = intOr(l, "banAfterRemovals", Ladder.DEFAULT.banAfterRemovals());
            int decay = intOr(l, "strikeDecayDays", Ladder.DEFAULT.strikeDecayDays());
            List<Integer> days = new ArrayList<>();
            if (l.has("banDays") && l.get("banDays").isJsonArray()) {
                for (JsonElement e : l.getAsJsonArray("banDays")) {
                    if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) days.add(Math.max(0, e.getAsInt()));
                }
            }
            if (days.isEmpty()) days = Ladder.DEFAULT.banDays();
            if (removeAt < 1 || banAfter < 1) return null;
            return new Ladder(removeAt, banAfter, List.copyOf(days), Math.max(0, decay));
        } catch (Exception e) {
            return null;
        }
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber()
            ? o.get(key).getAsInt() : fallback;
    }

    /** Serialize a payload back to the shared shape (the disk cache). Pure. */
    static String toJson(Payload p) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("version", p.version());
        o.add("hard", array(p.hard()));
        o.add("word", array(p.word()));
        o.add("cjk", array(p.cjk()));
        o.add("phrases", array(p.phrases()));
        o.add("allow", array(p.allow()));
        if (p.ladder() != null) {
            JsonObject l = new JsonObject();
            l.addProperty("removeAtStrike", p.ladder().removeAtStrike());
            l.addProperty("banAfterRemovals", p.ladder().banAfterRemovals());
            JsonArray d = new JsonArray();
            for (int x : p.ladder().banDays()) d.add(x);
            l.add("banDays", d);
            l.addProperty("strikeDecayDays", p.ladder().strikeDecayDays());
            o.add("ladder", l);
        }
        return o.toString();
    }

    private static JsonArray array(List<String> items) {
        JsonArray a = new JsonArray();
        for (String s : items) a.add(s);
        return a;
    }

    static void saveDiskCache(Payload p) {
        Path target = defaultFile();
        if (target == null) return;
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, toJson(p), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] live word list: failed to write {}: {}", target, e.toString());
        }
    }

    private static Path defaultFile() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Test seam: force the relay overlay (bypassing network + disk); {@code null} resets. */
    static synchronized void setRelayForTest(Payload p) {
        relay = p == null ? Payload.EMPTY : p;
        loaded = p != null;
        compiled = null;
    }
}
