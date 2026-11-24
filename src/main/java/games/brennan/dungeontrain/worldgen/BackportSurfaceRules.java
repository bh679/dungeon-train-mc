package games.brennan.dungeontrain.worldgen;

import com.blackgear.vanillabackport.common.worldgen.surface.ModSurfaceRuleData;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.List;

/**
 * VanillaBackport's sulfur-cave surface rule (the sulfur / cinnabar bands through a sulfur cave's rock) on
 * DT's overworld noise settings.
 *
 * <p>Since VanillaBackport 1.2 the rule is merged in at surface-build time, and only when the generator's
 * settings are vanilla's {@code minecraft:overworld}. DT's worlds generate with {@code dungeontrain:overworld}
 * and its {@code overworld_y*} variants, so {@code mixin/vanillabackport/DtOverworldSulfurRuleMixin} applies
 * the same merge for those: without it the Nether-exit sulfur caves turn plain stone.</p>
 */
public final class BackportSurfaceRules {

    private static final String OVERWORLD_PREFIX = "overworld";

    /** Last input rule and its merged result: the generator passes the same rule object for every chunk. */
    private static volatile Cached cache;

    private record Cached(SurfaceRules.RuleSource in, SurfaceRules.RuleSource out) {}

    private BackportSurfaceRules() {}

    /** True for {@code dungeontrain:overworld} and its {@code dungeontrain:overworld_y*} variants. */
    public static boolean isDtOverworld(ResourceKey<NoiseGeneratorSettings> key) {
        return key != null
                && DungeonTrain.MOD_ID.equals(key.location().getNamespace())
                && key.location().getPath().startsWith(OVERWORLD_PREFIX);
    }

    /** {@code rule} with VanillaBackport's sulfur-cave rules merged in, exactly as it does for vanilla's overworld. */
    public static SurfaceRules.RuleSource withSulfur(SurfaceRules.RuleSource rule) {
        Cached c = cache;
        if (c != null && c.in() == rule) return c.out();
        SurfaceRules.RuleSource merged = ModSurfaceRuleData.merge(rule, List.of(ModSurfaceRuleData.makeRules()));
        cache = new Cached(rule, merged);
        return merged;
    }
}
