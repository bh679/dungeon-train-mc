package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.NoiseGeneratorSettingsAccessor;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * Recognises the surface rule of one of DT's own {@code dungeontrain:} noise settings, so
 * {@link games.brennan.dungeontrain.mixin.terrablender.SurfaceRuleManagerMixin} can keep it in charge
 * of vanilla biomes.
 *
 * <p><b>Why it matters.</b> TerraBlender replaces {@code NoiseGeneratorSettings.surfaceRule()} for every
 * dimension type in {@code #terrablender:overworld_regions} and maps the {@code minecraft} biome
 * namespace to its OWN hard-coded copy of vanilla's rule — the settings' rule only runs as a fallback.
 * DT's rule differs from vanilla in one value, the {@code bedrock_floor} gradient ({@code above_bottom: 1}
 * instead of 5), so without this the 1-thick floor gets vanilla's random bedrock specks up to floor+4.</p>
 *
 * <p>Identity, not equality: the rule TerraBlender passes in is the very object held by the registry
 * entry. TerraBlender caches its result per settings instance, so this runs once per settings per world.</p>
 */
public final class DtSurfaceRules {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DtSurfaceRules() {}

    /** The {@code dungeontrain:} noise settings whose raw surface rule is {@code rule}, if any. */
    public static Optional<ResourceKey<NoiseGeneratorSettings>> ownerOf(SurfaceRules.RuleSource rule) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (rule == null || server == null) return Optional.empty();
        for (Map.Entry<ResourceKey<NoiseGeneratorSettings>, NoiseGeneratorSettings> e
                : server.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).entrySet()) {
            if (!DungeonTrain.MOD_ID.equals(e.getKey().location().getNamespace())) continue;
            if (((NoiseGeneratorSettingsAccessor) (Object) e.getValue()).dungeontrain$rawSurfaceRule() == rule) {
                LOGGER.info("[DungeonTrain] Surface rules: {} keeps its own rule for vanilla biomes"
                        + " (TerraBlender namespace rules still apply to modded biomes)", e.getKey().location());
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }
}
