package games.brennan.dungeontrain.client.bugresponse;

import games.brennan.dungeontrain.client.DungeonTrainSettingsScreen;
import games.brennan.dungeontrain.client.GraphicsCapabilities;
import games.brennan.dungeontrain.client.shader.IrisPackControl;
import games.brennan.dungeontrain.client.snapshot.RideSnapshotCapture;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.util.MachineSpecs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The lag tips the bug-report card offers when no newer release addresses lag. Each tip is checked
 * against this player's setup and only shown when it applies: Distant Horizons loaded, a high render
 * distance, a shader pack in use, ride photos above the lowest resolution, a singleplayer train long
 * enough to matter, too little memory handed to the game. Buttons open the screen where the setting
 * lives (Distant Horizons' own settings, Video Settings, the shader pack screen, Dungeon Train's
 * settings) or, for ride photos, apply the lower resolution directly.
 *
 * <p>The same list is the Performance tab of Options → Dungeon Train…
 * ({@link games.brennan.dungeontrain.client.DungeonTrainClientOptionsScreen}), so players can find the
 * tips without having to report lag first.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class LagTips {

    private static final String KEY = "gui.dungeontrain.bug_response.tips.";

    static final int RENDER_DISTANCE_LIMIT = 12;
    static final int RENDER_DISTANCE_SUGGESTED = 10;
    static final int PHOTO_EDGE_SUGGESTED = 720;
    private static final long GB = 1024L * 1024L * 1024L;
    /** Below this much heap the game is short of memory for Dungeon Train's world. */
    static final int MEMORY_RECOMMENDED_GB = 6;
    /** Distant Horizons keeps its own LOD data in memory on top of the game's. */
    static final int MEMORY_RECOMMENDED_DH_GB = 8;
    /** Only suggest more memory when the machine has enough to give. */
    private static final int MEMORY_MACHINE_MIN_GB = 12;

    /**
     * One tip. {@code button}/{@code action} are null when there is nothing to click; {@code hint} is
     * the grey note shown on the right instead (the memory tip — that setting lives in the launcher).
     */
    public record Tip(String id, Component text, @Nullable Component button, @Nullable Runnable action,
                      @Nullable Component hint) {}

    private LagTips() {}

    public static List<Tip> applicable(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        List<Tip> tips = new ArrayList<>();

        if (GraphicsCapabilities.distantHorizonsActive()) {
            boolean canOpen = distantHorizonsSettingsFactory().isPresent();
            tips.add(new Tip("dh", Component.translatable(KEY + "dh"),
                    canOpen ? Component.translatable(KEY + "dh.button") : null,
                    canOpen ? () -> openDistantHorizonsSettings(parent) : null,
                    null));
        }

        int renderDistance = mc.options.renderDistance().get();
        if (renderDistance > RENDER_DISTANCE_LIMIT) {
            tips.add(new Tip("render_distance",
                    Component.translatable(KEY + "render_distance", renderDistance, RENDER_DISTANCE_SUGGESTED),
                    Component.translatable("options.video"),
                    () -> mc.setScreen(new VideoSettingsScreen(parent, mc, mc.options)),
                    null));
        }

        if (GraphicsCapabilities.shaderPackActive()) {
            boolean canOpen = IrisPackControl.canOpenSettings();
            tips.add(new Tip("shaders", Component.translatable(KEY + "shaders"),
                    canOpen ? Component.translatable(KEY + "shaders.button") : null,
                    canOpen ? () -> IrisPackControl.openSettings(parent) : null,
                    null));
        }

        if (ClientDisplayConfig.isRideSnapshotsEnabled()) {
            int edge = RideSnapshotCapture.currentGalleryEdge();
            if (edge > PHOTO_EDGE_SUGGESTED) {
                tips.add(new Tip("photos",
                        Component.translatable(KEY + "photos", edge, PHOTO_EDGE_SUGGESTED),
                        Component.translatable(KEY + "photos.button"),
                        () -> ClientDisplayConfig.setRideSnapshotMaxResolution(PHOTO_EDGE_SUGGESTED),
                        null));
            }
        }

        // The carriage count is a server setting: only a singleplayer world's owner can change it.
        if (mc.getSingleplayerServer() != null) {
            tips.add(new Tip("carriages", Component.translatable(KEY + "carriages"),
                    Component.translatable(KEY + "carriages.button"),
                    () -> mc.setScreen(new DungeonTrainSettingsScreen(parent)),
                    null));
        }

        long heap = MachineSpecs.maxHeapBytes();
        long machine = MachineSpecs.physicalMemoryBytes();
        int recommended = GraphicsCapabilities.distantHorizonsActive() ? MEMORY_RECOMMENDED_DH_GB : MEMORY_RECOMMENDED_GB;
        if (heap > 0 && heap < (recommended - 0.5) * GB && machine >= MEMORY_MACHINE_MIN_GB * GB) {
            String allocated = String.format(Locale.ROOT, "%.0f", heap / (double) GB);
            tips.add(new Tip("memory", Component.translatable(KEY + "memory", allocated, recommended),
                    null, null, Component.translatable(KEY + "memory.hint")));
        }
        return tips;
    }

    private record ConfigScreen(ModContainer mod, IConfigScreenFactory factory) {}

    /** The config-screen factory Distant Horizons registers with NeoForge, when it has one. */
    private static Optional<ConfigScreen> distantHorizonsSettingsFactory() {
        try {
            Optional<? extends ModContainer> container = ModList.get().getModContainerById("distanthorizons");
            if (container.isEmpty()) return Optional.empty();
            ModContainer mod = container.get();
            return mod.getCustomExtension(IConfigScreenFactory.class).map(f -> new ConfigScreen(mod, f));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private static void openDistantHorizonsSettings(Screen parent) {
        distantHorizonsSettingsFactory().ifPresent(c -> {
            try {
                Minecraft.getInstance().setScreen(c.factory().createScreen(c.mod(), parent));
            } catch (Throwable ignored) {
                // A broken third-party screen must not take the death screen or options down with it.
            }
        });
    }
}
