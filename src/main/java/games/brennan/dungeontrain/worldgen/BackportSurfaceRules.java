package games.brennan.dungeontrain.worldgen;

import com.blackgear.vanillabackport.common.worldgen.surface.ModSurfaceRuleData;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VanillaBackport's sulfur-cave surface rule (the sulfur / cinnabar bands through a sulfur cave's rock) on
 * the generators DT builds or registers.
 *
 * <p>Up to 1.1.x VanillaBackport baked the rule into every {@code NoiseGeneratorSettings} it saw constructed.
 * Since 1.2 it merges it in at surface-build time, and only when the generator's settings are vanilla's
 * {@code minecraft:overworld}. {@code mixin/vanillabackport/DtOverworldSulfurRuleMixin} applies the same
 * merge to {@link #needsSulfur the generators DT owns}: its {@code dungeontrain:overworld*} settings, and the
 * unregistered ({@code Holder.direct}) settings DT builds for the legacy bands, portal rooms and sample
 * generators. Without it the Nether-exit sulfur caves turn plain stone.</p>
 */
public final class BackportSurfaceRules {

    private static final String OVERWORLD_PREFIX = "overworld";
    /** A generator hands its settings' rule over by identity, so a handful of entries covers every live one. */
    private static final int CACHE_LIMIT = 64;

    private static final Map<IdentityKey, SurfaceRules.RuleSource> CACHE = new ConcurrentHashMap<>();

    private record IdentityKey(SurfaceRules.RuleSource rule) {
        @Override
        public boolean equals(Object o) {
            return o instanceof IdentityKey k && k.rule == rule;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(rule);
        }
    }

    private BackportSurfaceRules() {}

    /**
     * True for a generator whose surface DT merges the rule into: registered {@code dungeontrain:overworld*}
     * settings, or settings with no registry key at all (only DT builds those). Vanilla's own
     * {@code minecraft:overworld} is left to VanillaBackport, so nothing is merged twice.
     */
    public static boolean needsSulfur(Holder<NoiseGeneratorSettings> settings) {
        if (settings == null) return false;
        return settings.unwrapKey().map(BackportSurfaceRules::isDtOverworld).orElse(true);
    }

    /** True for {@code dungeontrain:overworld} and its {@code dungeontrain:overworld_y*} variants. */
    public static boolean isDtOverworld(ResourceKey<NoiseGeneratorSettings> key) {
        return key != null
                && DungeonTrain.MOD_ID.equals(key.location().getNamespace())
                && key.location().getPath().startsWith(OVERWORLD_PREFIX);
    }

    /** {@code rule} with VanillaBackport's sulfur-cave rules merged in, exactly as it does for vanilla's overworld. */
    public static SurfaceRules.RuleSource withSulfur(SurfaceRules.RuleSource rule) {
        IdentityKey key = new IdentityKey(rule);
        SurfaceRules.RuleSource cached = CACHE.get(key);
        if (cached != null) return cached;
        if (CACHE.size() >= CACHE_LIMIT) CACHE.clear();
        SurfaceRules.RuleSource merged = ModSurfaceRuleData.merge(rule, List.of(ModSurfaceRuleData.makeRules()));
        CACHE.put(key, merged);
        return merged;
    }
}
