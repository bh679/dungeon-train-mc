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

    private static volatile Set<String> keys;

    private ModRoster() {}

    /** Normalise an id or display name to the roster's key form: lowercase, alphanumerics only. */
    public static String normalise(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** The roster keys, loaded once from the jar. Empty when the resource is missing or broken. */
    public static Set<String> keys() {
        Set<String> local = keys;
        if (local == null) {
            synchronized (ModRoster.class) {
                local = keys;
                if (local == null) {
                    local = load();
                    keys = local;
                }
            }
        }
        return local;
    }

    private static Set<String> load() {
        try (var in = ModRoster.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("[DungeonTrain] mod roster resource {} missing; recommendations page disabled", RESOURCE);
                return Set.of();
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Set<String> out = new HashSet<>();
            for (var e : root.getAsJsonArray("keys")) {
                String k = normalise(e.getAsString());
                if (!k.isEmpty()) out.add(k);
            }
            return Set.copyOf(out);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] mod roster failed to load; recommendations page disabled: {}", t.toString());
            return Set.of();
        }
    }

    /** Whether this mod is part of Dungeon Train or its modpack — matched by id or display name. */
    public static boolean isOurs(Set<String> roster, String modId, String displayName) {
        return roster.contains(normalise(modId)) || roster.contains(normalise(displayName));
    }

    /**
     * The player's mods that aren't ours, in the order given. Pure and roster-injected so the
     * membership rules can be unit-tested without a loaded jar; {@link #leftovers(Collection)} is
     * the runtime entry point. An empty roster returns nothing (see the class note on failing
     * closed).
     */
    public static List<LoadedMod> leftovers(Set<String> roster, Collection<LoadedMod> loaded) {
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
        return leftovers(keys(), loaded);
    }
}
