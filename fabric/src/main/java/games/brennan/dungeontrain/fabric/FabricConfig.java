package games.brennan.dungeontrain.fabric;

import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeConfigRegistry;
import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeModConfigEvents;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.platform.DtModId;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.neoforged.fml.config.ModConfig;

/**
 * Fabric config registration through Forge Config API Port (the loader-neutral
 * {@code net.neoforged.neoforge.common.ModConfigSpec} impl DT's config specs already
 * target). Mirrors the three {@code modContainer.registerConfig} calls +
 * the {@code ModConfigEvent} → {@code WorldGenCycle.invalidateCache} listener in the
 * NeoForge {@code DungeonTrain} constructor. FCAP's runtime is JiJ'd by Sable-Fabric.
 */
public final class FabricConfig {

    private FabricConfig() {}

    public static void register() {
        NeoForgeConfigRegistry.INSTANCE.register(DtModId.MOD_ID, ModConfig.Type.SERVER,
            DungeonTrainConfig.SPEC, "dungeontrain-server.toml");
        NeoForgeConfigRegistry.INSTANCE.register(DtModId.MOD_ID, ModConfig.Type.CLIENT,
            ClientDisplayConfig.SPEC, "dungeontrain-client.toml");
        NeoForgeConfigRegistry.INSTANCE.register(DtModId.MOD_ID, ModConfig.Type.COMMON,
            DungeonTrainCommonConfig.SPEC, "dungeontrain-common.toml");

        // Rebuild the memoised WorldGenCycle whenever the COMMON config (re)loads — the
        // Loading pass clears any pre-load default cycle, Reloading catches file-watcher /
        // config-screen edits (the NeoForge base ModConfigEvent listener caught both).
        NeoForgeModConfigEvents.loading(DtModId.MOD_ID).register(FabricConfig::onConfigChange);
        NeoForgeModConfigEvents.reloading(DtModId.MOD_ID).register(FabricConfig::onConfigChange);
    }

    private static void onConfigChange(ModConfig config) {
        if (config.getSpec() == DungeonTrainCommonConfig.SPEC) {
            WorldGenCycle.invalidateCache();
        }
    }
}
