package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.DeathStatsPacket;
import games.brennan.dungeontrain.player.PendingInventory;
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
import net.minecraft.server.level.ServerPlayer;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Rewrites the vanilla DeathScreen into a compact "death recap" layout on
 * singleplayer Dungeon Train worlds:
 *
 * <pre>
 *                       You Died!
 *                      Killed by &lt;mob&gt;
 *
 *   [ DIST  TIME  CARTS  LOOT  BOOKS ]              stats strip
 *   [ MET  KILLED  FRIENDS  MOBS  DEALT  TAKEN ]    combat strip
 *   [ Weapon [I]                Armor [I][I][I][I] ]   loadout strip
 *
 *   [    New World    ][   Title Screen    ]   side-by-side buttons
 * </pre>
 *
 * <p>The "Respawn" and "Same World" buttons are intentionally absent — in
 * Dungeon Train, dying ends the run; the only continuations are starting a
 * new world or exiting to the title screen. Same-seed re-runs are still
 * accessible from the title-screen world picker.</p>
 *
 * <p>New World creates a fresh save with a random seed. It carries forward
 * the current world's vanilla settings (game mode, difficulty, hardcore,
 * data packs) and Dungeon Train options (trainY, startsWithTrain, carriage
 * dims, generation mode, group size) via {@link PendingWorldChoices};
 * starting dimension is rolled fresh by
 * {@link StartingDimension#rollRespawnDimension} per the 94/5/1 distribution.</p>
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

    private static final ResourceKey<WorldPreset> DT_OVERWORLD = preset("dungeon_train");
    private static final ResourceKey<WorldPreset> DT_OVERWORLD_COMPAT = preset("dungeon_train_compat");
    private static final ResourceKey<WorldPreset> DT_NETHER = preset("dungeon_train_nether");
    private static final ResourceKey<WorldPreset> DT_END = preset("dungeon_train_end");

    private static final int GAP = 4;
    private static final int PANEL_GAP = 4;
    private static final int FONT_LINE = 9;
    private static final int ICON_SIZE = 16;

    private static final int STATS_PAD_H = 12;
    private static final int STATS_PAD_V = 6;
    private static final int STATS_LABEL_VALUE_GAP = 2;
    private static final int STATS_PANEL_H =
            STATS_PAD_V + FONT_LINE + STATS_LABEL_VALUE_GAP + FONT_LINE + STATS_PAD_V;

    private static final int LOADOUT_PAD_H = 12;
    private static final int LOADOUT_PAD_V = 6;
    private static final int LOADOUT_PANEL_H = LOADOUT_PAD_V + ICON_SIZE + LOADOUT_PAD_V;
    private static final int ARMOR_ICON_GAP = 2;

    private static final int PANEL_BG = 0xB8101010;
    private static final int PANEL_BORDER = 0x66FF5555;
    private static final int PANEL_BORDER_H = 1;
    private static final int LABEL_COLOR = 0xBBBBBB;
    private static final int VALUE_COLOR = 0xFFFFFF;
    private static final int EMPTY_SLOT_COLOR = 0x40FFFFFF;

    /**
     * Headroom above the stats panel top, leaving room for the scaled
     * {@code You Died!} title (renders at scaled y=60–78) and the mixin-
     * relocated subtitle (renders at y=82–91). Below this threshold the
     * panels are skipped and the screen falls back to vanilla layout.
     */
    private static final int MIN_STATS_TOP_Y = 95;

    private static final Component STATS_WEAPON_LABEL = Component.translatable("gui.dungeontrain.death.stats.weapon");
    private static final Component STATS_ARMOR_LABEL = Component.translatable("gui.dungeontrain.death.stats.armor");

    private static final Component STATS_SHORT_DISTANCE = Component.translatable("gui.dungeontrain.death.stats.short.distance");
    private static final Component STATS_SHORT_TIME = Component.translatable("gui.dungeontrain.death.stats.short.time");
    private static final Component STATS_SHORT_CARTS = Component.translatable("gui.dungeontrain.death.stats.short.carts");
    private static final Component STATS_SHORT_MOBS = Component.translatable("gui.dungeontrain.death.stats.short.mobs");
    private static final Component STATS_SHORT_LOOT = Component.translatable("gui.dungeontrain.death.stats.short.loot");
    private static final Component STATS_SHORT_BOOKS = Component.translatable("gui.dungeontrain.death.stats.short.books");
    private static final Component STATS_SHORT_ENCOUNTERED = Component.translatable("gui.dungeontrain.death.stats.short.encountered");
    private static final Component STATS_SHORT_PLAYERS_KILLED = Component.translatable("gui.dungeontrain.death.stats.short.players_killed");
    private static final Component STATS_SHORT_BEFRIENDED = Component.translatable("gui.dungeontrain.death.stats.short.befriended");
    private static final Component STATS_SHORT_DAMAGE_DEALT = Component.translatable("gui.dungeontrain.death.stats.short.damage_dealt");
    private static final Component STATS_SHORT_DAMAGE_TAKEN = Component.translatable("gui.dungeontrain.death.stats.short.damage_taken");

    /**
     * Set by {@link #onScreenInitPost} so {@link #onScreenRenderPost} knows
     * where to draw the panels for THIS DeathScreen instance. Cleared on
     * close to avoid stale values leaking to a non-death screen.
     */
    private static int panelX = 0;
    private static int panelW = 0;
    private static int statsY = 0;
    private static int combatY = 0;
    private static int loadoutY = 0;
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

        int screenW = deathScreen.width;
        panelW = Math.min(380, screenW - 80);
        panelX = (screenW - panelW) / 2;

        // The recap is a 3-panel stack (stats, combat, loadout) sitting ABOVE
        // the two action buttons. Anchor the buttons two rows below vanilla's
        // Respawn slot — but never so high that the stack would collide with
        // the "You Died!" title: push them down as far as it takes for the
        // top (stats) panel to clear MIN_STATS_TOP_Y. Without this, adding the
        // combat row lifted statsY past the guard and suppressed the whole recap.
        int stackAboveButtons = GAP + LOADOUT_PANEL_H + 2 * (PANEL_GAP + STATS_PANEL_H);
        int buttonsY = Math.max(slotY + rowSpacing * 2, MIN_STATS_TOP_Y + stackAboveButtons);
        loadoutY = buttonsY - GAP - LOADOUT_PANEL_H;
        combatY = loadoutY - PANEL_GAP - STATS_PANEL_H;
        statsY = combatY - PANEL_GAP - STATS_PANEL_H;
        // Show the recap unless the relocated buttons would overflow the bottom
        // of the screen (tiny window / very large GUI scale) — then fall back to vanilla.
        statsPanelActive = buttonsY + slotH <= deathScreen.height - GAP;

        // Drop vanilla's Respawn and Title buttons. In Option D the death
        // screen has exactly two actions: start a new world, or exit to the
        // title screen. Respawn-in-current-world is gone by design — death
        // ends the run.
        event.removeListener(respawn);
        event.removeListener(title);

        Button newWorld = Button.builder(NEW_WORLD_LABEL, b -> launchWorld(deathScreen, false))
                .bounds(slotX, buttonsY, halfW, slotH)
                .build();
        event.addListener(newWorld);

        // Title goes through the project's direct path (no vanilla
        // "Are you sure?" confirm screen), and uses the same Sable pre-drain
        // dance as launchWorld so the integrated server tears down cleanly.
        Button newTitle = Button.builder(TITLE_SCREEN_KEY, b -> goToTitleScreen())
                .bounds(slotX + halfW + GAP, buttonsY, halfW, slotH)
                .build();
        event.addListener(newTitle);
    }

    /**
     * Draw the stats strip + loadout strip above the button row. Skipped
     * when {@link #onScreenInitPost} flagged the layout as too cramped, or
     * when no {@link DeathStatsPacket} has been received yet for this death.
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

        // Top stats strip: 5 evenly-distributed cells, small uppercase label
        // above value. Cell centers are spaced cellWidth apart.
        fillPanel(graphics, panelX, statsY, panelW, STATS_PANEL_H);
        int topCellW = (panelW - STATS_PAD_H * 2) / 5;
        int topLabelY = statsY + STATS_PAD_V;
        int topValueY = topLabelY + FONT_LINE + STATS_LABEL_VALUE_GAP;
        int topFirstCenter = panelX + STATS_PAD_H + topCellW / 2;

        drawStatCell(graphics, font, STATS_SHORT_DISTANCE, formatDistance(stats.distanceBlocks()),
                topFirstCenter, topLabelY, topValueY);
        drawStatCell(graphics, font, STATS_SHORT_TIME, formatTime(stats.runTicks()),
                topFirstCenter + topCellW, topLabelY, topValueY);
        drawStatCell(graphics, font, STATS_SHORT_CARTS, String.valueOf(stats.cartsTravelled()),
                topFirstCenter + topCellW * 2, topLabelY, topValueY);
        drawStatCell(graphics, font, STATS_SHORT_LOOT, String.valueOf(stats.containersOpened()),
                topFirstCenter + topCellW * 3, topLabelY, topValueY);
        drawStatCell(graphics, font, STATS_SHORT_BOOKS, String.valueOf(stats.booksRead()),
                topFirstCenter + topCellW * 4, topLabelY, topValueY);

        // Combat strip: 6 cells — players encountered / killed / befriended,
        // total mob kills (counts everything, incl. PlayerMobs), and damage
        // dealt / taken (post-mitigation health points).
        fillPanel(graphics, panelX, combatY, panelW, STATS_PANEL_H);
        int combatCellW = (panelW - STATS_PAD_H * 2) / 6;
        int combatLabelY = combatY + STATS_PAD_V;
        int combatValueY = combatLabelY + FONT_LINE + STATS_LABEL_VALUE_GAP;
        int combatFirstCenter = panelX + STATS_PAD_H + combatCellW / 2;

        drawStatCell(graphics, font, STATS_SHORT_ENCOUNTERED, String.valueOf(stats.playersEncountered()),
                combatFirstCenter, combatLabelY, combatValueY);
        drawStatCell(graphics, font, STATS_SHORT_PLAYERS_KILLED, String.valueOf(stats.playersKilled()),
                combatFirstCenter + combatCellW, combatLabelY, combatValueY);
        drawStatCell(graphics, font, STATS_SHORT_BEFRIENDED, String.valueOf(stats.playersBefriended()),
                combatFirstCenter + combatCellW * 2, combatLabelY, combatValueY);
        drawStatCell(graphics, font, STATS_SHORT_MOBS, String.valueOf(stats.mobKills()),
                combatFirstCenter + combatCellW * 3, combatLabelY, combatValueY);
        drawStatCell(graphics, font, STATS_SHORT_DAMAGE_DEALT, formatDamage(stats.damageDealt()),
                combatFirstCenter + combatCellW * 4, combatLabelY, combatValueY);
        drawStatCell(graphics, font, STATS_SHORT_DAMAGE_TAKEN, formatDamage(stats.damageTaken()),
                combatFirstCenter + combatCellW * 5, combatLabelY, combatValueY);

        // Loadout strip: Weapon (label + 1 slot) left-anchored, Armor
        // (label + 4 slots) right-anchored. Same panel width as stats.
        fillPanel(graphics, panelX, loadoutY, panelW, LOADOUT_PANEL_H);
        int iconRowY = loadoutY + LOADOUT_PAD_V;
        int labelBaseline = iconRowY + (ICON_SIZE - font.lineHeight) / 2 + 1;

        int weaponLabelX = panelX + LOADOUT_PAD_H;
        graphics.drawString(font, STATS_WEAPON_LABEL, weaponLabelX, labelBaseline, LABEL_COLOR, false);
        int weaponSlotX = weaponLabelX + font.width(STATS_WEAPON_LABEL) + GAP;
        renderItemSlot(graphics, stats.mostUsedWeapon(), weaponSlotX, iconRowY, mouseX, mouseY);

        int armorRowW = 4 * ICON_SIZE + 3 * ARMOR_ICON_GAP;
        int armorRowX = panelX + panelW - LOADOUT_PAD_H - armorRowW;
        graphics.drawString(font, STATS_ARMOR_LABEL,
                armorRowX - GAP - font.width(STATS_ARMOR_LABEL),
                labelBaseline, LABEL_COLOR, false);
        ItemStack[] armorStacks = { stats.armorHead(), stats.armorChest(), stats.armorLegs(), stats.armorFeet() };
        for (int i = 0; i < 4; i++) {
            int slotX = armorRowX + i * (ICON_SIZE + ARMOR_ICON_GAP);
            renderItemSlot(graphics, armorStacks[i], slotX, iconRowY, mouseX, mouseY);
        }
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof DeathScreen) {
            statsPanelActive = false;
        }
    }

    /**
     * Translucent panel fill with a thin red top-edge accent. Reused for
     * both the stats strip and the loadout strip so the two read as a pair.
     */
    private static void fillPanel(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, PANEL_BG);
        graphics.fill(x, y, x + w, y + PANEL_BORDER_H, PANEL_BORDER);
    }

    /**
     * Draw one stat cell: small uppercase label centered above a larger
     * white value, both anchored to {@code centerX}.
     */
    private static void drawStatCell(GuiGraphics graphics, Font font,
                                     Component label, String value,
                                     int centerX, int labelY, int valueY) {
        int labelW = font.width(label);
        graphics.drawString(font, label, centerX - labelW / 2, labelY, LABEL_COLOR, false);
        int valueW = font.width(value);
        graphics.drawString(font, value, centerX - valueW / 2, valueY, VALUE_COLOR, false);
    }

    /**
     * Render a single item slot + tooltip on hover. Empty stacks render as
     * a faint placeholder box so the layout doesn't shift when a slot is
     * missing.
     */
    private static void renderItemSlot(GuiGraphics graphics, ItemStack stack,
                                       int x, int y, int mouseX, int mouseY) {
        if (stack.isEmpty()) {
            graphics.fill(x, y, x + ICON_SIZE, y + ICON_SIZE, EMPTY_SLOT_COLOR);
            return;
        }
        graphics.renderItem(stack, x, y);
        graphics.renderItemDecorations(Minecraft.getInstance().font, stack, x, y);
        if (mouseX >= x && mouseX < x + ICON_SIZE
                && mouseY >= y && mouseY < y + ICON_SIZE) {
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

    /**
     * Damage rendered as rounded health points (half-hearts), abbreviated for
     * large totals so a long run's damage can't overflow the narrow stat cell.
     */
    private static String formatDamage(double healthPoints) {
        if (healthPoints >= 1_000_000.0) return String.format("%.1fM", healthPoints / 1_000_000.0);
        if (healthPoints >= 10_000.0) return String.format("%.1fk", healthPoints / 1_000.0);
        return String.format("%,.0f", healthPoints);
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

        // When the current world has keepInventory on, snapshot the player's
        // inventory + XP (consumed on the next world's first login by
        // KeepInventoryCarryEvents) and create the next world with keepInventory
        // on too. Otherwise a fresh save resets the gamerule to its default
        // (false), so carried items would just be lost on the next death.
        boolean keepInventory = captureKeepInventory(server);

        String name = "Dungeon Train " + System.currentTimeMillis();
        GameRules gameRules = new GameRules();
        if (keepInventory) {
            // Null server is safe here: the new world's server doesn't exist
            // yet, and KEEP_INVENTORY has no change callback to fire.
            gameRules.getRule(GameRules.RULE_KEEPINVENTORY).set(true, null);
        }
        LevelSettings settings = new LevelSettings(
                name,
                cur.gameType(),
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

    /**
     * Read the current world's {@code keepInventory} gamerule and, when on,
     * snapshot the local player's inventory + experience into
     * {@link PendingInventory} so {@link games.brennan.dungeontrain.event.KeepInventoryCarryEvents}
     * can re-apply it on the next world's first login. Returns the gamerule
     * value so the caller can mirror it onto the new world's {@link GameRules}.
     *
     * <p>The read + snapshot run on the server thread (where the gamerule and
     * the player live) via the same {@code server.execute(...)} +
     * {@link CompletableFuture} round-trip as {@link #preDrainTrainSubLevels}.
     * Any stale snapshot is cleared whenever keepInventory is off, so a later
     * transition can't restore a previous capture.</p>
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
            case OVERWORLD -> DungeonTrainCommonConfig.getDefaultCompatibleTerrain()
                    ? DT_OVERWORLD_COMPAT : DT_OVERWORLD;
        };
    }

    private static ResourceKey<WorldPreset> preset(String path) {
        return ResourceKey.create(Registries.WORLD_PRESET,
                ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path));
    }
}
