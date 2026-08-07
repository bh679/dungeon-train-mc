package games.brennan.dungeontrain.modrec;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Which of the player's loaded mods are not ours" — the gate and the tile list for the death
 * screen's Mod Recommendations page.
 *
 * <p>Membership is read from {@code assets/dungeontrain/modrec/roster.json}, generated at build
 * time by the {@code generateModRoster} Gradle task from the two files that already state it:
 * the {@code neoforge.mods.toml} template (Dungeon Train itself, its hard dependencies, the
 * jarJar'd siblings, and the declared optional compat entries) and
 * {@code modpack/modpack.config.json} (the companion roster). Nothing here is hand-maintained —
 * add a companion to the modpack and it drops out of the grid on the next build.</p>
 *
 * <p>Matching is on a normalised key (lowercase, alphanumerics only) tried against both the mod's
 * id and its display name, because the modpack config stores CurseForge slugs while
 * {@code ModList} reports modIds and the two disagree often enough to matter.</p>
 *
 * <p><b>Fails closed.</b> A missing, empty or unparseable roster yields no leftovers at all, so
 * the page simply never opens. The alternative — treating an unreadable roster as "nothing is
 * ours" — would offer the player a grid consisting mostly of Dungeon Train's own components,
 * which is worse than not asking.</p>
 */
public final class ModRoster {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String RESOURCE = "/assets/dungeontrain/modrec/roster.json";

    /** One mod as reported by the loader: its id and the name a player would recognise. */
    public record LoadedMod(String modId, String displayName) {}

    /**
     * The loaded roster: exact normalised {@code keys}, plus raw-modId {@code prefixes} for families
     * that register many ids at once (the Forgified Fabric API's {@code fabric_*} modules).
     */
    public record Roster(Set<String> keys, List<String> prefixes) {
        public Roster {
            keys = Set.copyOf(keys);
            prefixes = List.copyOf(prefixes);
        }
        public boolean isEmpty() { return keys.isEmpty(); }
        public static Roster of(Set<String> keys) { return new Roster(keys, List.of()); }
    }

    private static volatile Roster roster;

    private ModRoster() {}

    /** Normalise an id or display name to the roster's key form: lowercase, alphanumerics only. */
    public static String normalise(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** The roster, loaded once from the jar. Empty when the resource is missing or broken. */
    public static Roster roster() {
        Roster local = roster;
        if (local == null) {
            synchronized (ModRoster.class) {
                local = roster;
                if (local == null) {
                    local = load();
                    roster = local;
                }
            }
        }
        return local;
    }

    private static Roster load() {
        try (var in = ModRoster.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("[DungeonTrain] mod roster resource {} missing; recommendations page disabled", RESOURCE);
                return Roster.of(Set.of());
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Set<String> keys = new HashSet<>();
            for (var e : root.getAsJsonArray("keys")) {
                String k = normalise(e.getAsString());
                if (!k.isEmpty()) keys.add(k);
            }
            List<String> prefixes = new ArrayList<>();
            if (root.has("prefixes")) {
                for (var e : root.getAsJsonArray("prefixes")) {
                    String p = e.getAsString().toLowerCase(Locale.ROOT);
                    if (!p.isEmpty()) prefixes.add(p);
                }
            }
            return new Roster(keys, prefixes);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] mod roster failed to load; recommendations page disabled: {}", t.toString());
            return Roster.of(Set.of());
        }
    }

    /**
     * Whether this mod is part of Dungeon Train or its modpack. Matched against the normalised id
     * and display name, then against the raw-id prefixes (which catch families like the Forgified
     * Fabric API's {@code fabric_*} modules without enumerating a set that keeps changing).
     */
    public static boolean isOurs(Roster roster, String modId, String displayName) {
        if (roster.keys().contains(normalise(modId))) return true;
        if (roster.keys().contains(normalise(displayName))) return true;
        String raw = modId == null ? "" : modId.toLowerCase(Locale.ROOT);
        for (String p : roster.prefixes()) {
            if (raw.startsWith(p)) return true;
        }
        return false;
    }

    /**
     * The player's mods that aren't ours, in the order given. Pure and roster-injected so the
     * membership rules can be unit-tested without a loaded jar; {@link #leftovers(Collection)} is
     * the runtime entry point. An empty roster returns nothing (see the class note on failing
     * closed).
     */
    public static List<LoadedMod> leftovers(Roster roster, Collection<LoadedMod> loaded) {
        if (roster.isEmpty() || loaded == null) return List.of();
        List<LoadedMod> out = new ArrayList<>();
        for (LoadedMod m : loaded) {
            if (m == null || m.modId() == null || m.modId().isBlank()) continue;
            if (!isOurs(roster, m.modId(), m.displayName())) out.add(m);
        }
        return List.copyOf(out);
    }

    /** The player's mods that aren't ours, against the roster shipped in the jar. */
    public static List<LoadedMod> leftovers(Collection<LoadedMod> loaded) {
        return leftovers(roster(), loaded);
    }
}
