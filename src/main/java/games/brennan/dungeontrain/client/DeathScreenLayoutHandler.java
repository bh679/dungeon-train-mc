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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Rewrites the vanilla DeathScreen into a uniform four-button column with a
 * single-column run-stats panel and a centred equipment row above it:
 *
 * <pre>
 *   ┌──────────────────────┐
 *   │ Mobs killed       0  │
 *   │ Time            0:00 │
 *   │ Carts travelled   0  │
 *   │ Loot containers   0  │
 *   │ Distance        0 m  │
 *   │ Books read        0  │
 *   │                      │
 *   │   [🪖][⛓][👖][🥾] [⚔] │
 *   └──────────────────────┘
 *   [      New World      ]
 *   [      Same World     ]
 *   [      Respawn        ]
 *   [      Title Screen   ]
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
 * <p>The vanilla "Score: 0" line and the subtitle ("X was killed by Y") are
 * relocated by {@code DeathScreenMoveTextMixin} — subtitle drops to y=78 so
 * it sits cleanly below the 2× scaled "You Died!" title, and score moves
 * off-screen since Dungeon Train doesn't track XP-based score.</p>
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
     * Single-column stats panel that sits above the (vanilla-positioned)
     * button row, NOT pushing the buttons down. Six rows of stats (one
     * stat per row, label left + value right) followed by a centred row of
     * equipment icons (helmet, chest, legs, boots, weapon). Empty
     * equipment slots are skipped at render time — the remaining icons
     * reflow tightly toward the centre so a player who died with only a
     * sword doesn't see four ghost squares.
     */
    private static final int STATS_LINE_HEIGHT = 11;
    private static final int STATS_ICON_SIZE = 16;
    private static final int STATS_ICON_GAP = 2;
    private static final int STATS_ARMOR_WEAPON_SEPARATOR = 8;
    private static final int STATS_INNER_PAD = 6;
    private static final int STATS_STAT_ROWS = 6;
    private static final int STATS_PANEL_HEIGHT =
            STATS_INNER_PAD
            + STATS_STAT_ROWS * STATS_LINE_HEIGHT
            + STATS_INNER_PAD
            + STATS_ICON_SIZE
            + STATS_INNER_PAD;

    /**
     * Vertical clearance reserved above the panel so it doesn't collide
     * with the 2× scaled "You Died!" title, which spans roughly y=30–70.
     */
    private static final int TITLE_CLEARANCE = 12;

    /** Opaque background so anything vanilla draws behind the panel is fully masked. */
    private static final int STATS_PANEL_BG = 0xF0101010;

    private static final Component STATS_MOBS = Component.translatable("gui.dungeontrain.death.stats.mobs");
    private static final Component STATS_CARTS = Component.translatable("gui.dungeontrain.death.stats.carts");
    private static final Component STATS_DISTANCE = Component.translatable("gui.dungeontrain.death.stats.distance");
    private static final Component STATS_TIME = Component.translatable("gui.dungeontrain.death.stats.time");
    private static final Component STATS_CONTAINERS = Component.translatable("gui.dungeontrain.death.stats.containers");
    private static final Component STATS_BOOKS = Component.translatable("gui.dungeontrain.death.stats.books");

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
        int rowSpacing = slotH + GAP;

        // Stat panel: narrower than the previous 2-col layout, single
        // column reads cleanly without needing 360 px. Anchored above the
        // button column with TITLE_CLEARANCE breathing room from the
        // scaled-2× "You Died!" title (which spans ~y=30–70). On tiny
        // windows / very high GUI scales there may not be enough headroom
        // — if the panel would land at y < 80 we skip it entirely rather
        // than collide with the title.
        int screenW = deathScreen.width;
        statsPanelW = Math.min(280, screenW - 40);
        statsPanelX = (screenW - statsPanelW) / 2;
        statsPanelY = slotY - GAP - STATS_PANEL_HEIGHT - TITLE_CLEARANCE;
        statsPanelActive = statsPanelY >= 80;

        // Replace the vanilla Title Screen button with one that exits
        // directly — vanilla wraps it in a "Are you sure you want to give
        // up?" ConfirmScreen which is redundant for this mod (the
        // Respawn button is the only one that needs a confirm).
        event.removeListener(respawn);
        event.removeListener(title);

        Button newWorld = Button.builder(NEW_WORLD_LABEL, b -> launchWorld(deathScreen, false))
                .bounds(slotX, slotY, slotW, slotH)
                .build();
        event.addListener(newWorld);

        Button sameWorld = Button.builder(SAME_WORLD_LABEL, b -> launchWorld(deathScreen, true))
                .bounds(slotX, slotY + rowSpacing, slotW, slotH)
                .build();
        event.addListener(sameWorld);

        Button wrappedRespawn = Button.builder(RESPAWN_KEY, b -> showRespawnConfirm(deathScreen))
                .bounds(slotX, slotY + 2 * rowSpacing, slotW, slotH)
                .build();
        event.addListener(wrappedRespawn);

        Button newTitle = Button.builder(TITLE_SCREEN_KEY, b -> goToTitleScreen())
                .bounds(slotX, slotY + 3 * rowSpacing, slotW, slotH)
                .build();
        event.addListener(newTitle);
    }

    /**
     * Draw the single-column stats panel + centred equipment row just
     * above the (vanilla-positioned) buttons. Only runs after
     * {@link #onScreenInitPost} set the {@code statsPanelActive} flag,
     * and only when a {@link DeathStatsPacket} has been received for the
     * current death. Without a packet we render nothing.
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

        // Opaque background panel — purely decorative now that the
        // vanilla subtitle/score have been moved out of the panel area
        // by DeathScreenMoveTextMixin. Gives the stats block visual
        // weight against the world-render backdrop.
        graphics.fill(statsPanelX, statsPanelY,
                statsPanelX + statsPanelW, statsPanelY + STATS_PANEL_HEIGHT,
                STATS_PANEL_BG);

        int labelColor = 0xBBBBBB;
        int valueColor = 0xFFFFFF;
        int innerLeft = statsPanelX + STATS_INNER_PAD;
        int innerRight = statsPanelX + statsPanelW - STATS_INNER_PAD;
        int innerWidth = innerRight - innerLeft;
        int rowY = statsPanelY + STATS_INNER_PAD;

        // Six stats stacked one per row, label left-aligned and value
        // right-aligned to the panel's inner right edge so values line up
        // in a single column regardless of label length.
        drawStatRow(graphics, font, STATS_MOBS, String.valueOf(stats.mobKills()),
                innerLeft, rowY, innerWidth, labelColor, valueColor);
        rowY += STATS_LINE_HEIGHT;
        drawStatRow(graphics, font, STATS_TIME, formatTime(stats.runTicks()),
                innerLeft, rowY, innerWidth, labelColor, valueColor);
        rowY += STATS_LINE_HEIGHT;
        drawStatRow(graphics, font, STATS_CARTS, String.valueOf(stats.cartsTravelled()),
                innerLeft, rowY, innerWidth, labelColor, valueColor);
        rowY += STATS_LINE_HEIGHT;
        drawStatRow(graphics, font, STATS_CONTAINERS, String.valueOf(stats.containersOpened()),
                innerLeft, rowY, innerWidth, labelColor, valueColor);
        rowY += STATS_LINE_HEIGHT;
        drawStatRow(graphics, font, STATS_DISTANCE, formatDistance(stats.distanceBlocks()),
                innerLeft, rowY, innerWidth, labelColor, valueColor);
        rowY += STATS_LINE_HEIGHT;
        drawStatRow(graphics, font, STATS_BOOKS, String.valueOf(stats.booksRead()),
                innerLeft, rowY, innerWidth, labelColor, valueColor);
        rowY += STATS_LINE_HEIGHT + STATS_INNER_PAD;

        // Equipment row: armor pieces (helmet/chest/legs/feet) followed
        // by a small gap and then the most-used weapon. Empty slots are
        // skipped entirely — the remaining icons reflow tightly so a
        // player with only a sword sees one centred icon rather than
        // four ghost squares + the sword pinned to one side.
        List<ItemStack> armorRow = new ArrayList<>(4);
        if (!stats.armorHead().isEmpty())  armorRow.add(stats.armorHead());
        if (!stats.armorChest().isEmpty()) armorRow.add(stats.armorChest());
        if (!stats.armorLegs().isEmpty())  armorRow.add(stats.armorLegs());
        if (!stats.armorFeet().isEmpty())  armorRow.add(stats.armorFeet());
        ItemStack weapon = stats.mostUsedWeapon();
        boolean hasWeapon = !weapon.isEmpty();

        if (!armorRow.isEmpty() || hasWeapon) {
            int rowWidth = armorRow.size() * STATS_ICON_SIZE
                    + Math.max(0, armorRow.size() - 1) * STATS_ICON_GAP
                    + (hasWeapon ? STATS_ICON_SIZE : 0)
                    + (hasWeapon && !armorRow.isEmpty() ? STATS_ARMOR_WEAPON_SEPARATOR : 0);
            int cursor = statsPanelX + (statsPanelW - rowWidth) / 2;
            for (ItemStack piece : armorRow) {
                renderItemSlot(graphics, piece, cursor, rowY, mouseX, mouseY);
                cursor += STATS_ICON_SIZE + STATS_ICON_GAP;
            }
            if (hasWeapon) {
                if (!armorRow.isEmpty()) {
                    // The trailing ICON_GAP in the loop is overwritten by
                    // the separator gap, so subtract it back out.
                    cursor += STATS_ARMOR_WEAPON_SEPARATOR - STATS_ICON_GAP;
                }
                renderItemSlot(graphics, weapon, cursor, rowY, mouseX, mouseY);
            }
        }
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof DeathScreen) {
            statsPanelActive = false;
        }
    }

    /**
     * Draw one stat row as {@code label …… value} across {@code width}
     * pixels. Label left-aligned, value right-aligned to the row's right
     * edge so values stack into a clean vertical column.
     */
    private static void drawStatRow(GuiGraphics graphics, Font font,
                                    Component label, String value,
                                    int x, int y, int width,
                                    int labelColor, int valueColor) {
        graphics.drawString(font, label, x, y, labelColor, false);
        int valueWidth = font.width(value);
        graphics.drawString(font, value, x + width - valueWidth, y, valueColor, false);
    }

    /**
     * Render a single item slot + tooltip on hover. Empty stacks are an
     * early-return — callers are expected to filter empties out of the
     * equipment row so empty slots leave no visual artefact.
     */
    private static void renderItemSlot(GuiGraphics graphics, ItemStack stack,
                                       int x, int y, int mouseX, int mouseY) {
        if (stack.isEmpty()) return;
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
