package games.brennan.dungeontrain.perks;

import games.brennan.dungeontrain.perks.effects.archivist.ArchivistPerks;
import games.brennan.dungeontrain.perks.effects.crafter.CrafterPerks;
import games.brennan.dungeontrain.perks.effects.knight.KnightPerks;
import games.brennan.dungeontrain.perks.effects.lover.LoverPerkIds;
import games.brennan.dungeontrain.perks.effects.trader.ReducedReduction;
import games.brennan.dungeontrain.perks.effects.trader.RestockVillager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Which perks have effect code, and the stats that code shows. A perk in the tree but not built here
 * is "Coming soon": drawn, joined into paths, never bought. Each class declares its own perks (its
 * {@code declare()}), marking them built and registering their {@link PerkStats}.
 *
 * <p>{@link #LOCKED} perks stay Coming soon even when their code exists, until their design is settled.
 */
public final class BuiltPerks {

    /** Built, but held back as Coming soon (Brennan, 2026-10-27). */
    static final Set<String> LOCKED = Set.of("drift-chance", "own-carriage-return");

    private static final Set<String> BUILT = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean declared;

    private BuiltPerks() {}

    /** Called by a class's {@code declare()} for each perk it has effect code for. */
    public static void markBuilt(String... ids) {
        Collections.addAll(BUILT, ids);
    }

    /** True when the perk does something and is not held back. */
    public static boolean isBuilt(String id) {
        bootstrap();
        return id != null && BUILT.contains(id) && !LOCKED.contains(id);
    }

    /** Run every class's declaration once. */
    public static void bootstrap() {
        if (declared) return;
        synchronized (BuiltPerks.class) {
            if (declared) return;
            ArchivistPerks.declare();
            CrafterPerks.declare();
            KnightPerks.declare();
            LoverPerkIds.declare();
            ReducedReduction.declare();
            RestockVillager.declare();
            declared = true;
        }
    }
}
