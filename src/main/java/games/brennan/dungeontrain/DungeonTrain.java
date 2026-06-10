package games.brennan.dungeontrain;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.registry.ModBlocks;
import games.brennan.dungeontrain.registry.ModCreativeTabs;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.registry.ModItems;
import games.brennan.dungeontrain.registry.ModMobEffects;
import games.brennan.dungeontrain.registry.ModSounds;
import games.brennan.dungeontrain.train.TrainMembership;
import games.brennan.dungeontrain.worldgen.feature.ModFeatures;
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
        // narrative_lectern + its BlockItem — first block-registry in the
        // project. NarrativeLecternHooks (mod-bus) attaches it to vanilla
        // BlockEntityType.LECTERN's valid blocks.
        ModBlocks.register(modBus);

        ModCreativeTabs.register(modBus);
        ModFeatures.register(modBus);
        ModMobEffects.register(modBus);
        ModSounds.register(modBus);

        // Global achievements (advancements) — custom criterion triggers
        // + per-player run-state attachment.
        ModAdvancementTriggers.register(modBus);
        ModDataAttachments.register(modBus);

        modContainer.registerConfig(
                ModConfig.Type.SERVER,
                DungeonTrainConfig.SPEC,
                "dungeontrain-server.toml");

        // Per-client display preferences (HUD / world-space text scale).
        // Lives at <minecraft>/config/dungeontrain-client.toml so it follows
        // the player across worlds rather than per-save like the server config.
        modContainer.registerConfig(
                ModConfig.Type.CLIENT,
                ClientDisplayConfig.SPEC,
                "dungeontrain-client.toml");

        // Common gameplay defaults readable on the title screen (no world) and
        // on a dedicated server. Holds the global DEFAULT PlayerMob spawn rate;
        // per-world overrides live in DungeonTrainWorldData (SavedData).
        modContainer.registerConfig(
                ModConfig.Type.COMMON,
                DungeonTrainCommonConfig.SPEC,
                "dungeontrain-common.toml");

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

        // Restrict Adventure Item Names' ambient mob naming (villagers + passive
        // animals) to entities on the train: off-train / vanilla-world mobs are
        // no longer auto-named while DT is installed. PlayerMob naming is left
        // untouched (it bypasses the gate inside AIN). AIN is always bundled via
        // jarJar, so NamingConfig is always present — no ModList.isLoaded guard
        // needed. The gate is a cheap tag-prefix scan (TrainMembership).
        NamingConfig.registerMobNameGate(TrainMembership::isOnTrain);
    }
}
