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
 *   <li><b>Baked</b> — {@code assets/dungeontrain/cheat/approved_mods.json}, shipped in the jar so
 *       the check works offline and on the very first launch. GENERATED at build time
 *       ({@code gradle/approved-mods.gradle}): every modpack mod, jarJar'd sibling and declared
 *       dependency, plus the hand-curated rest from {@code src/main/whitelist/}. Also carries
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
 * anything.</b> It ships ON ({@link #BAKED_ENFORCE}), so an unapproved mod makes the run Free Play
 * from the first launch, offline included. The relay can turn it off with an explicit
 * {@code "enforce":false}: the kill switch that returns the whole player base to normal play on
 * their next launch. It matters more here than it did for the blacklist, because the failure
 * direction is inverted — a missing entry on the blacklist lets one cheat through, a missing entry
 * HERE free-plays every honest player running that mod. A payload that does not mention
 * {@code enforce} (or cannot be read) leaves the current value alone.</p>
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
    /**
     * The hash-verified half (2026-10): every jar SHA-1 the relay currently approves, plus the
     * ones baked in for the modpack's pinned files. A mod id is a line in mods.toml anyone can
     * edit; a file's hash is not — so {@link UnapprovedModIntegrity} also asks whether each
     * installed FILE is one of these. See {@link ModFileScanner}.
     */
    private static volatile Set<String> bakedHashes = Set.of();
    private static volatile Set<String> relayHashes = Set.of();
    /** A lowercase 40-hex SHA-1, as the relay serves and the scanner computes them. */
    private static final java.util.regex.Pattern SHA1 = java.util.regex.Pattern.compile("[0-9a-f]{40}");
    private static final int MAX_HASHES = 200000;
    /**
     * Enforcement as this jar ships it: ON. A run with an unapproved mod installed is Free Play from
     * the very first launch, offline included. The relay can only turn it OFF (or back on) with an
     * explicit value, which is then cached so the kill switch also holds on offline boots.
     */
    static final boolean BAKED_ENFORCE = true;
    private static volatile boolean enforce = BAKED_ENFORCE;
    /**
     * The HASH check's own switch, and it ships OFF: the relay fills its catalogue from what
     * players report while this is off, and flips it on once coverage is good. The failure
     * direction is the same as {@link #enforce} — a file the relay has not placed yet free-plays
     * an honest player — so a payload that does not mention it leaves the current value alone.
     */
    static final boolean BAKED_ENFORCE_HASHES = false;
    private static volatile boolean enforceHashes = BAKED_ENFORCE_HASHES;

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
     * true ({@link #BAKED_ENFORCE}); an explicit relay value (live or cached) overrides it.
     */
    public static boolean enforce() {
        loadDiskCacheOnce();
        return enforce;
    }

    /** The effective approved-FILE set: baked ∪ relay SHA-1s, lowercase hex. */
    public static Set<String> approvedHashes() {
        loadBakedOnce();
        loadDiskCacheOnce();
        Set<String> out = new HashSet<>(bakedHashes);
        out.addAll(relayHashes);
        return Set.copyOf(out);
    }

    /**
     * Is the hash check enforced — does an installed file the relay could not verify flip the run
     * to Free Play? Ships false ({@link #BAKED_ENFORCE_HASHES}); only an explicit relay value
     * (live or cached) turns it on.
     */
    public static boolean enforceHashes() {
        loadDiskCacheOnce();
        return enforceHashes;
    }

    /** Pure: is this file's hash on the approved-file list? */
    static boolean isFileApproved(ModFileFingerprint file, Set<String> approvedSha1s) {
        return file != null && file.sha1() != null && approvedSha1s.contains(file.sha1());
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
        if (payload.enforce() != null) enforce = payload.enforce();
        relayHashes = payload.hashes();
        if (payload.enforceHashes() != null) enforceHashes = payload.enforceHashes();
        saveDiskCache(payload);
    }

    /**
     * What the relay serves, and what the disk cache holds: two id lists and the enforcement flag,
     * plus (since the hash-verified whitelist) the approved file hashes and their own flag.
     * {@code enforce} / {@code enforceHashes} are {@code null} when the payload did not say — the
     * current value (baked or cached) then stands, so a relay that has never been told cannot
     * switch enforcement off, and a relay too old to know about hashes cannot switch THAT on.
     */
    record Payload(Set<String> approved, Set<String> revoked, Boolean enforce,
                   Set<String> hashes, Boolean enforceHashes) {
        Payload {
            hashes = hashes == null ? Set.of() : Set.copyOf(hashes);
        }

        /** The pre-hash shape: no file hashes, hash enforcement unsaid. */
        Payload(Set<String> approved, Set<String> revoked, Boolean enforce) {
            this(approved, revoked, enforce, Set.of(), null);
        }
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
            bakedHashes = parseHashes(root);
            LOGGER.debug("[DungeonTrain] approved-mod list: {} baked id(s), {} prefix(es), {} file hash(es)",
                baked.size(), prefixes.size(), bakedHashes.size());
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

    /**
     * The {@code hashes} array — lowercase 40-hex SHA-1s — of a baked resource or a relay payload.
     * Anything that is not a SHA-1 is dropped: a malformed entry can only ever fail to approve a
     * file, never approve the wrong one, but there is no reason to carry junk.
     */
    static Set<String> parseHashes(JsonObject root) {
        if (!root.has("hashes") || !root.get("hashes").isJsonArray()) return Set.of();
        Set<String> out = new HashSet<>();
        for (JsonElement e : root.getAsJsonArray("hashes")) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) continue;
            String h = e.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
            if (SHA1.matcher(h).matches()) out.add(h);
            if (out.size() >= MAX_HASHES) break;
        }
        return Set.copyOf(out);
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
            if (p == null) return;
            relayApproved = p.approved();
            relayRevoked = p.revoked();
            if (p.enforce() != null) enforce = p.enforce();
            relayHashes = p.hashes();
            if (p.enforceHashes() != null) enforceHashes = p.enforceHashes();
            LOGGER.debug("[DungeonTrain] approved-mod list: loaded {} approval(s), {} revocation(s), "
                + "enforce={}, {} file hash(es), enforceHashes={} from {}", relayApproved.size(),
                relayRevoked.size(), enforce, relayHashes.size(), enforceHashes, file);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] approved-mod list: could not read {} — using baked only: {}",
                file, e.toString());
        }
    }

    /**
     * Parse {@code {"ok":true,"approved":[…],"revoked":[…],"enforce":false}} into a payload.
     * Defensive at the boundary, never throws: an unreadable body (not JSON, not an object) is
     * {@code null}, and the caller keeps what it already had — lists AND enforcement. An {@code
     * enforce} that is missing or not a boolean comes back {@code null} for the same reason: only an
     * explicit relay value may change it.
     */
    static Payload parse(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root == null || !root.isJsonObject()) return null;
            JsonObject o = root.getAsJsonObject();
            Set<String> approvedIds = ModIds.sanitize(stringsAt(o, "approved"), MAX_IDS);
            Set<String> revokedIds = ModIds.sanitize(stringsAt(o, "revoked"), MAX_IDS);
            Boolean on = boolAt(o, "enforce");
            return new Payload(approvedIds, revokedIds, on, parseHashes(o), boolAt(o, "enforceHashes"));
        } catch (Exception e) {
            return null;
        }
    }

    /** A boolean field, or {@code null} when missing or not a boolean — "the relay did not say". */
    private static Boolean boolAt(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.getAsJsonPrimitive(key).isBoolean()
            ? o.get(key).getAsBoolean() : null;
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
        if (payload.enforce() != null) obj.addProperty("enforce", payload.enforce());
        obj.add("hashes", arrayOf(new java.util.TreeSet<>(payload.hashes())));
        if (payload.enforceHashes() != null) obj.addProperty("enforceHashes", payload.enforceHashes());
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
            enforce = BAKED_ENFORCE;
            relayHashes = Set.of();
            enforceHashes = BAKED_ENFORCE_HASHES;
            loaded = false;
        } else {
            relayApproved = payload.approved();
            relayRevoked = payload.revoked();
            enforce = payload.enforce() != null ? payload.enforce() : BAKED_ENFORCE;
            relayHashes = payload.hashes();
            enforceHashes = payload.enforceHashes() != null ? payload.enforceHashes() : BAKED_ENFORCE_HASHES;
            loaded = true;
        }
    }
}
