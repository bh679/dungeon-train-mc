package games.brennan.dungeontrain.event;

import com.blackgear.vanillabackport.common.worldgen.surface.ModSurfaceRuleData;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.BackportBiomes;
import games.brennan.dungeontrain.worldgen.BackportSurfaceRules;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Warns when VanillaBackport's sulfur-cave surface rule would be missing from DT's overworld surface.
 *
 * <p>Since VanillaBackport 1.2 the rule (the sulfur / cinnabar bands through a sulfur cave's rock) is merged in
 * at surface-build time, for vanilla's overworld only; {@code mixin/vanillabackport/DtOverworldSulfurRuleMixin}
 * applies the same merge to {@code dungeontrain:overworld} and its {@code overworld_y*} variants
 * ({@link BackportSurfaceRules}). This checks that VanillaBackport's rule still names the sulfur-caves biome
 * and that DT's live overworld generator is one that mixin merges it into. If a
 * VanillaBackport update moves the rule elsewhere, the Nether-exit sulfur caves silently turn plain stone —
 * this makes it visible at server start instead.</p>
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
            // VanillaBackport's side: its rule data still names the sulfur-caves biome.
            if (!mentionsBiome(ModSurfaceRuleData.makeRules(), BackportBiomes.SULFUR_CAVES)) {
                LOGGER.warn("[DungeonTrain] VanillaBackport's surface rules no longer mention {}, so sulfur caves in"
                        + " Dungeon Train worlds will be plain stone", BackportBiomes.SULFUR_CAVES.location());
                return;
            }
            // DT's side: the live overworld generator is one DtOverworldSulfurRuleMixin merges the rule into.
            if (event.getServer().overworld().getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator gen
                    && gen.generatorSettings().unwrapKey().map(BackportSurfaceRules::isDtOverworld).orElse(false)
                    && !BackportSurfaceRules.needsSulfur(gen.generatorSettings())) {
                LOGGER.warn("[DungeonTrain] {} no longer takes VanillaBackport's sulfur rule, so sulfur caves in"
                        + " Dungeon Train worlds will be plain stone", gen.generatorSettings().unwrapKey().get().location());
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
