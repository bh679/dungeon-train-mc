package games.brennan.dungeontrain.cheat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The set of mod IDs Dungeon Train treats as <b>approved</b> — mods a run may have installed and
 * still count. This is the whitelist half of the Free Play mod check, and the inverse of
 * {@link CheatModList}: that list names what we know is cheating, this one names what we have
 * decided is fine, and {@link UnapprovedModIntegrity} treats everything else installed as
 * unapproved.
 *
 * <p>A blacklist can only ever catch what we already know about, which is the wrong shape for the
 * question Free Play actually asks — "did this run play the game we balanced?". Both lists are
 * kept: an unapproved mod that is ALSO a known cheat mod gets the specific message it deserves.</p>
 *
 * <p>Three sources, resolved in {@link #approved()}:</p>
 * <ul>
 *   <li><b>Baked</b> — {@code assets/dungeontrain/cheat/approved_mods.json}, curated and shipped in
 *       the jar, so the check works offline and on the very first launch. Also carries
 *       {@link #prefixes()}, a short list of raw-ID prefixes (Sinytra Connector's ~45 {@code
 *       fabric_*} modules) where a rule beats a transcription that goes stale silently.</li>
 *   <li><b>Relay approvals</b> — added to the baked set, so a newly-approved mod reaches
 *       already-shipped jars without a release.</li>
 *   <li><b>Relay revocations</b> — subtracted last, and they beat everything: an approval baked
 *       into a shipped jar (or matched by a prefix) can be pulled without a release.</li>
 * </ul>
 *
 * <p>So the effective set is {@code (baked ∪ approved) − revoked}, and a prefix match counts as
 * approval unless the exact ID was revoked. The last successful fetch is cached to
 * {@code config/dungeontrain-approved-mods.json} (atomic tmp-then-rename, mirroring
 * {@link CheatModList}) so an offline boot still has the last-known list.</p>
 *
 * <p><b>{@link #enforce()} is the switch that decides whether any of this costs a player
 * anything.</b> It ships false and is turned on from the relay. While it is false, unapproved mods
 * are detected and logged but the run is untouched — an observe-only period in which the real
 * impact of the list can be measured before anybody's progress is affected. It is also the kill
 * switch: turning it off returns the whole player base to normal play on their next launch. That
 * matters more here than it did for the blacklist, because the failure direction is inverted — a
 * missing entry on the blacklist lets one cheat through, a missing entry HERE free-plays every
 * honest player running that mod.</p>
 *
 * <p>All writes swap the {@code volatile} snapshots whole (never mutate them); readers only touch
 * volatile state. Relay values are validated ({@link ModIds#isValid}) so a typo'd server-side entry
 * can never poison the list.</p>
 */
public final class ApprovedModList {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The curated whitelist shipped in the jar. */
    static final String RESOURCE = "/assets/dungeontrain/cheat/approved_mods.json";

    /** Cache file under the loader config dir; written on each successful relay fetch. */
    static final String FILE_NAME = "dungeontrain-approved-mods.json";

    // A whitelist is inherently long — the baked seed alone is ~180 entries, and the relay list
    // grows as the ecosystem is curated. Far higher than CheatModList's cap, which bounds a list of
    // exceptions rather than a list of everything that is fine.
    private static final int MAX_IDS = 20000;

    /** Parsed once from the jar resource; never null after {@link #loadBakedOnce}. */
    private static volatile Set<String> baked;
    private static volatile List<String> prefixes = List.of();

    /** Sanitized relay overlays — only ever swapped whole, never mutated. */
    private static volatile Set<String> relayApproved = Set.of();
    private static volatile Set<String> relayRevoked = Set.of();
    private static volatile boolean enforce = false;

    /**
     * True once the disk cache has been consulted OR a network fetch has landed — either way the
     * in-memory overlays are authoritative and stale disk must not overwrite them.
     */
    private static volatile boolean loaded = false;

    private ApprovedModList() {}

    /**
     * The effective approved-mod ID set: {@code (baked ∪ relay approved) − relay revoked}.
     * Lowercase; callers compare a lowercased mod ID against this, or use {@link #isApproved} to
     * get the prefix rule as well.
     */
    public static Set<String> approved() {
        loadBakedOnce();
        loadDiskCacheOnce();
        Set<String> out = new HashSet<>(baked);
        out.addAll(relayApproved);
        out.removeAll(relayRevoked);
        return Set.copyOf(out);
    }

    /** Raw-ID prefixes that count as approved (see the resource's {@code prefixesWhy}). */
    public static List<String> prefixes() {
        loadBakedOnce();
        return prefixes;
    }

    /**
     * Is enforcement on — i.e. does an unapproved mod actually flip the run to Free Play? Ships
     * false; only the relay turns it on.
     */
    public static boolean enforce() {
        loadDiskCacheOnce();
        return enforce;
    }

    /**
     * Is this mod ID approved? Exact match first, then the prefix rule — but a revoked ID is never
     * approved, whichever way it would otherwise have matched. Pure given the two arguments, so
     * {@link UnapprovedModIntegrity} can unit-test the whole scan with no live state.
     */
    static boolean isApproved(String modId, Set<String> approvedIds, List<String> idPrefixes,
                              Set<String> revokedIds) {
        String id = ModIds.normalise(modId);
        if (id.isEmpty()) return false;
        if (revokedIds != null && revokedIds.contains(id)) return false;
        if (approvedIds.contains(id)) return true;
        for (String p : idPrefixes) {
            if (!p.isEmpty() && id.startsWith(p)) return true;
        }
        return false;
    }

    /** Live-state convenience over {@link #isApproved}. */
    public static boolean isApproved(String modId) {
        return isApproved(modId, approved(), prefixes(), relayRevoked);
    }

    /** The current relay revocations — package-visible so the scan can pass them down. */
    static Set<String> revoked() {
        loadDiskCacheOnce();
        return relayRevoked;
    }

    /**
     * Accept a freshly-fetched relay payload: validate, swap the overlays, and persist to the disk
     * cache. Marks the list loaded so a later read won't reload stale disk over it. Called from
     * {@link ApprovedModListFetcher} on its HTTP completion thread.
     */
    static synchronized void accept(Payload payload) {
        loaded = true;
        relayApproved = payload.approved();
        relayRevoked = payload.revoked();
        enforce = payload.enforce();
        saveDiskCache(payload);
    }

    /** What the relay serves, and what the disk cache holds: two lists and the enforcement flag. */
    record Payload(Set<String> approved, Set<String> revoked, boolean enforce) {
        static final Payload EMPTY = new Payload(Set.of(), Set.of(), false);
    }

    /** Read the baked resource once per JVM. Best-effort — a missing/corrupt file means "empty". */
    static synchronized void loadBakedOnce() {
        if (baked != null) return;
        try (var in = ApprovedModList.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("[DungeonTrain] approved-mod list resource {} missing — "
                    + "no mod is approved from the jar", RESOURCE);
                baked = Set.of();
                return;
            }
            JsonObject root = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            baked = parseBaked(root);
            prefixes = parsePrefixes(root);
            LOGGER.debug("[DungeonTrain] approved-mod list: {} baked id(s), {} prefix(es)",
                baked.size(), prefixes.size());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] approved-mod list: could not read {} — "
                + "no mod is approved from the jar: {}", RESOURCE, e.toString());
            baked = Set.of();
        }
    }

    /**
     * Flatten the resource's {@code groups} into one ID set. The grouping exists so the file reads
     * as a set of curation decisions rather than a wall of strings; nothing at runtime cares which
     * group an ID came from. Package-visible for tests.
     */
    static Set<String> parseBaked(JsonObject root) {
        List<String> ids = new ArrayList<>();
        if (root.has("groups") && root.get("groups").isJsonObject()) {
            for (var entry : root.getAsJsonObject("groups").entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject group = entry.getValue().getAsJsonObject();
                if (!group.has("ids") || !group.get("ids").isJsonArray()) continue;
                for (JsonElement e : group.getAsJsonArray("ids")) {
                    if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) ids.add(e.getAsString());
                }
            }
        }
        return ModIds.sanitize(ids, MAX_IDS);
    }

    /** The resource's raw-ID prefixes, lowercased and validated as ID fragments. */
    static List<String> parsePrefixes(JsonObject root) {
        if (!root.has("prefixes") || !root.get("prefixes").isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("prefixes")) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) continue;
            String p = ModIds.normalise(e.getAsString());
            // A prefix must itself look like part of a mod ID, and must not be empty — an empty
            // prefix would approve literally every mod installed and quietly disable the feature.
            if (!p.isEmpty() && ModIds.isValid(p)) out.add(p);
        }
        return List.copyOf(out);
    }

    /** Read the disk cache once per JVM, seeding the relay overlays. Best-effort — never throws. */
    static synchronized void loadDiskCacheOnce() {
        if (loaded) return;
        loaded = true;
        Path file = defaultFile();
        if (file == null || !Files.exists(file)) return;
        try {
            Payload p = parse(Files.readString(file, StandardCharsets.UTF_8));
            relayApproved = p.approved();
            relayRevoked = p.revoked();
            enforce = p.enforce();
            LOGGER.debug("[DungeonTrain] approved-mod list: loaded {} approval(s), {} revocation(s), "
                + "enforce={} from {}", relayApproved.size(), relayRevoked.size(), enforce, file);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] approved-mod list: could not read {} — using baked only: {}",
                file, e.toString());
        }
    }

    /**
     * Parse {@code {"ok":true,"approved":[…],"revoked":[…],"enforce":false}} into a payload.
     * Defensive at the boundary: any malformed body → the empty payload, never throws. Note that
     * an unreadable body therefore turns enforcement OFF rather than leaving it on — the safe
     * direction when we cannot tell what the relay meant.
     */
    static Payload parse(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return Payload.EMPTY;
            JsonObject o = root.getAsJsonObject();
            Set<String> approvedIds = ModIds.sanitize(stringsAt(o, "approved"), MAX_IDS);
            Set<String> revokedIds = ModIds.sanitize(stringsAt(o, "revoked"), MAX_IDS);
            boolean on = o.has("enforce") && o.get("enforce").isJsonPrimitive()
                && o.getAsJsonPrimitive("enforce").isBoolean()
                && o.get("enforce").getAsBoolean();
            return new Payload(approvedIds, revokedIds, on);
        } catch (Exception e) {
            return Payload.EMPTY;
        }
    }

    private static List<String> stringsAt(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return List.of();
        JsonArray arr = o.getAsJsonArray(key);
        List<String> ids = new ArrayList<>(arr.size());
        for (JsonElement e : arr) {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) ids.add(e.getAsString());
        }
        return ids;
    }

    /** Serialize a payload to the wire/cache form. Pure. */
    static String toJson(Payload payload) {
        JsonObject obj = new JsonObject();
        obj.addProperty("ok", true);
        obj.add("approved", arrayOf(payload.approved()));
        obj.add("revoked", arrayOf(payload.revoked()));
        obj.addProperty("enforce", payload.enforce());
        return obj.toString();
    }

    private static JsonArray arrayOf(Collection<String> ids) {
        JsonArray arr = new JsonArray();
        for (String id : ids) arr.add(id);
        return arr;
    }

    /** Atomic tmp-then-rename write of the relay payload (mirrors {@code CheatModList.saveDiskCache}). */
    static void saveDiskCache(Payload payload) {
        Path target = defaultFile();
        if (target == null) return;
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, toJson(payload), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] approved-mod list: failed to write {}: {}",
                target, e.toString());
        }
    }

    private static Path defaultFile() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Test seam: force the relay overlays to a known state (bypasses network + disk), marking the
     * list loaded so reads won't reload disk. {@code null} resets to pristine.
     */
    static synchronized void setRelayForTest(Payload payload) {
        if (payload == null) {
            relayApproved = Set.of();
            relayRevoked = Set.of();
            enforce = false;
            loaded = false;
        } else {
            relayApproved = payload.approved();
            relayRevoked = payload.revoked();
            enforce = payload.enforce();
            loaded = true;
        }
    }
}
