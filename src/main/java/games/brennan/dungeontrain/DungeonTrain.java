package games.brennan.dungeontrain;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.DungeonTrainSettingsScreen;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
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

        ModLoadingContext.get().registerConfig(
                ModConfig.Type.SERVER,
                DungeonTrainConfig.SPEC,
                "dungeontrain-server.toml");

        MinecraftForge.EVENT_BUS.register(this);

        // Stage 1 diagnostic — raise the `games.brennan.dungeontrain.jitter`
        // namespace to DEBUG so the train-hop probes survive Forge's default
        // INFO root logger level. Remove once the root cause of the flatbed
        // hop is confirmed and the probes are demoted to TRACE.
        Configurator.setLevel("games.brennan.dungeontrain.jitter", Level.DEBUG);

        LOGGER.info("Dungeon Train constructor — mod loading");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Dungeon Train common setup");
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
