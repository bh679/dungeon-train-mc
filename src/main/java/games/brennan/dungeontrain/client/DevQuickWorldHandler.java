package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.Optional;
import java.util.function.Function;

/**
 * TitleScreen first-row layout. Always installs three replacement widgets in
 * the vanilla Singleplayer slot and toggles their visibility based on
 * {@link VersionInfo#BRANCH} + Shift modifier. Vanilla Singleplayer is always
 * hidden — the settings icon is the single entry point into
 * {@link SelectWorldScreen}.
 *
 * <p>Visibility matrix:</p>
 * <pre>
 *   Branch | Shift | First row
 *   -------+-------+------------------------------------------------
 *   main   | any   | [ New World (survival, DT preset) | settings ]
 *   dev    | no    | [ New World (creative, DT preset) ]
 *   dev    | yes   | [ New World (survival, DT preset) | settings ]
 * </pre>
 *
 * <p>"main" is decided at build time by commit-hash equivalence with the
 * local {@code main} ref, so worktrees built straight off main also register
 * as release.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DevQuickWorldHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Component SINGLEPLAYER_KEY = Component.translatable("menu.singleplayer");
    private static final Component NEW_WORLD_LABEL = Component.literal("New World");
    private static final Component SETTINGS_ICON_LABEL =
            Component.literal("⚙").withStyle(ChatFormatting.BOLD);

    private static final String EDITOR_WORLD_PREFIX = "train editor ";

    private static final int GAP = 4;

    private static final ResourceKey<WorldPreset> DT_DEFAULT_PRESET = ResourceKey.create(
            Registries.WORLD_PRESET,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train"));

    /** Compatible-Terrain variant (vanilla overworld noise) selected when the COMMON toggle is on. */
    private static final ResourceKey<WorldPreset> DT_COMPAT_PRESET = ResourceKey.create(
            Registries.WORLD_PRESET,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train_compat"));

    private static WeakReference<Button> singleplayerRef = new WeakReference<>(null);
    private static WeakReference<Button> creativeNewWorldRef = new WeakReference<>(null);
    private static WeakReference<Button> survivalNewWorldRef = new WeakReference<>(null);
    private static WeakReference<Button> settingsIconRef = new WeakReference<>(null);
    private static WeakReference<Screen> screenRef = new WeakReference<>(null);

    private DevQuickWorldHandler() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }

        Button singleplayer = findSingleplayerButton(event);
        if (singleplayer == null) {
            LOGGER.warn("Quick-world: singleplayer button not found on TitleScreen; skipping.");
            clearRefs();
            return;
        }

        int spX = singleplayer.getX();
        int spY = singleplayer.getY();
        int spW = singleplayer.getWidth();
        int spH = singleplayer.getHeight();
        int iconW = spH; // square button (~10% of vanilla 200px width)
        int wideW = spW - iconW - GAP;

        Button creativeNewWorld = Button.builder(NEW_WORLD_LABEL,
                        b -> launchCreativeWorld(titleScreen))
                .bounds(spX, spY, spW, spH)
                .build();

        Button survivalNewWorld = Button.builder(NEW_WORLD_LABEL,
                        b -> launchSurvivalWorld(titleScreen))
                .bounds(spX, spY, wideW, spH)
                .build();

        Button settingsIcon = buildSettingsIcon(
                spX + wideW + GAP, spY, iconW, spH, titleScreen);

        event.addListener(creativeNewWorld);
        event.addListener(survivalNewWorld);
        event.addListener(settingsIcon);

        singleplayerRef = new WeakReference<>(singleplayer);
        creativeNewWorldRef = new WeakReference<>(creativeNewWorld);
        survivalNewWorldRef = new WeakReference<>(survivalNewWorld);
        settingsIconRef = new WeakReference<>(settingsIcon);
        screenRef = new WeakReference<>(titleScreen);

        applyVisibility(currentMode());
    }

    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (event.getScreen() != screenRef.get()) {
            return;
        }
        applyVisibility(currentMode());
    }

    private static void applyVisibility(FirstRowMode mode) {
        Button sp = singleplayerRef.get();
        Button creative = creativeNewWorldRef.get();
        Button survival = survivalNewWorldRef.get();
        Button settings = settingsIconRef.get();
        if (sp == null || creative == null || survival == null || settings == null) {
            return;
        }
        sp.visible = false;
        boolean showRow = mode == FirstRowMode.SURVIVAL_ROW;
        creative.visible = !showRow;
        survival.visible = showRow;
        settings.visible = showRow;
    }

    private static Button buildSettingsIcon(int x, int y, int w, int h, Screen parent) {
        Button.OnPress onPress = b -> Minecraft.getInstance().setScreen(new SelectWorldScreen(parent));
        return Button.builder(SETTINGS_ICON_LABEL, onPress)
                .bounds(x, y, w, h)
                .build();
    }

    private static Button findSingleplayerButton(ScreenEvent.Init.Post event) {
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof Button button
                    && SINGLEPLAYER_KEY.equals(button.getMessage())) {
                return button;
            }
        }
        return null;
    }

    static void launchEditorWorld(Screen lastScreen) {
        String name = nextEditorWorldName();
        LevelSettings settings = new LevelSettings(
                name,
                GameType.CREATIVE,
                false,
                Difficulty.NORMAL,
                true,
                new GameRules(),
                WorldDataConfiguration.DEFAULT);
        openLevel(name, settings, lastScreen);
    }

    private static String nextEditorWorldName() {
        LevelStorageSource source = Minecraft.getInstance().getLevelSource();
        int i = 1;
        while (source.levelExists(EDITOR_WORLD_PREFIX + i)) {
            i++;
        }
        return EDITOR_WORLD_PREFIX + i;
    }

    private static void launchCreativeWorld(Screen lastScreen) {
        String name = "Dev World " + System.currentTimeMillis();
        LevelSettings settings = new LevelSettings(
                name,
                GameType.CREATIVE,
                false,
                Difficulty.NORMAL,
                true,
                new GameRules(),
                WorldDataConfiguration.DEFAULT);
        openLevel(name, settings, lastScreen);
    }

    private static void launchSurvivalWorld(Screen lastScreen) {
        String name = "World " + System.currentTimeMillis();
        LevelSettings settings = new LevelSettings(
                name,
                GameType.SURVIVAL,
                false,
                Difficulty.NORMAL,
                false,
                new GameRules(),
                WorldDataConfiguration.DEFAULT);
        openLevel(name, settings, lastScreen);
    }

    private static void openLevel(String name, LevelSettings settings, Screen lastScreen) {
        Minecraft mc = Minecraft.getInstance();
        WorldOptions options = WorldOptions.defaultWithRandomSeed();
        WorldOpenFlows flows = mc.createWorldOpenFlows();
        flows.createFreshLevel(name, settings, options, dtPresetDimensions(), lastScreen);
    }

    private static Function<RegistryAccess, WorldDimensions> dtPresetDimensions() {
        return registryAccess -> {
            Registry<WorldPreset> presetRegistry =
                    registryAccess.registryOrThrow(Registries.WORLD_PRESET);
            ResourceKey<WorldPreset> key = DungeonTrainCommonConfig.getDefaultCompatibleTerrain()
                    ? DT_COMPAT_PRESET : DT_DEFAULT_PRESET;
            Optional<Holder.Reference<WorldPreset>> dt = presetRegistry.getHolder(key);
            if (dt.isPresent()) {
                return dt.get().value().createWorldDimensions();
            }
            LOGGER.warn("Quick-world: DT default preset not in registry; falling back to NORMAL.");
            return WorldPresets.createNormalWorldDimensions(registryAccess);
        };
    }

    private static FirstRowMode currentMode() {
        boolean main = "main".equals(VersionInfo.BRANCH);
        boolean shift = Screen.hasShiftDown();
        return (main || shift) ? FirstRowMode.SURVIVAL_ROW : FirstRowMode.CREATIVE_QUICK;
    }

    private static void clearRefs() {
        singleplayerRef = new WeakReference<>(null);
        creativeNewWorldRef = new WeakReference<>(null);
        survivalNewWorldRef = new WeakReference<>(null);
        settingsIconRef = new WeakReference<>(null);
        screenRef = new WeakReference<>(null);
    }

    private enum FirstRowMode {
        CREATIVE_QUICK,
        SURVIVAL_ROW
    }
}
