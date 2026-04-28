package games.brennan.dungeontrain;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.registry.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;

/**
 * Dungeon Train — Minecraft port of the itch.io game.
 * Entry point for the NeoForge mod.
 */
@Mod(DungeonTrain.MOD_ID)
public class DungeonTrain {
    public static final String MOD_ID = "dungeontrain";
    private static final Logger LOGGER = LogUtils.getLogger();

    public DungeonTrain(IEventBus modBus, ModContainer modContainer) {
        modBus.addListener(this::commonSetup);

        // First DeferredRegister in the project — wires the variant
        // clipboard item produced by the block-variant menu's Copy button.
        ModItems.register(modBus);

        modContainer.registerConfig(
                ModConfig.Type.SERVER,
                DungeonTrainConfig.SPEC,
                "dungeontrain-server.toml");

        // No NeoForge.EVENT_BUS.register(this) — every game-bus listener in
        // this mod lives in its own @EventBusSubscriber class (event/*.java,
        // editor/*.java, etc.) which the loader auto-registers. NeoForge
        // 1.21.1 rejects register(this) on classes with no @SubscribeEvent
        // methods, so calling it here would crash mod construction.

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
    }
}
