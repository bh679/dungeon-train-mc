package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.NoiseGeneratorSettingsAccessor;
import games.brennan.dungeontrain.worldgen.BackportBiomes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Warns when VanillaBackport's sulfur-cave surface rule is missing from DT's overworld noise settings.
 *
 * <p>DT adds nothing for it: VanillaBackport injects its rule (the sulfur / cinnabar bands through a sulfur
 * cave's rock) at the tail of every {@code NoiseGeneratorSettings} constructor, so {@code dungeontrain:overworld}
 * and its {@code overworld_y*} variants carry it already. If a VanillaBackport update stops doing that, the
 * Nether-exit sulfur caves silently turn plain stone — this makes it visible at server start instead.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class VanillaBackportSurfaceRuleCheck {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceKey<NoiseGeneratorSettings> DT_OVERWORLD = ResourceKey.create(
            Registries.NOISE_SETTINGS, ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "overworld"));

    private VanillaBackportSurfaceRuleCheck() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            NoiseGeneratorSettings settings = event.getServer().registryAccess()
                    .registryOrThrow(Registries.NOISE_SETTINGS).get(DT_OVERWORLD);
            if (settings == null) return;                // not a DT datapack set — nothing to check
            SurfaceRules.RuleSource raw = ((NoiseGeneratorSettingsAccessor) (Object) settings).dungeontrain$rawSurfaceRule();
            if (!mentionsBiome(raw, BackportBiomes.SULFUR_CAVES)) {
                LOGGER.warn("[DungeonTrain] {}'s surface rule no longer mentions {} — VanillaBackport didn't inject its"
                        + " sulfur/cinnabar rule, so sulfur caves in Dungeon Train worlds will be plain stone",
                        DT_OVERWORLD.location(), BackportBiomes.SULFUR_CAVES.location());
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Couldn't check VanillaBackport's surface rule on {}", DT_OVERWORLD.location(), t);
        }
    }

    /**
     * True if {@code rule} names {@code biome} anywhere in its tree. Encoded through the rule codec — vanilla's
     * biome condition keeps its list private — reading whatever encodes even if one custom node can't.
     */
    public static boolean mentionsBiome(SurfaceRules.RuleSource rule, ResourceKey<Biome> biome) {
        if (rule == null) return false;
        String id = biome.location().toString();
        return SurfaceRules.RuleSource.CODEC.encodeStart(JsonOps.INSTANCE, rule)
                .resultOrPartial(error -> { })
                .map(json -> json.toString().contains(id))
                .orElse(false);
    }
}
