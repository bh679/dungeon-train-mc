package games.brennan.dungeontrain.cheat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The set of mod IDs Dungeon Train treats as "cheat mods" — installing one flips the run to
 * <b>Free Play</b> (see {@link CheatModIntegrity}). Two sources merged:
 *
 * <ul>
 *   <li><b>Baked</b> ({@link #BAKED}) — a curated list shipped in the jar, so detection always
 *       works offline and on the very first launch.</li>
 *   <li><b>Relay</b> — an updatable list fetched anonymously from {@code GET /cheat-mods} by
 *       {@link CheatModListFetcher}, so a newly-discovered cheat mod reaches already-shipped jars
 *       without a release. The last successful fetch is cached to
 *       {@code config/dungeontrain-cheat-mods.json} (atomic tmp-then-rename, mirroring
 *       {@code RelayOutbox}) so an offline boot still has the last-known list.</li>
 * </ul>
 *
 * <p>{@link #effective()} returns the union. Because the network fetch is async but detection runs
 * synchronously at server boot, the current boot uses baked ∪ last-cached-relay immediately and a
 * fresh fetch only affects the next boot — a one-session propagation lag that is fine for a soft
 * honesty nudge. This is not hard anti-cheat: a determined cheater can defeat it; the goal is to
 * keep honest players' global stats meaningful.</p>
 *
 * <p>All writes swap the {@code volatile relay} snapshot whole (never mutate it); readers only
 * touch volatile state. Relay values are validated ({@link #isValidModId}) so a typo'd server-side
 * entry can never poison the list.</p>
 */
public final class CheatModList {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Curated, shipped-in-the-jar cheat mod IDs (lowercase). NeoForge-relevant — many notorious
     * cheat clients (Meteor, Wurst) are Fabric-only and can't load here, so this leans toward the
     * mod categories that DO exist on NeoForge: x-ray, fullbright, freecam, and automation.
     *
     * <p><b>Curation list — extend freely.</b> IDs are matched case-insensitively against the
     * installed mods' own {@code modId}. When unsure of a mod's real ID, check its
     * {@code neoforge.mods.toml}; the display name is not the ID. The relay list ({@code /cheat-mods})
     * augments this at runtime, so urgent additions don't need a release.</p>
     */
    static final Set<String> BAKED = Set.of(
        // x-ray
        "xray", "xraymod", "advancedxray", "xrayultimate",
        // automation / pathfinding
        "baritone",
        // enchantment cap-removers — pure cheat tools that raise/remove the vanilla enchant level
        // cap (they add no new enchantments). DT's EnchantLevelCap already keeps *natural* loot in
        // check, but these still let a player anvil/command themselves absurd gear, so treat their
        // mere presence as Free Play. (Command-driven /ec-style enchanting is already caught by the
        // command allowlist.)
        "enchantmentlevelbreak", "betterenchants", "limitlessenchantments",
        "noenchantmentcaplevel", "noenchantcap",
        // generic cheat utilities
        "meteor-client", "wurst", "aristois", "impact",
        // client-side command toolkits — enchantment/RNG seed cracking (/cenchant, /ccrackrng),
        // ore + entity finding (/cfind), and item/world manipulation helpers. Client-only, so it
        // is caught in single-player (shared JVM) but not on a dedicated server.
        "clientcommands",
        // vein miners / chain mining — one swing breaks the whole ore vein (or the whole tree),
        // which multiplies resource income well past what DT's loot and difficulty curves assume.
        // Every ID below was read out of that mod's own neoforge.mods.toml/mods.toml (slugs and
        // display names are NOT mod IDs); library and dependency IDs were stripped so no innocent
        // mod can be caught by association. Excluded on purpose: worldgen mods that merely place
        // bigger veins, and AoE-hammer content mods — neither is one-swing chain mining.
        "veinminer", "veinminer_client", "veinminer_enchantment", "veinmining", "veinminermod",
        "veinminerplusplus", "veinminerrevived", "oreexcavation", "oreharvester", "ftbultimine",
        "ultimine_rewind", "liteminer", "onekeyminer", "vein_vantage", "vein_mine", "excavein",
        "chainvein", "simple_vein_miner", "tethermine", "quickmine", "svmm",
        "all_in_one_veinminer", "multiveinminer", "universalveinminer", "advancedveinminer",
        "archveinminer_neof", "shiftingveinminer", "exoveinmine", "dh_s_veinminer",
        "ore_veinminer", "vyvern_mine", "tinkers_vein_miner", "collectall",
        // …same category, but the mod ID is an ordinary English word. Verified against the real
        // jars, and accepted with eyes open: if some unrelated mod ever ships one of these IDs it
        // would false-trip Free Play. Cheap to undo (Free Play is session-only, nothing is written
        // to the world), so the coverage is worth the small risk.
        "burst", "shatter", "excavate", "viner", "azmine", "vpx_data",
        // tree-fellers / treecapitators — the same one-swing-many-blocks deal, for wood.
        "treefeller", "treetimberneo", "heavyaxxe_",
        // Modrinth "mr_*" wrappers — vein-miner datapacks republished as loadable mods, so they
        // show up in ModList under these generated IDs.
        "mr_vein_miner", "mr_ore_veinminer", "mr_tree_veinminer", "mr_the_veinminer",
        "mr_custom_vm", "mr_coconite_coolveinminer", "mr_ly_veinminerenchantment",
        "mr_ore_veinminerenchant", "mr_ks_veinminerenchantment", "mr_tree_veinminerenchant",
        "mr_tree_miner_leaf_decay", "mr_veinmine_autosmeltenchantments",
        // unlimited villager trading — removing the trade lock turns one villager hall into an
        // uncapped emerald faucet, which is precisely the income curve DT's loot and difficulty
        // tables are balanced against. Same reasoning as the vein miners above: not malicious,
        // but it plays a different game. Every ID was read out of that jar's own
        // neoforge.mods.toml; the "mr_*" pair are Modrinth datapack-to-mod republishes, which
        // load under generated IDs (same as the vein-miner wrappers above). Deliberately
        // excluded: trade-cycling and villager-carrying QoL mods — they make the vanilla
        // restock loop less tedious, they do not remove the cap.
        "infinitetrading", "mr_unlimited_trading", "mr_unlimited_villagertrades",
        // in-game item / NBT editors — build any item with any component, i.e. /give with a GUI
        // and no command for CommandAllowlist to see. They already need operator permission to
        // do anything (which OperatorIntegrity now catches on its own), but naming them lets the
        // login notice say WHAT tripped Free Play rather than just "someone has cheats".
        // "infinityeditor" is the original Ruukas build, "infinity_item_editor_re" the
        // maintained NeoForge fork, and "cadeditor" the CAD (Component And Data) Editor — the
        // IBE Editor successor, which edits the 1.20.5+ component set (attributes, enchantments,
        // stack size, unbreakable, container contents, item type) from a GUI. "dine" (Dynamic
        // In-Game NBT Editor) is the same idea aimed at entities and block entities — a live NBT
        // tree you can rewrite mid-run. It drives the vanilla /data command, so its edits already
        // need permission level 2, but naming it here lets the notice say what tripped Free Play.
        // "ankinbt" (AnkiNBT) covers all three surfaces at once — items, live entities and
        // villager trades — with a simple mode for names/enchantments/attributes and a full NBT
        // tree behind it.
        // "nbtedit" is In-game NBTEdit Reborn, the maintained continuation of the original
        // NBTEdit — an /nbtedit tree over the held item or the entity you look at.
        "infinityeditor", "infinity_item_editor_re", "cadeditor", "dine", "ankinbt", "nbtedit",
        // mob-stat / spawn-rule rewriters — a GUI over every living entity's health, armour,
        // speed, attack damage, damage-type immunities, and the biome + group-size rules that
        // decide what spawns. That is DT's difficulty curve and spawn tables rewritten from a
        // menu, so a run made under one is not comparable to anyone else's. Presence-based like
        // the rest of the list: it flips Free Play whether the player tuned mobs down OR up.
        "visual_mobs_edit"
    );

    /** Cache file under the loader config dir; written on each successful relay fetch. */
    static final String FILE_NAME = "dungeontrain-cheat-mods.json";

    private static final int MAX_ID_LEN = 64;
    private static final int MAX_IDS = 500;

    /** Sanitized relay overlay — only ever swapped whole, never mutated. */
    private static volatile Set<String> relay = Set.of();

    /**
     * True once the disk cache has been consulted OR a network fetch has landed — either way the
     * in-memory {@code relay} is authoritative and stale disk must not overwrite it.
     */
    private static volatile boolean loaded = false;

    private CheatModList() {}

    /**
     * The effective cheat-mod ID set: baked ∪ relay (relay seeded from the disk cache on first
     * use). Lowercase; callers compare a lowercased mod ID against this.
     */
    public static Set<String> effective() {
        loadDiskCacheOnce();
        Set<String> out = new HashSet<>(BAKED);
        out.addAll(relay);
        return Set.copyOf(out);
    }

    /**
     * Accept a freshly-fetched relay list: validate, swap the overlay, and persist to the disk
     * cache. Marks the list loaded so a later {@link #effective()} won't reload stale disk over it.
     * Called from {@link CheatModListFetcher} on its HTTP completion thread.
     */
    static synchronized void accept(Collection<String> ids) {
        Set<String> clean = sanitize(ids);
        loaded = true;
        relay = clean;
        saveDiskCache(clean);
    }

    /** New lowercase set holding only the entries that pass {@link #isValidModId}. */
    static Set<String> sanitize(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>();
        for (String id : raw) {
            if (isValidModId(id)) out.add(id.trim().toLowerCase(Locale.ROOT));
            if (out.size() >= MAX_IDS) break;
        }
        return Set.copyOf(out);
    }

    /**
     * A plausible mod ID: non-empty after trim, sane length, only {@code [a-z0-9_.-]} (after
     * lowercasing). Anything else is dropped — the relay is trusted, but a malformed server value
     * must never enter the match set (worst case it would false-positive some innocent mod).
     */
    static boolean isValidModId(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > MAX_ID_LEN) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.';
            if (!ok) return false;
        }
        return true;
    }

    /** Read the disk cache once per JVM, seeding {@code relay}. Best-effort — never throws. */
    static synchronized void loadDiskCacheOnce() {
        if (loaded) return;
        loaded = true;
        Path file = defaultFile();
        if (file == null || !Files.exists(file)) return;
        try {
            String body = Files.readString(file, StandardCharsets.UTF_8);
            relay = parse(body);
            LOGGER.debug("[DungeonTrain] cheat-mod list: loaded {} cached id(s) from {}",
                relay.size(), file);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] cheat-mod list: could not read {} — using baked only: {}",
                file, e.toString());
        }
    }

    /**
     * Parse {@code {"ok":true,"mods":[...]}} (or a bare {@code {"mods":[...]}}) into a clean set.
     * Defensive at the boundary: any malformed body → empty set, never throws.
     */
    static Set<String> parse(String body) {
        try {
            JsonElement root = com.google.gson.JsonParser.parseString(body);
            if (!root.isJsonObject()) return Set.of();
            JsonObject o = root.getAsJsonObject();
            if (!o.has("mods") || !o.get("mods").isJsonArray()) return Set.of();
            JsonArray arr = o.getAsJsonArray("mods");
            java.util.List<String> ids = new java.util.ArrayList<>(arr.size());
            for (JsonElement e : arr) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) ids.add(e.getAsString());
            }
            return sanitize(ids);
        } catch (Exception e) {
            return Set.of();
        }
    }

    /** Serialize a mod-ID set to the {@code {"ok":true,"mods":[...]}} wire/cache form. Pure. */
    static String toJson(Set<String> ids) {
        JsonArray arr = new JsonArray();
        for (String id : ids) arr.add(id);
        JsonObject obj = new JsonObject();
        obj.addProperty("ok", true);
        obj.add("mods", arr);
        return obj.toString();
    }

    /** Atomic tmp-then-rename write of the relay list (mirrors {@code RelayOutbox.save}). */
    static void saveDiskCache(Set<String> ids) {
        Path target = defaultFile();
        if (target == null) return;
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, toJson(ids), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] cheat-mod list: failed to write {}: {}",
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
     * Test seam: force the relay overlay to a known set (bypasses network + disk), marking the
     * list loaded so {@link #effective()} won't reload disk. {@code null} resets to pristine.
     */
    static synchronized void setRelayForTest(Collection<String> ids) {
        if (ids == null) {
            relay = Set.of();
            loaded = false;
        } else {
            relay = sanitize(ids);
            loaded = true;
        }
    }
}
