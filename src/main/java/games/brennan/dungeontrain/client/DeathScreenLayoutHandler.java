package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.player.PendingInventory;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.client.worldgen.PendingStartingDimension;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.StartingDimension;
import net.minecraft.client.Minecraft;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Replaces the vanilla {@link DeathScreen} with {@link NarrativeDeathScreen} —
 * the paginated "the Dungeon Train asks" recap — on singleplayer Dungeon Train
 * worlds. In Dungeon Train, dying ends the run: the narrative screen offers
 * "Board anew" (a fresh world) and "Leave the line" (the title screen), with no
 * respawn-in-place, so hardcore worlds get the same treatment.
 *
 * <p>This class also owns the world-transition plumbing the narrative screen
 * calls: {@link #launchWorld} creates a fresh save (carrying forward the current
 * world's vanilla + Dungeon Train settings via {@code PendingWorldChoices}; the
 * new save always starts in the Overworld), and {@link #goToTitleScreen} exits
 * to the title. Both run the Sable sub-level pre-drain so the integrated server
 * tears down cleanly (see {@link #preDrainTrainSubLevels}).</p>
 *
 * <p>LAN / dedicated-server death screens are left untouched — we can't recreate
 * a world we don't own; the singleplayer guard ({@code getSingleplayerServer()})
 * gates the swap.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DeathScreenLayoutHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Name prefix stamped on every auto-generated run world. Doubles as the
     * safety gate for reboard deletion — only folders carrying it are ever
     * auto-deleted (see {@link #deletableLevelId}).
     */
    private static final String WORLD_NAME_PREFIX = "Dungeon Train ";

    private static final ResourceKey<WorldPreset> DT_OVERWORLD = preset("dungeon_train");
    private static final ResourceKey<WorldPreset> DT_OVERWORLD_COMPAT = preset("dungeon_train_compat");
    private static final ResourceKey<WorldPreset> DT_NETHER = preset("dungeon_train_nether");
    private static final ResourceKey<WorldPreset> DT_END = preset("dungeon_train_end");

    private DeathScreenLayoutHandler() {}

    /**
     * Swap the vanilla death screen for the narrative one as it opens. Guarded
     * to singleplayer (integrated server present). The new screen is not a
     * {@link DeathScreen}, so the re-fired {@code Opening} event for it is a
     * no-op — no recursion.
     */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof DeathScreen)) return;
        if (Minecraft.getInstance().getSingleplayerServer() == null) return;
        event.setNewScreen(new NarrativeDeathScreen());
    }

    public static void launchWorld(Screen lastScreen, boolean sameSeed) {
        launchWorld(lastScreen, sameSeed, false);
    }

    public static void launchWorld(Screen lastScreen, boolean sameSeed, boolean forceSurvival) {
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
                }
            } catch (Exception e) {
                LOGGER.warn("DeathScreenLayout: failed to read DungeonTrainWorldData; new world will use mod defaults.", e);
            }
        }
        // Pin the starting dimension so WorldLifecycleEvents commits the
        // correct value into the new world's DungeonTrainWorldData on
        // ServerStartedEvent.
        PendingStartingDimension.set(startingDim);

        // When the current world has keepInventory on, snapshot the player's
        // inventory + XP (consumed on the next world's first login by
        // KeepInventoryCarryEvents) and create the next world with keepInventory
        // on too.
        boolean keepInventory = captureKeepInventory(server);

        String name = WORLD_NAME_PREFIX + System.currentTimeMillis();
        GameRules gameRules = new GameRules();
        if (keepInventory) {
            gameRules.getRule(GameRules.RULE_KEEPINVENTORY).set(true, null);
        }
        GameType gameType = forceSurvival ? GameType.SURVIVAL : cur.gameType();
        LevelSettings settings = new LevelSettings(
                name,
                gameType,
                cur.hardcore(),
                cur.difficulty(),
                cur.allowCommands(),
                gameRules,
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

        // Dungeon Train is a new-world-per-run game, so by default the dying
        // world's save is not worth keeping: skip its chunk save and delete the
        // folder after teardown. Double-gated — the client toggle (trash chip on
        // the death screen) AND the auto-generated name prefix; a renamed or
        // hand-made world is never auto-deleted.
        String oldLevelId = deletableLevelId(server);
        if (oldLevelId != null) {
            suppressLevelSaving(server);
        }

        // Pre-drain Sable sub-levels on the server thread before disconnect, so
        // ChunkMap.hasWork() doesn't spin vanilla's stopServer() wait loop
        // forever on the "freshly-created world → immediate disconnect" path.
        preDrainTrainSubLevels(server);

        // Tear down the running integrated server before loading the new world —
        // mirror the vanilla "Save and Quit to Title" sequence so the
        // IntegratedServer tick loop ends before createFreshLevel starts.
        if (mc.level != null) {
            mc.level.disconnect();
        }
        // When the old world is being discarded the vanilla "Saving world" label
        // would be a lie — show the discard message instead.
        mc.disconnect(new GenericMessageScreen(Component.translatable(
                oldLevelId != null ? "gui.dungeontrain.death.discarding_world" : "menu.savingLevel")));

        // disconnect() has fully halted the integrated server and released its
        // session lock, so the old save folder can be deleted before the new
        // world spins up. A failed delete only logs — never blocks the reboard.
        if (oldLevelId != null) {
            deleteOldWorld(mc, oldLevelId);
        }

        WorldOpenFlows flows = mc.createWorldOpenFlows();
        flows.createFreshLevel(name, settings, options, dims, new TitleScreen());
    }

    /**
     * The save-folder name of the current singleplayer world if — and only if —
     * it is safe to auto-delete on reboard: the client toggle is on AND the
     * folder carries the auto-generated {@code "Dungeon Train "} prefix that
     * {@link #launchWorld} itself stamps on every fresh run. Returns {@code null}
     * when the world must be kept (toggle off, renamed/hand-made world, or the
     * folder name couldn't be read), in which case the normal save path runs.
     */
    private static String deletableLevelId(MinecraftServer server) {
        if (!ClientDisplayConfig.isDeleteWorldOnReboard()) return null;
        try {
            String levelId = server.getWorldPath(LevelResource.ROOT)
                    .normalize().getFileName().toString();
            if (!levelId.startsWith(WORLD_NAME_PREFIX)) {
                LOGGER.info("DeathScreenLayout: world '{}' is not an auto-generated Dungeon Train save; keeping it.", levelId);
                return null;
            }
            return levelId;
        } catch (Exception e) {
            LOGGER.warn("DeathScreenLayout: could not resolve current save folder; world will be kept.", e);
            return null;
        }
    }

    /**
     * Flip {@code noSave} on every {@link ServerLevel} (on the server thread) so
     * the imminent shutdown skips the chunk save — pure teardown cost saved on a
     * world whose folder is deleted right after. Metadata (level.dat, playerdata)
     * still writes; harmless, the whole folder goes next. Best-effort: on timeout
     * the world simply saves normally before deletion.
     */
    private static void suppressLevelSaving(MinecraftServer server) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        server.execute(() -> {
            try {
                for (ServerLevel level : server.getAllLevels()) {
                    level.noSave = true;
                }
            } finally {
                done.complete(null);
            }
        });
        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("DeathScreenLayout: noSave flag wait timed out; old world will save before deletion", e);
        }
    }

    /**
     * Delete the old (already shut down) world's save folder via the vanilla
     * world-list deletion path. Any failure — lingering session lock, IO error,
     * content validation — is logged and swallowed so world creation proceeds.
     */
    private static void deleteOldWorld(Minecraft mc, String levelId) {
        try (LevelStorageSource.LevelStorageAccess access = mc.getLevelSource().createAccess(levelId)) {
            access.deleteLevel();
            LOGGER.info("DeathScreenLayout: deleted old world save '{}'", levelId);
        } catch (Exception e) {
            LOGGER.warn("DeathScreenLayout: failed to delete old world save '{}'; it will remain in the world list.", levelId, e);
        }
    }

    /**
     * Read the current world's {@code keepInventory} gamerule and, when on,
     * snapshot the local player's inventory + experience into
     * {@link PendingInventory} so {@link games.brennan.dungeontrain.event.KeepInventoryCarryEvents}
     * can re-apply it on the next world's first login. Returns the gamerule
     * value so the caller can mirror it onto the new world's {@link GameRules}.
     */
    private static boolean captureKeepInventory(MinecraftServer server) {
        UUID localId = Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getUUID() : null;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                boolean keep = server.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
                if (keep && localId != null) {
                    ServerPlayer player = server.getPlayerList().getPlayer(localId);
                    if (player != null) {
                        PendingInventory.capture(player);
                    } else {
                        LOGGER.warn("DeathScreenLayout: keepInventory on but server player {} not found; carrying nothing", localId);
                        PendingInventory.clear();
                    }
                } else {
                    PendingInventory.clear();
                }
                result.complete(keep);
            } catch (Throwable t) {
                LOGGER.warn("DeathScreenLayout: keepInventory capture failed; new world will not carry inventory", t);
                PendingInventory.clear();
                result.complete(false);
            }
        });
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("DeathScreenLayout: keepInventory capture wait timed out or errored", e);
            return false;
        }
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
                    ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                    if (container != null && deleted > 0) {
                        long deadline = System.nanoTime() + 1_000_000_000L; // 1 s
                        int ticks = 0;
                        while (System.nanoTime() < deadline) {
                            container.tick();
                            ticks++;
                            Thread.yield();
                        }
                        LOGGER.info("DeathScreenLayout: pre-drained {} train sub-levels in {} ({} ticks, ~1s wall)",
                                deleted, level.dimension().location(), ticks);
                    }
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

    /**
     * Exit to the title screen directly, without vanilla's "Are you sure you
     * want to give up?" ConfirmScreen. In singleplayer this still tears down the
     * integrated server with the Sable sub-level pre-drain — otherwise the chunk
     * map's stopServer wait loop spins forever (see {@link #preDrainTrainSubLevels}).
     */
    public static void goToTitleScreen() {
        // The title path is not a "next world" transition — drop any snapshot a
        // prior (failed/abandoned) launchWorld may have left so it can't leak
        // into the next world the player loads from the title screen.
        PendingInventory.clear();
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            preDrainTrainSubLevels(server);
        }
        if (mc.level != null) {
            mc.level.disconnect();
        }
        mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        mc.setScreen(new TitleScreen());
    }

    /**
     * Save the current world and quit Minecraft to the desktop. Mirrors
     * {@link #goToTitleScreen()} — same Sable sub-level pre-drain + integrated-server
     * teardown so shutdown doesn't hang on the chunk-map wait loop — but ends in
     * {@link Minecraft#stop()} instead of returning to the title screen. Called by
     * the pause menu's Shift-revealed "Quit Game" button. After {@code disconnect}
     * the level and integrated server are gone, so {@code stop()} runs the same
     * orderly shutdown as the title-screen Quit button.
     */
    public static void quitToDesktop() {
        PendingInventory.clear();
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            preDrainTrainSubLevels(server);
        }
        if (mc.level != null) {
            mc.level.disconnect();
        }
        mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        mc.stop();
    }

    private static ResourceKey<WorldPreset> presetFor(StartingDimension dim) {
        return switch (dim) {
            case NETHER -> DT_NETHER;
            case END -> DT_END;
            case OVERWORLD -> DungeonTrainCommonConfig.getDefaultCompatibleTerrain()
                    ? DT_OVERWORLD_COMPAT : DT_OVERWORLD;
        };
    }

    private static ResourceKey<WorldPreset> preset(String path) {
        return ResourceKey.create(Registries.WORLD_PRESET,
                ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path));
    }
}
