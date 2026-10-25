package games.brennan.dungeontrain.perks;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every perk the mod knows about, by id. Class sessions register their perks from a static
 * initialiser (see {@link TierOnePerks}); registration is idempotent for an identical
 * definition and rejects a different definition under the same id.
 *
 * <p>Thread-safe for registration during mod construction; reads afterwards are lock-free
 * copies.</p>
 */
public final class PerkRegistry {

    private static final Map<String, PerkDef> PERKS = Collections.synchronizedMap(new LinkedHashMap<>());

    private PerkRegistry() {}

    /** Register a perk. Returns the definition so callers can keep a handle. */
    public static PerkDef register(PerkDef def) {
        synchronized (PERKS) {
            PerkDef existing = PERKS.get(def.id());
            if (existing != null && !existing.equals(def)) {
                throw new IllegalStateException("perk '" + def.id() + "' already registered as " + existing + ", not " + def);
            }
            PERKS.putIfAbsent(def.id(), def);
        }
        return def;
    }

    public static PerkDef register(String id, PerkClass perkClass, int tier) {
        return register(new PerkDef(id, perkClass, tier));
    }

    public static Optional<PerkDef> get(String id) {
        return Optional.ofNullable(id == null ? null : PERKS.get(id));
    }

    public static boolean isKnown(String id) {
        return id != null && PERKS.containsKey(id);
    }

    /** Registration order. */
    public static Collection<PerkDef> all() {
        synchronized (PERKS) {
            return Collections.unmodifiableList(new java.util.ArrayList<>(PERKS.values()));
        }
    }

    public static Set<String> ids() {
        synchronized (PERKS) {
            return Set.copyOf(PERKS.keySet());
        }
    }

    /** Make sure the built-in perks are registered before a read (class init is lazy). */
    public static void bootstrap() {
        TierOnePerks.ensureRegistered();
    }
}
