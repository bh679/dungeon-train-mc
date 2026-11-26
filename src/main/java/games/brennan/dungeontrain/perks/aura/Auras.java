package games.brennan.dungeontrain.perks.aura;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Every aura perk, by perk id. Filled by each class's {@code declare()}; read on both sides. */
public final class Auras {

    private static final Map<String, AuraDef> DEFS = java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    private Auras() {}

    public static void register(AuraDef def) {
        DEFS.put(def.perkId(), def);
    }

    /** The aura for a perk id, or null when the perk is not an aura. */
    public static AuraDef of(String perkId) {
        games.brennan.dungeontrain.perks.BuiltPerks.bootstrap();
        return perkId == null ? null : DEFS.get(perkId);
    }

    public static Collection<AuraDef> all() {
        games.brennan.dungeontrain.perks.BuiltPerks.bootstrap();
        synchronized (DEFS) {
            return java.util.List.copyOf(DEFS.values());
        }
    }
}
