package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
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
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.Optional;
import java.util.function.Function;

/**
 * Dev-only TitleScreen shortcut: on dev branches the Singleplayer button is
 * replaced by "New World" by default, which immediately spins up a fresh
 * creative-mode world using the Dungeon Train default preset. Hold Shift to
 * swap back to the vanilla Singleplayer button (world-select screen).
 *
 * <p>Gated on {@link VersionInfo#BRANCH} != "main" so release jars are unaffected.
 * Mirrors the dev-only pattern used by {@link VersionMenuOverlay}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DevQuickWorldHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Component SINGLEPLAYER_KEY = Component.translatable("menu.singleplayer");
    private static final Component NEW_WORLD_LABEL = Component.literal("New World");

    private static final ResourceKey<WorldPreset> DT_DEFAULT_PRESET = ResourceKey.create(
            Registries.WORLD_PRESET,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train"));

    private static WeakReference<Button> singleplayerRef = new WeakReference<>(null);
    private static WeakReference<Button> newWorldRef = new WeakReference<>(null);
    private static WeakReference<Screen> screenRef = new WeakReference<>(null);

    private DevQuickWorldHandler() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!isDevBuild()) {
            return;
        }
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }

        Button singleplayer = findSingleplayerButton(event);
        if (singleplayer == null) {
            LOGGER.warn("Dev quick-world: singleplayer button not found on TitleScreen; skipping.");
            singleplayerRef = new WeakReference<>(null);
            newWorldRef = new WeakReference<>(null);
            screenRef = new WeakReference<>(null);
            return;
        }

        Button newWorld = Button.builder(NEW_WORLD_LABEL,
                        b -> launchFreshWorld(titleScreen))
                .bounds(singleplayer.getX(), singleplayer.getY(),
                        singleplayer.getWidth(), singleplayer.getHeight())
                .build();
        newWorld.visible = true;
        singleplayer.visible = false;

        event.addListener(newWorld);

        singleplayerRef = new WeakReference<>(singleplayer);
        newWorldRef = new WeakReference<>(newWorld);
        screenRef = new WeakReference<>(titleScreen);
    }

    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (!isDevBuild()) {
            return;
        }
        if (event.getScreen() != screenRef.get()) {
            return;
        }
        Button sp = singleplayerRef.get();
        Button nw = newWorldRef.get();
        if (sp == null || nw == null) {
            return;
        }
        boolean shift = Screen.hasShiftDown();
        sp.visible = shift;
        nw.visible = !shift;
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

    private static void launchFreshWorld(Screen lastScreen) {
        Minecraft mc = Minecraft.getInstance();
        String name = "Dev World " + System.currentTimeMillis();
        LevelSettings settings = new LevelSettings(
                name,
                GameType.CREATIVE,
                false,
                Difficulty.NORMAL,
                true,
                new GameRules(),
                WorldDataConfiguration.DEFAULT);
        WorldOptions options = WorldOptions.defaultWithRandomSeed();
        Function<RegistryAccess, WorldDimensions> dims = registryAccess -> {
            Registry<WorldPreset> presetRegistry =
                    registryAccess.registryOrThrow(Registries.WORLD_PRESET);
            Optional<Holder.Reference<WorldPreset>> dt = presetRegistry.getHolder(DT_DEFAULT_PRESET);
            if (dt.isPresent()) {
                return dt.get().value().createWorldDimensions();
            }
            LOGGER.warn("Dev quick-world: DT default preset not in registry; falling back to NORMAL.");
            return WorldPresets.createNormalWorldDimensions(registryAccess);
        };
        WorldOpenFlows flows = mc.createWorldOpenFlows();
        flows.createFreshLevel(name, settings, options, dims, lastScreen);
    }

    private static boolean isDevBuild() {
        return !"main".equals(VersionInfo.BRANCH);
    }
}
