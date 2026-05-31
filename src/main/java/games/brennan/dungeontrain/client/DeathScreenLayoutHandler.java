package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.DeathStatsPacket;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.client.worldgen.PendingStartingDimension;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.StartingDimension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
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
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
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
 * <p>Respawn fires {@code mc.player.respawn()} directly — server-side
 * {@code RespawnDimensionEvents} then rolls a starting dimension (1% End /
 * 5% Nether / 94% Overworld) and teleports the player into that dim with a
 * train. New World and Same World both run {@link #launchWorld} which rolls
 * the same dimension distribution at world-creation time via
 * {@link StartingDimension#rollRespawnDimension}.</p>
 *
 * <p>New World creates a fresh save with a random seed; Same World reuses
 * the current world's seed. Both carry forward the current world's vanilla
 * settings (game mode, difficulty, hardcore, data packs) and Dungeon Train
 * options (trainY, startsWithTrain, carriage dims, generation mode, group
 * size) via {@link PendingWorldChoices}; starting dimension is the rolled
 * value, not the previous world's.</p>
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

    /**
     * Compact 2-column stats panel that sits above the (vanilla-positioned)
     * button row, NOT pushing the buttons down. Three rows of stats
     * (6 stats in 2 columns) + one row of item icons (weapon + 4 armor
     * pieces). Total height plus padding stays under ~60 px so the panel
     * doesn't bump into the subtitle / death message on standard-height
     * windows.
     */
    private static final int STATS_LINE_HEIGHT = 11;
    private static final int STATS_ICON_SIZE = 16;
    private static final int STATS_INNER_PAD = 4;
    private static final int STATS_STAT_ROWS = 3;
    private static final int STATS_PANEL_HEIGHT =
            STATS_INNER_PAD
            + STATS_STAT_ROWS * STATS_LINE_HEIGHT
            + STATS_INNER_PAD
            + STATS_ICON_SIZE
            + STATS_INNER_PAD;

    /** Opaque background so vanilla subtitle/score text behind the panel is fully masked. */
    private static final int STATS_PANEL_BG = 0xF0101010;

    private static final Component STATS_MOBS = Component.translatable("gui.dungeontrain.death.stats.mobs");
    private static final Component STATS_CARTS = Component.translatable("gui.dungeontrain.death.stats.carts");
    private static final Component STATS_DISTANCE = Component.translatable("gui.dungeontrain.death.stats.distance");
    private static final Component STATS_TIME = Component.translatable("gui.dungeontrain.death.stats.time");
    private static final Component STATS_CONTAINERS = Component.translatable("gui.dungeontrain.death.stats.containers");
    private static final Component STATS_BOOKS = Component.translatable("gui.dungeontrain.death.stats.books");
    private static final Component STATS_WEAPON_LABEL = Component.translatable("gui.dungeontrain.death.stats.weapon");
    private static final Component STATS_ARMOR_LABEL = Component.translatable("gui.dungeontrain.death.stats.armor");

    /**
     * Set by {@link #onScreenInitPost} so the {@link #onScreenRenderPost}
     * hook knows where the stat panel should be drawn for THIS particular
     * DeathScreen instance. Cleared on screen close to avoid stale values
     * leaking to a non-death screen.
     */
    private static int statsPanelX = 0;
    private static int statsPanelY = 0;
    private static int statsPanelW = 0;
    private static boolean statsPanelActive = false;

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

        // Stat panel: centered horizontally, wider than the button column
        // so the 2-column layout breathes. Positioned strictly above the
        // (vanilla-positioned) button row — never overlapping. The panel
        // overlaps the vanilla "You Died!" subtitle (y=85) and "Score:"
        // line (y=100); an opaque background fill in onScreenRenderPost
        // masks them.
        //
        // On tiny windows / very high GUI scales the panel may climb up
        // into the title area or off the top of the screen — if there
        // isn't at least 60 px of headroom above the buttons we just skip
        // the panel rather than overlap controls.
        int screenW = deathScreen.width;
        statsPanelW = Math.min(360, screenW - 40);
        statsPanelX = (screenW - statsPanelW) / 2;
        statsPanelY = slotY - GAP - STATS_PANEL_HEIGHT;
        statsPanelActive = statsPanelY >= 60;

        // Replace the vanilla Title Screen button with one that exits
        // directly — vanilla wraps it in a "Are you sure you want to give
        // up?" confirm dialog which is redundant for this mod (the
        // death screen already shows four distinct exit paths).
        int titleX = title.getX();
        int titleY = title.getY() + rowSpacing;
        int titleW = title.getWidth();
        int titleH = title.getHeight();
        event.removeListener(respawn);
        event.removeListener(title);

        // Respawn now routes through RespawnDimensionEvents server-side: 94%
        // overworld (vanilla flow), 5% Nether, 1% End — each with a train.
        // No confirm screen any more; the prior "respawn is buggy" warning
        // was a no-op placeholder for a behaviour the random-dim handler now
        // actually defines.
        Button wrappedRespawn = Button.builder(RESPAWN_KEY, b -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) mc.player.respawn();
                    mc.setScreen(null);
                })
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

        Button newTitle = Button.builder(TITLE_SCREEN_KEY, b -> goToTitleScreen())
                .bounds(titleX, titleY, titleW, titleH)
                .build();
        event.addListener(newTitle);
    }

    /**
     * Draw the compact 2-column stats panel + item row just above the
     * (vanilla-positioned) buttons. Only runs after {@link #onScreenInitPost}
     * set the {@code statsPanelActive} flag, and only when a
     * {@link DeathStatsPacket} has been received for the current death.
     * Without a packet we render nothing.
     */
    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!statsPanelActive) return;
        if (!(event.getScreen() instanceof DeathScreen)) return;
        DeathStatsPacket stats = DeathStatsCache.get();
        if (stats == null) return;

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        int mouseX = event.getMouseX();
        int mouseY = event.getMouseY();

        // Opaque background panel to fully mask the vanilla subtitle /
        // "Score:" line that may sit underneath at small screen heights.
        // Drawn after vanilla, so it covers cleanly.
        graphics.fill(statsPanelX, statsPanelY,
                statsPanelX + statsPanelW, statsPanelY + STATS_PANEL_HEIGHT,
                STATS_PANEL_BG);

        int labelColor = 0xBBBBBB;
        int valueColor = 0xFFFFFF;
        int colWidth = (statsPanelW - GAP) / 2;
        int leftX = statsPanelX;
        int rightX = statsPanelX + colWidth + GAP;
        int rowY = statsPanelY + STATS_INNER_PAD;

        // Layout: 3 rows × 2 cols of stats.
        drawStatCell(graphics, font, STATS_MOBS, String.valueOf(stats.mobKills()),
                leftX, rowY, colWidth, labelColor, valueColor);
        drawStatCell(graphics, font, STATS_TIME, formatTime(stats.runTicks()),
                rightX, rowY, colWidth, labelColor, valueColor);
        rowY += STATS_LINE_HEIGHT;
        drawStatCell(graphics, font, STATS_CARTS, String.valueOf(stats.cartsTravelled()),
                leftX, rowY, colWidth, labelColor, valueColor);
        drawStatCell(graphics, font, STATS_CONTAINERS, String.valueOf(stats.containersOpened()),
                rightX, rowY, colWidth, labelColor, valueColor);
        rowY += STATS_LINE_HEIGHT;
        drawStatCell(graphics, font, STATS_DISTANCE, formatDistance(stats.distanceBlocks()),
                leftX, rowY, colWidth, labelColor, valueColor);
        drawStatCell(graphics, font, STATS_BOOKS, String.valueOf(stats.booksRead()),
                rightX, rowY, colWidth, labelColor, valueColor);
        rowY += STATS_LINE_HEIGHT + STATS_INNER_PAD;

        // Item row: weapon (labeled) on the left, four armor pieces on
        // the right (also labeled). Each slot is rendered with renderItem;
        // hover detection uses mouse coordinates so tooltips appear per
        // icon individually.
        int textBaseline = rowY + (STATS_ICON_SIZE - font.lineHeight) / 2 + 1;
        int weaponLabelW = font.width(STATS_WEAPON_LABEL);
        graphics.drawString(font, STATS_WEAPON_LABEL, leftX, textBaseline, labelColor, false);
        int weaponX = leftX + weaponLabelW + GAP;
        renderItemSlot(graphics, stats.mostUsedWeapon(), weaponX, rowY, mouseX, mouseY);

        int armorRightEdge = statsPanelX + statsPanelW;
        int armorRow = 4 * STATS_ICON_SIZE + 3 * 2;
        int armorBaseX = armorRightEdge - armorRow;
        int armorLabelW = font.width(STATS_ARMOR_LABEL);
        graphics.drawString(font, STATS_ARMOR_LABEL, armorBaseX - GAP - armorLabelW, textBaseline, labelColor, false);
        ItemStack[] armorStacks = { stats.armorHead(), stats.armorChest(), stats.armorLegs(), stats.armorFeet() };
        for (int i = 0; i < 4; i++) {
            int slotX = armorBaseX + i * (STATS_ICON_SIZE + 2);
            renderItemSlot(graphics, armorStacks[i], slotX, rowY, mouseX, mouseY);
        }
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof DeathScreen) {
            statsPanelActive = false;
        }
    }

    /**
     * Draw one stat cell as {@code label .... value} inside a column of
     * {@code width} pixels. Label left-aligned, value right-aligned. Skips
     * the row if the stat is zero/empty would be nicer, but we always show
     * all six for layout stability.
     */
    private static void drawStatCell(GuiGraphics graphics, Font font,
                                     Component label, String value,
                                     int x, int y, int width,
                                     int labelColor, int valueColor) {
        graphics.drawString(font, label, x, y, labelColor, false);
        int valueWidth = font.width(value);
        graphics.drawString(font, value, x + width - valueWidth, y, valueColor, false);
    }

    /**
     * Render a single item slot + tooltip on hover. Empty stacks render as
     * a faint placeholder box so the layout doesn't shift when a slot is
     * missing.
     */
    private static void renderItemSlot(GuiGraphics graphics, ItemStack stack,
                                       int x, int y, int mouseX, int mouseY) {
        if (stack.isEmpty()) {
            // Faint dashed-outline box for empty slots — communicates "no
            // gear" without going noisy on the screen.
            graphics.fill(x, y, x + STATS_ICON_SIZE, y + STATS_ICON_SIZE, 0x40FFFFFF);
            return;
        }
        graphics.renderItem(stack, x, y);
        graphics.renderItemDecorations(Minecraft.getInstance().font, stack, x, y);
        if (mouseX >= x && mouseX < x + STATS_ICON_SIZE
                && mouseY >= y && mouseY < y + STATS_ICON_SIZE) {
            graphics.renderTooltip(Minecraft.getInstance().font,
                    Screen.getTooltipFromItem(Minecraft.getInstance(), stack),
                    stack.getTooltipImage(), mouseX, mouseY);
        }
    }

    private static String formatDistance(double blocks) {
        return String.format("%,.0f m", blocks);
    }

    private static String formatTime(long ticks) {
        long totalSeconds = ticks / 20L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    public static void launchWorld(Screen lastScreen, boolean sameSeed) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            LOGGER.warn("DeathScreenLayout: launchWorld called with no singleplayer server");
            return;
        }

        WorldData worldData = server.getWorldData();
        LevelSettings cur = worldData.getLevelSettings();
        WorldOptions curOpts = worldData.worldGenOptions();

        StartingDimension startingDim = StartingDimension.rollRespawnDimension(
                RandomSource.create().nextDouble());
        LOGGER.info("DeathScreenLayout: respawn rolled startingDimension={}", startingDim);
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
                    // Pre-drain only runs when we actually deleted ships on
                    // this level — its purpose is to clean up after the
                    // deletes here, BEFORE the disconnect chain starts.
                    // Levels with no DT ships are skipped; ShipShutdownEvents
                    // will run its own short safety drain at ServerStopping
                    // for any leftover Sable chunk-map state.
                    //
                    // Wall-clock-bound: {@code container.tick()} is a
                    // synchronous queue-pump that returns instantly when
                    // nothing's queued, but Sable's chunk-map cleanup runs
                    // on OTHER threads. Looping with {@code Thread.yield()}
                    // gives those threads CPU time.
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
     * Exit to the title screen directly, without vanilla's "Are you sure
     * you want to give up?" ConfirmScreen. In singleplayer this still has
     * to tear down the integrated server with the Sable sub-level
     * pre-drain — otherwise the chunk map's stopServer wait loop spins
     * forever (see {@link #preDrainTrainSubLevels} and the save-and-quit
     * hang memory in this project).
     */
    public static void goToTitleScreen() {
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
