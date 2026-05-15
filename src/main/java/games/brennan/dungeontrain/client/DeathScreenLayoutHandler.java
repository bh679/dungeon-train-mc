package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.client.worldgen.PendingStartingDimension;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.StartingDimension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.WorldData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Rewrites the vanilla DeathScreen into three rows on singleplayer Dungeon
 * Train worlds:
 *
 * <pre>
 *   [        New World        ]   full width
 *   [ Respawn ][  Same World  ]   half width each
 *   [      Title Screen       ]   shifted down one row
 * </pre>
 *
 * <p>The Respawn button is wrapped in a {@link ConfirmScreen} warning the
 * player that respawn is currently buggy. "Continue anyway" performs the
 * vanilla respawn; "Okay" opens {@link ChooseWorldScreen} so the player can
 * pick between New World and Same World on a dedicated screen.</p>
 *
 * <p>New World creates a fresh save with a random seed; Same World reuses
 * the current world's seed. Both carry forward the current world's vanilla
 * settings (game mode, difficulty, hardcore, data packs) and Dungeon Train
 * options (trainY, startsWithTrain, carriage dims, generation mode, group
 * size, starting dimension) via {@link PendingWorldChoices}.</p>
 *
 * <p>Hardcore and LAN/multiplayer death screens are left untouched: vanilla
 * has no respawn button in hardcore, and we can't recreate a world we don't
 * own.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DeathScreenLayoutHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Component RESPAWN_KEY = Component.translatable("deathScreen.respawn");
    private static final Component TITLE_SCREEN_KEY = Component.translatable("deathScreen.titleScreen");

    private static final Component NEW_WORLD_LABEL = Component.translatable("gui.dungeontrain.death.new_world");
    private static final Component SAME_WORLD_LABEL = Component.translatable("gui.dungeontrain.death.same_world");

    private static final ResourceKey<WorldPreset> DT_OVERWORLD = preset("dungeon_train");
    private static final ResourceKey<WorldPreset> DT_NETHER = preset("dungeon_train_nether");
    private static final ResourceKey<WorldPreset> DT_END = preset("dungeon_train_end");

    private static final int GAP = 4;

    private DeathScreenLayoutHandler() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof DeathScreen deathScreen)) return;
        if (Minecraft.getInstance().getSingleplayerServer() == null) return;

        Button respawn = findButton(event, RESPAWN_KEY);
        Button title = findButton(event, TITLE_SCREEN_KEY);
        if (respawn == null || title == null) {
            LOGGER.info("DeathScreenLayout: respawn or title button missing on DeathScreen — skipping reshuffle.");
            return;
        }

        int slotX = respawn.getX();
        int slotY = respawn.getY();
        int slotW = respawn.getWidth();
        int slotH = respawn.getHeight();
        int halfW = (slotW - GAP) / 2;
        int rowSpacing = slotH + GAP;

        event.removeListener(respawn);
        title.setY(title.getY() + rowSpacing);

        Button wrappedRespawn = Button.builder(RESPAWN_KEY, b -> showRespawnConfirm(deathScreen))
                .bounds(slotX, slotY + rowSpacing, halfW, slotH)
                .build();
        event.addListener(wrappedRespawn);

        Button sameWorld = Button.builder(SAME_WORLD_LABEL, b -> launchWorld(deathScreen, true))
                .bounds(slotX + halfW + GAP, slotY + rowSpacing, halfW, slotH)
                .build();
        event.addListener(sameWorld);

        Button newWorld = Button.builder(NEW_WORLD_LABEL, b -> launchWorld(deathScreen, false))
                .bounds(slotX, slotY, slotW, slotH)
                .build();
        event.addListener(newWorld);
    }

    static void launchWorld(Screen lastScreen, boolean sameSeed) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            LOGGER.warn("DeathScreenLayout: launchWorld called with no singleplayer server");
            return;
        }

        WorldData worldData = server.getWorldData();
        LevelSettings cur = worldData.getLevelSettings();
        WorldOptions curOpts = worldData.worldGenOptions();

        StartingDimension startingDim = StartingDimension.OVERWORLD;
        ServerLevel overworld = server.overworld();
        if (overworld != null) {
            try {
                DungeonTrainWorldData dt = DungeonTrainWorldData.get(overworld);
                if (dt != null && dt.dims() != null) {
                    PendingWorldChoices.set(
                            dt.getTrainY(),
                            dt.startsWithTrain(),
                            dt.dims(),
                            DungeonTrainConfig.getGenerationMode(),
                            DungeonTrainConfig.getGroupSize());
                    startingDim = dt.startingDimension();
                }
            } catch (Exception e) {
                LOGGER.warn("DeathScreenLayout: failed to read DungeonTrainWorldData; new world will use mod defaults.", e);
            }
        }
        // Pin the starting dimension so WorldLifecycleEvents commits the
        // correct value into the new world's DungeonTrainWorldData on
        // ServerStartedEvent. Without this, the new world's PendingStartingDimension
        // would carry whatever the title-screen world picker left in it
        // (defaults to OVERWORLD), which would route a Nether/End DT player
        // back to the overworld on subsequent loads.
        PendingStartingDimension.set(startingDim);

        String name = "Dungeon Train " + System.currentTimeMillis();
        LevelSettings settings = new LevelSettings(
                name,
                cur.gameType(),
                cur.hardcore(),
                cur.difficulty(),
                cur.allowCommands(),
                new GameRules(),
                worldData.getDataConfiguration());

        long seed = sameSeed ? curOpts.seed() : WorldOptions.randomSeed();
        WorldOptions options = new WorldOptions(
                seed,
                curOpts.generateStructures(),
                curOpts.generateBonusChest());

        ResourceKey<WorldPreset> targetPreset = presetFor(startingDim);
        Function<RegistryAccess, WorldDimensions> dims = registryAccess -> {
            Registry<WorldPreset> presets = registryAccess.registryOrThrow(Registries.WORLD_PRESET);
            Optional<Holder.Reference<WorldPreset>> dt = presets.getHolder(targetPreset);
            if (dt.isPresent()) {
                return dt.get().value().createWorldDimensions();
            }
            LOGGER.warn("DeathScreenLayout: preset {} missing; falling back to NORMAL.", targetPreset.location());
            return WorldPresets.createNormalWorldDimensions(registryAccess);
        };

        LOGGER.info("DeathScreenLayout: launching {} world (seed={}, dim={}, preset={})",
                sameSeed ? "same-seed" : "fresh-seed", seed, startingDim, targetPreset.location());

        // Pre-drain Sable sub-levels on the server thread before disconnect.
        // ShipShutdownEvents does this during ServerStoppingEvent with a
        // 20-tick budget; that's not always enough on the
        // "freshly-created world → immediate disconnect" stress path —
        // ChunkMap.hasWork() stays true and vanilla's stopServer() wait loop
        // spins forever on "Saving worlds". Doing the drain here, BEFORE we
        // trigger the disconnect chain, gives the chunk map plenty of time
        // to flush before stopServer() runs. See ShipShutdownEvents.java
        // for the upstream Sable bug context.
        preDrainTrainSubLevels(server);

        // Tear down the running integrated server before loading the new
        // world — createFreshLevel from in-world otherwise stalls on a black
        // screen because the new server can't start while the old one still
        // holds state. Mirror the vanilla "Save and Quit to Title" sequence
        // from PauseScreen: ClientLevel.disconnect() sends the disconnect
        // packet that triggers the integrated server to halt; then
        // Minecraft.disconnect(Screen) synchronously waits for the
        // IntegratedServer tick loop to end. Calling Minecraft.disconnect
        // alone leaves the server running and the wait loop spins forever.
        if (mc.level != null) {
            mc.level.disconnect();
        }
        mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));

        WorldOpenFlows flows = mc.createWorldOpenFlows();
        flows.createFreshLevel(name, settings, options, dims, new TitleScreen());
    }

    private static void preDrainTrainSubLevels(MinecraftServer server) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        server.execute(() -> {
            try {
                for (ServerLevel level : server.getAllLevels()) {
                    Shipyard shipyard = Shipyards.of(level);
                    int deleted = 0;
                    for (ManagedShip ship : shipyard.findAll()) {
                        if (ship.getKinematicDriver() instanceof TrainTransformProvider) {
                            shipyard.delete(ship);
                            deleted++;
                        }
                    }
                    if (deleted == 0) continue;
                    ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                    if (container != null) {
                        for (int i = 0; i < 60; i++) {
                            container.tick();
                        }
                    }
                    LOGGER.info("DeathScreenLayout: pre-drained {} train sub-levels in {} (60 ticks)",
                            deleted, level.dimension().location());
                }
            } catch (Throwable t) {
                LOGGER.warn("DeathScreenLayout: pre-drain failed; falling back to vanilla shutdown path", t);
            } finally {
                done.complete(null);
            }
        });
        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("DeathScreenLayout: pre-drain wait timed out or errored", e);
        }
    }

    private static void showRespawnConfirm(Screen deathScreen) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new ConfirmScreen(
                proceed -> {
                    if (proceed) {
                        if (mc.player != null) mc.player.respawn();
                        mc.setScreen(null);
                    } else {
                        mc.setScreen(new ChooseWorldScreen(deathScreen));
                    }
                },
                Component.translatable("gui.dungeontrain.death.confirm_title"),
                Component.translatable("gui.dungeontrain.death.confirm_message"),
                Component.translatable("gui.dungeontrain.death.confirm_yes"),
                Component.translatable("gui.dungeontrain.death.confirm_no")));
    }

    private static Button findButton(ScreenEvent.Init.Post event, Component message) {
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof Button button && message.equals(button.getMessage())) {
                return button;
            }
        }
        return null;
    }

    private static ResourceKey<WorldPreset> presetFor(StartingDimension dim) {
        return switch (dim) {
            case NETHER -> DT_NETHER;
            case END -> DT_END;
            case OVERWORLD -> DT_OVERWORLD;
        };
    }

    private static ResourceKey<WorldPreset> preset(String path) {
        return ResourceKey.create(Registries.WORLD_PRESET,
                ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path));
    }
}
