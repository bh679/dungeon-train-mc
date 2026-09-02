package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderQuietRules;
import games.brennan.dungeontrain.editor.EditorQuietRuleEvents;
import games.brennan.dungeontrain.editor.EditorQuietRules;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.cheat.EditorContentIntegrity;
import games.brennan.dungeontrain.client.menu.CustomContentToggleButton;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.perf.PerfTestMode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.tutorial.TutorialSteps;
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
 * TitleScreen first-row layout. Always installs four replacement widgets in
 * the vanilla Singleplayer slot and toggles their visibility based on
 * {@link VersionInfo#BRANCH} + Shift modifier. Vanilla Singleplayer is always
 * hidden — the ⚙ icon is the single entry point into {@link SelectWorldScreen},
 * and it is revealed by holding Shift rather than shown outright.
 *
 * <p>Visibility matrix:</p>
 * <pre>
 *   Branch | Shift | First row
 *   -------+-------+------------------------------------------------
 *   main   | no    | [ New World (survival, DT preset) ............. ]
 *   main   | yes   | [ New World (survival, DT preset) | ⚙ world list ]
 *   dev    | no    | [ New World (creative, DT preset) | ⏱ perf world ]
 *   dev    | yes   | [ New World (survival, DT preset) | ⚙ world list ]
 * </pre>
 *
 * <p>The world list is a rare errand — a player starts a fresh run far more often than they
 * return to an old save — so the unlabelled cog is not worth a permanent slot on the menu. It
 * sits behind Shift, the same modifier that reveals the Train Builder on the row below, and the
 * survival New World button takes the freed width when it is hidden.</p>
 *
 * <p>Both rows share one wide+square split, so the layout doesn't shift when the
 * shift modifier swaps them — only the square's occupant changes. The ⏱ button
 * creates a superflat, pinned-seed, quiet world for benchmarking without needing
 * {@code -PperfTest} on the command line; see {@link PerfTestMode}.</p>
 *
 * <p>"main" is decided at build time by commit-hash equivalence with the
 * local {@code main} ref, so worktrees built straight off main also register
 * as release.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DevQuickWorldHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Component SINGLEPLAYER_KEY = Component.translatable("menu.singleplayer");
    private static final Component NEW_WORLD_LABEL = Component.translatable("gui.dungeontrain.new_world");
    private static final Component SETTINGS_ICON_LABEL =
            Component.literal("⚙").withStyle(ChatFormatting.BOLD);
    /**
     * Dev-row perf-world button. A literal glyph rather than a translatable key for the same reason
     * as the settings icon above: this row only exists on dev builds, so a lang key would cost a
     * string in all 20 locales (plus its provenance stamp) for a button no player ever sees.
     */
    private static final Component PERF_ICON_LABEL =
            Component.literal("⏱").withStyle(ChatFormatting.BOLD);

    /**
     * Shared with {@code EditorQuietRuleEvents}, which identifies an editor world by this prefix on
     * every server start. Two copies of the string would mean editor worlds that quietly miss the
     * rule, so there is only the one.
     */
    private static final String EDITOR_WORLD_PREFIX = EditorQuietRuleEvents.EDITOR_WORLD_PREFIX;
    private static final String BUILDER_WORLD_PREFIX = "train builder ";

    private static final int GAP = 4;

    private static final ResourceKey<WorldPreset> DT_DEFAULT_PRESET = ResourceKey.create(
            Registries.WORLD_PRESET,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train"));

    /** Compatible-Terrain variant (vanilla overworld noise) selected when the COMMON toggle is on. */
    private static final ResourceKey<WorldPreset> DT_COMPAT_PRESET = ResourceKey.create(
            Registries.WORLD_PRESET,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train_compat"));

    /**
     * Train Builder world: overworld only, 96 blocks tall, and pure void — everything in it
     * (platform, track, carriages) is stamped once by {@code BuilderWorldSetup} when the client
     * reports which mode was picked. Deliberately absent from the
     * {@code minecraft:worldgen/world_preset/normal} tag, so it never shows up in the vanilla
     * World Type cycle — the builder tiles are its only entry point.
     */
    private static final ResourceKey<WorldPreset> DT_BUILDER_PRESET = ResourceKey.create(
            Registries.WORLD_PRESET,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train_builder"));

    /**
     * Train Editor world: the builder recipe (overworld only, void, flat generator) at Dungeon
     * Train's full height — plots at y=230 need the 320 ceiling, and {@code /dt portal test} needs
     * the 80-block basement under the floor. See {@code EditorWorldLayout}. Untagged for the same
     * reason as the builder preset: the Train Editor tiles are its only entry point.
     */
    private static final ResourceKey<WorldPreset> DT_EDITOR_PRESET = ResourceKey.create(
            Registries.WORLD_PRESET,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train_editor"));

    private static WeakReference<Button> singleplayerRef = new WeakReference<>(null);
    private static WeakReference<Button> creativeNewWorldRef = new WeakReference<>(null);
    private static WeakReference<Button> perfNewWorldRef = new WeakReference<>(null);
    private static WeakReference<Button> survivalNewWorldRef = new WeakReference<>(null);
    private static WeakReference<Button> settingsIconRef = new WeakReference<>(null);
    private static WeakReference<Button> contentToggleRef = new WeakReference<>(null);
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

        // The custom-content toggle only exists for installs that have Train Editor content, so
        // the survival row narrows by one square when it does and is untouched when it doesn't.
        boolean hasBuilds = hasEditorBuilds();
        int survivalX = hasBuilds ? spX + iconW + GAP : spX;
        int survivalW = hasBuilds ? wideW - iconW - GAP : wideW;

        // Dev row uses the SAME wide+square split as the survival row, so the two rows line up
        // whichever is showing — only the square's occupant differs (perf world vs settings).
        Button creativeNewWorld = Button.builder(NEW_WORLD_LABEL,
                        b -> launchCreativeWorld(titleScreen))
                .bounds(spX, spY, wideW, spH)
                .build();

        Button perfNewWorld = Button.builder(PERF_ICON_LABEL,
                        b -> launchPerfWorld(titleScreen))
                .bounds(spX + wideW + GAP, spY, iconW, spH)
                .build();

        Button survivalNewWorld = Button.builder(NEW_WORLD_LABEL,
                        b -> launchSurvivalWorld(titleScreen))
                .bounds(survivalX, spY, survivalW, spH)
                .build();

        CustomContentToggleButton contentToggle = hasBuilds ? buildContentToggle(spX, spY, iconW) : null;

        Button settingsIcon = buildSettingsIcon(
                spX + wideW + GAP, spY, iconW, spH, titleScreen);

        event.addListener(creativeNewWorld);
        event.addListener(perfNewWorld);
        event.addListener(survivalNewWorld);
        event.addListener(settingsIcon);
        if (contentToggle != null) {
            event.addListener(contentToggle);
        }

        singleplayerRef = new WeakReference<>(singleplayer);
        creativeNewWorldRef = new WeakReference<>(creativeNewWorld);
        perfNewWorldRef = new WeakReference<>(perfNewWorld);
        survivalNewWorldRef = new WeakReference<>(survivalNewWorld);
        settingsIconRef = new WeakReference<>(settingsIcon);
        contentToggleRef = new WeakReference<>(contentToggle);
        screenRef = new WeakReference<>(titleScreen);

        applyVisibility();
    }

    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (event.getScreen() != screenRef.get()) {
            return;
        }
        applyVisibility();
    }

    /**
     * One read of the Shift key drives both decisions in a frame: which New World row shows, and
     * whether the ⚙ world-list icon is revealed beside it.
     */
    private static void applyVisibility() {
        boolean shift = Screen.hasShiftDown();
        applyVisibility(currentMode(shift), shift);
    }

    private static void applyVisibility(FirstRowMode mode, boolean shift) {
        Button sp = singleplayerRef.get();
        Button creative = creativeNewWorldRef.get();
        Button perf = perfNewWorldRef.get();
        Button survival = survivalNewWorldRef.get();
        Button settings = settingsIconRef.get();
        if (sp == null || creative == null || perf == null || survival == null || settings == null) {
            return;
        }
        sp.visible = false;
        boolean showRow = mode == FirstRowMode.SURVIVAL_ROW;
        creative.visible = !showRow;
        // Perf world is a dev-only affordance — it rides with the creative row and is never offered
        // on main builds or behind the shift modifier.
        perf.visible = !showRow;
        survival.visible = showRow;
        // The world list is Shift-only. On main that is the whole gating; on dev the survival row
        // is behind Shift anyway, so the cog rides in with it exactly as before.
        boolean showSettings = showRow && shift;
        settings.visible = showSettings;
        // New World absorbs the cog's square when it is hidden, so the row reads as one finished
        // button rather than a button with a hole next to it. Both edges are derived from the cog's
        // own bounds, which never move — no second set of widths to keep in sync.
        int survivalRight = showSettings
                ? settings.getX() - GAP
                : settings.getX() + settings.getWidth();
        survival.setWidth(survivalRight - survival.getX());
        // Outside the null guard above: a clean install has no toggle at all, and that is not a
        // reason to stop laying out the rest of the row.
        Button contentToggle = contentToggleRef.get();
        if (contentToggle != null) {
            contentToggle.visible = showRow;
        }
    }

    /**
     * Does this install have Train Editor content? Guarded the same way
     * {@code CustomContentGate.askCounting} guards it: the scan walks player-editable folders from
     * the title screen, outside the server lifecycle it normally runs in, and a failure there must
     * cost the player nothing more than the toggle.
     */
    private static boolean hasEditorBuilds() {
        try {
            return EditorContentIntegrity.hasCustomContent();
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Couldn't check for editor content for the title-screen "
                    + "toggle; leaving it off.", e);
            return false;
        }
    }

    /**
     * The custom-content toggle. Pressing it flips the standing answer AND retires the per-world
     * confirmation, so the tooltip has to be re-pointed at the new state on the spot — the button
     * stays on screen after the press and would otherwise describe the state it just left.
     */
    private static CustomContentToggleButton buildContentToggle(int x, int y, int size) {
        return new CustomContentToggleButton(x, y, size, b -> {
            CustomContentGate.toggleContentEnabled();
            ((CustomContentToggleButton) b).refreshTooltip();
        });
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

    /**
     * Create the world the Train Editor's plot grid is laid out in.
     *
     * <p><b>No natural mob spawning</b> — see {@link EditorQuietRules}. A template carries the mobs
     * standing in its plot, so anything that wanders in is saved as part of somebody's build; this
     * bake is what makes "a mob in a plot is one the author placed" true. {@code
     * EditorQuietRuleEvents} re-applies it on every start, so this is the default rather than the
     * only enforcement.</p>
     *
     * <p><b>Void, overworld only</b> — {@link #DT_EDITOR_PRESET}. The plots are the only thing
     * the editor ever looks at, so the terrain that used to be generated under them (and the nether
     * and end alongside) was load time spent on nothing. {@code TrainBootstrapEvents} anchors the
     * spawn over the first plot and {@code BuilderSpawn.startFlying} keeps the player hovering there
     * until the editor command lifts them onto one.</p>
     *
     * <p><b>No train.</b> The editor lives on plots in the sky at {@code EditorLayout.PLOT_Y} and
     * reads nothing off a train, so this arms {@link PendingWorldChoices} with
     * {@code startsWithTrain = false} exactly as {@link #launchBuilderWorld} does. That one flag
     * skips the bootstrap spawn (the Sable ship, the eager carriage fill), the track corridor and
     * every band — the whole cost the player used to sit through before being lifted to the plots.
     * The other four fields are what {@code DungeonTrainWorldData.createDefault()} would have
     * chosen anyway, so plot sizing ({@code dims}) is unchanged from an editor world made before
     * this flag was armed.</p>
     *
     * <p>Which editor category to open on arrival is not written into the world — the client-side
     * {@link EditorAutoOpenHandler} carries the picker's choice across the load, the same way it
     * carries a {@link BuilderMode} for the builder path.</p>
     */
    public static void launchEditorWorld(Screen lastScreen) {
        String name = nextWorldName(EDITOR_WORLD_PREFIX);
        LOGGER.info("Editor world: creating '{}' (void, creative, no train — plots only)", name);

        // isPresent() requires all five fields, so pass the createDefault() values for the four we
        // don't care about — a partial set would be ignored and the world would spawn a train.
        PendingWorldChoices.set(
                DungeonTrainConfig.getTrainY(),
                false,
                CarriageDims.DEFAULT,
                DungeonTrainConfig.DEFAULT_GENERATION_MODE,
                DungeonTrainConfig.DEFAULT_GROUP_SIZE);

        GameRules rules = new GameRules();
        EditorQuietRules.apply(rules, null);   // no server yet — world is still being created
        LevelSettings settings = new LevelSettings(
                name,
                GameType.CREATIVE,
                false,
                Difficulty.NORMAL,
                true,
                rules,
                WorldDataConfiguration.DEFAULT);
        openLevel(name, settings, lastScreen, DT_EDITOR_PRESET, false);
    }

    /**
     * Create the world a Train Builder mode is edited in: the smallest, quietest world that can
     * hold what you're building.
     *
     * <ul>
     *   <li><b>No train.</b> {@link PendingWorldChoices} is armed with
     *       {@code startsWithTrain = false}, which {@code WorldLifecycleEvents} commits into
     *       {@code DungeonTrainWorldData} on the overworld's Load. That one flag suppresses the
     *       bootstrap spawn, the track corridor and every band — the builder never wanted a
     *       train in view, and generating one is pure cost.</li>
     *   <li><b>One dimension, 100 blocks tall, void</b> apart from a 300×300 platform — see
     *       {@link #DT_BUILDER_PRESET}.</li>
     *   <li><b>Always noon</b> (fixed in the dimension type) and <b>no clock, weather or natural
     *       mob spawning</b> — see {@link BuilderQuietRules}, which
     *       {@code BuilderQuietRuleEvents} re-applies on every start of a builder world, so this
     *       creation-time bake is the default rather than the only enforcement.</li>
     *   <li>Random seed — unlike the perf world, nothing here benefits from an identical
     *       world every run.</li>
     * </ul>
     *
     * <p>The chosen {@code mode} is not written into the world; the client-side
     * {@link EditorAutoOpenHandler} carries it across the load and acts on arrival.</p>
     */
    public static void launchBuilderWorld(Screen lastScreen, BuilderMode mode) {
        String name = nextWorldName(BUILDER_WORLD_PREFIX);
        LOGGER.info("Builder world: creating '{}' (void platform, creative, no train) for mode '{}'",
                name, mode.id());

        // isPresent() requires all five fields, so pass defaults for the four we don't care
        // about — a partial set would be ignored and the world would spawn a train.
        PendingWorldChoices.set(
                BuilderWorldLayout.TRAIN_Y,
                false,
                CarriageDims.DEFAULT,
                DungeonTrainConfig.DEFAULT_GENERATION_MODE,
                DungeonTrainConfig.DEFAULT_GROUP_SIZE);

        GameRules rules = new GameRules();
        BuilderQuietRules.apply(rules, null);   // no server yet — world is still being created

        LevelSettings settings = new LevelSettings(
                name,
                GameType.CREATIVE,
                false,
                Difficulty.PEACEFUL,
                true,
                rules,
                WorldDataConfiguration.DEFAULT);
        openLevel(name, settings, lastScreen, DT_BUILDER_PRESET, false);
    }

    /** Lowest unused {@code <prefix><n>}, so repeat launches never reuse or clobber a save. */
    private static String nextWorldName(String prefix) {
        LevelStorageSource source = Minecraft.getInstance().getLevelSource();
        int i = 1;
        while (source.levelExists(prefix + i)) {
            i++;
        }
        return prefix + i;
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
        openLevel(name, settings, lastScreen, PerfTestMode.ENABLED);
    }

    /**
     * Create a world for performance measurement: superflat, pinned seed, and the quiet game rules
     * baked in — without needing {@code -PperfTest} on the command line, so a session can make both
     * normal and perf worlds without relaunching Gradle.
     *
     * <p>Everything that makes it a perf world (preset, seed, rules) is written into the world at
     * creation, so reopening it later from the world list keeps all three with no flag set.</p>
     *
     * <p>Creative, matching the neighbouring dev button. Note when reading numbers from it: hostile
     * mobs do not target creative players, so mob targeting and pathfinding cost less here than in a
     * real session — {@code /gamemode survival} in-world if a measurement depends on that. The
     * {@code scripts/perf/} dedicated-server harness already runs survival.</p>
     */
    private static void launchPerfWorld(Screen lastScreen) {
        String name = "Perf World " + System.currentTimeMillis();
        GameRules rules = new GameRules();
        PerfTestMode.applyQuietRules(rules, null);   // no server yet — world is still being created
        LevelSettings settings = new LevelSettings(
                name,
                GameType.CREATIVE,
                false,
                Difficulty.NORMAL,
                true,
                rules,
                WorldDataConfiguration.DEFAULT);
        openLevel(name, settings, lastScreen, true);
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
        openLevel(name, settings, lastScreen, PerfTestMode.ENABLED);
    }

    /**
     * @param perf create this world for performance measurement — superflat and pinned-seed. The
     *             other callers pass {@link PerfTestMode#ENABLED} so the JVM-property path is
     *             unchanged; the dev perf button passes {@code true} directly.
     */
    private static void openLevel(String name, LevelSettings settings, Screen lastScreen, boolean perf) {
        // A perf world is flat AND pinned-seed; those two happen to coincide there but are
        // independent choices, so the overload below takes them separately.
        // Flat wins over the compatible-terrain toggle for a perf world: the point is to remove
        // chunk generation from the measurement, and Compatible Terrain is still noise terrain.
        ResourceKey<WorldPreset> preset = perf
                ? PerfTestMode.FLAT_PRESET
                : (DungeonTrainCommonConfig.getDefaultCompatibleTerrain()
                    ? DT_COMPAT_PRESET : DT_DEFAULT_PRESET);
        openLevel(name, settings, lastScreen, preset, perf);
    }

    /**
     * @param preset     world preset to generate with; falls back to the vanilla NORMAL
     *                   dimensions if it isn't in the registry
     * @param pinnedSeed use {@link PerfTestMode#seed()} instead of a random one. A pinned seed
     *                   makes every benchmark run lay out an identical world AND an identical
     *                   train — {@code DungeonTrainWorldData} derives the train's
     *                   {@code generationSeed} from the world seed, so this one value covers
     *                   both. Every non-benchmark launch keeps a random seed.
     */
    private static void openLevel(String name, LevelSettings settings, Screen lastScreen,
                                  ResourceKey<WorldPreset> preset, boolean pinnedSeed) {
        // New World is one of the two moments a run starts, so it is one of the two moments the
        // custom-content question gets asked — before the world exists, while "run without my
        // changes" is an answer that can still be honoured. Nothing to ask → falls straight through.
        if (CustomContentGate.askFirst(settings.gameType(), lastScreen,
                () -> openLevelNow(name, settings, lastScreen, preset, pinnedSeed))) {
            return;
        }
        openLevelNow(name, settings, lastScreen, preset, pinnedSeed);
    }

    private static void openLevelNow(String name, LevelSettings settings, Screen lastScreen,
                                     ResourceKey<WorldPreset> preset, boolean pinnedSeed) {
        Minecraft mc = Minecraft.getInstance();
        mc.options.tutorialStep = TutorialSteps.NONE;
        mc.options.save();
        WorldOptions options = pinnedSeed
                ? new WorldOptions(PerfTestMode.seed(), true, false)
                : WorldOptions.defaultWithRandomSeed();
        WorldOpenFlows flows = mc.createWorldOpenFlows();
        flows.createFreshLevel(name, settings, options, presetDimensions(preset), lastScreen);
    }

    private static Function<RegistryAccess, WorldDimensions> presetDimensions(ResourceKey<WorldPreset> key) {
        return registryAccess -> {
            Registry<WorldPreset> presetRegistry =
                    registryAccess.registryOrThrow(Registries.WORLD_PRESET);
            Optional<Holder.Reference<WorldPreset>> dt = presetRegistry.getHolder(key);
            if (dt.isPresent()) {
                return dt.get().value().createWorldDimensions();
            }
            LOGGER.warn("Quick-world: preset {} not in registry; falling back to NORMAL.", key.location());
            return WorldPresets.createNormalWorldDimensions(registryAccess);
        };
    }

    private static FirstRowMode currentMode(boolean shift) {
        boolean main = "main".equals(VersionInfo.BRANCH);
        return (main || shift) ? FirstRowMode.SURVIVAL_ROW : FirstRowMode.CREATIVE_QUICK;
    }

    private static void clearRefs() {
        singleplayerRef = new WeakReference<>(null);
        creativeNewWorldRef = new WeakReference<>(null);
        perfNewWorldRef = new WeakReference<>(null);
        survivalNewWorldRef = new WeakReference<>(null);
        settingsIconRef = new WeakReference<>(null);
        screenRef = new WeakReference<>(null);
    }

    private enum FirstRowMode {
        CREATIVE_QUICK,
        SURVIVAL_ROW
    }
}
