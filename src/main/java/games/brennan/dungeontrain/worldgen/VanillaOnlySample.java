package games.brennan.dungeontrain.worldgen;

import net.minecraft.resources.ResourceLocation;

/**
 * What an offline sample meant to look like the <b>vanilla</b> Nether or End may place — only
 * {@code minecraft:} features and structures.
 *
 * <p>A vanilla-biome generator is not enough on its own. BetterEnd, through a WorldWeaver
 * {@code biome_modifications} file, adds its flavolite, thallasium and ender ores and its crashed ship to
 * {@code minecraft:end_barrens}/{@code end_midlands}/{@code end_highlands}, and BetterNether adds its ores
 * to the vanilla Nether biomes — so the live biome holders a sample decorates from carry them. And
 * structures like {@code betterend:eternal_portal} list the vanilla End biomes in their own biome tags, so
 * a structure picked by "does it admit this biome" can be a BetterEnd one. Both mods only <i>add</i> under
 * their own namespaces, which makes the namespace an exact test here.</p>
 *
 * <p>A Biomes O' Plenty End-band sample ({@link BopEnd}) is decorated under the same rule with one more
 * namespace let through ({@code biomesoplenty}), so its BoP biomes keep their own plants and trees while
 * BetterEnd's injected ores and stone stay out.</p>
 *
 * <p>The flag is thread-local for the same reason {@link OfflineChunkSampler}'s sampling flag is: a
 * decoration pass runs to completion on one worker thread, and the mixin that vetoes features reads it
 * mid-pass.</p>
 */
public final class VanillaOnlySample {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** One extra namespace this thread's sample may place, or {@code null}. */
    private static final ThreadLocal<String> ALSO = new ThreadLocal<>();

    private VanillaOnlySample() {}

    /**
     * True when {@code id} is vanilla's. An unregistered feature or structure (null) is refused: the rule
     * is "vanilla only", and a vanilla one always has a key.
     */
    public static boolean allows(ResourceLocation id) {
        return id != null && ResourceLocation.DEFAULT_NAMESPACE.equals(id.getNamespace());
    }

    /** {@link #allows}, or {@code id} is in the extra namespace this thread's sample admits. */
    public static boolean allowsHere(ResourceLocation id) {
        if (allows(id)) return true;
        String also = ALSO.get();
        return also != null && id != null && also.equals(id.getNamespace());
    }

    /** True while this thread decorates a vanilla-only sample. */
    public static boolean isActive() {
        return ACTIVE.get();
    }

    static void set(boolean active) {
        set(active, null);
    }

    static void set(boolean active, String alsoNamespace) {
        ACTIVE.set(active);
        if (active && alsoNamespace != null) ALSO.set(alsoNamespace);
        else ALSO.remove();
    }
}
