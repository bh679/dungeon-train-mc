package games.brennan.dungeontrain;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.DungeonTrainSettingsScreen;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.registry.ModCreativeTabs;
import games.brennan.dungeontrain.registry.ModItems;
import games.brennan.dungeontrain.worldgen.feature.ModFeatures;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;

/**
 * Dungeon Train — Minecraft port of the itch.io game.
 * Entry point for the Forge mod.
 */
@Mod(DungeonTrain.MOD_ID)
public class DungeonTrain {
    public static final String MOD_ID = "dungeontrain";
    private static final Logger LOGGER = LogUtils.getLogger();

    public DungeonTrain() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        modBus.addListener(this::commonSetup);
        modBus.addListener(this::clientSetup);

        // First DeferredRegister in the project — wires the variant
        // clipboard item produced by the block-variant menu's Copy button.
        ModItems.register(modBus);

        // Custom creative tabs for the left-side prefab browser. Items are
        // populated dynamically from PrefabTabState on each tryRebuildTabContents
        // — the login sync packet triggers a rebuild once the registry is
        // populated.
        ModCreativeTabs.register(modBus);

        // Worldgen-feature DeferredRegister: TrackBedFeature paints bed +
        // rails into chunks at generation time so tracks become a property
        // of the chunk save (zero post-load cost). Datapack JSONs in
        // data/dungeontrain/{worldgen,forge} wire it through three biome
        // modifiers (overworld / nether / end).
        ModFeatures.register(modBus);

        ModLoadingContext.get().registerConfig(
                ModConfig.Type.SERVER,
                DungeonTrainConfig.SPEC,
                "dungeontrain-server.toml");

        MinecraftForge.EVENT_BUS.register(this);

        // Keeps the `games.brennan.dungeontrain.jitter` namespace at DEBUG
        // so the [baseline] capture line (spawn), [tripwire] WARN (large
        // physics-tick deltas — should never fire in normal play), and the
        // stuck-player diagnostics ([stuck.pIdx], [stuck.window],
        // [stuck.frozen], [panic.canonicalPos]) added by
        // plans/linear-marinating-yao.md stay visible without Forge-wide
        // DEBUG.
        //
        // The chatty per-tick probes ([physics], [pivotMoved], [pIdx],
        // [windowManager], [client]) log at TRACE — set
        // `-Dforge.logging.console.level=trace` or bump this line to
        // {@link Level#TRACE} to re-enable them when diagnosing a
        // regression of the train-hop fix.
        Configurator.setLevel("games.brennan.dungeontrain.jitter", Level.DEBUG);

        LOGGER.info("Dungeon Train constructor — mod loading");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Dungeon Train common setup");
        event.enqueueWork(DungeonTrainNet::register);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("Dungeon Train client setup");
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            ModLoadingContext.get().registerExtensionPoint(
                    ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory(
                            (mc, parent) -> new DungeonTrainSettingsScreen(parent)));
        });
    }
}
