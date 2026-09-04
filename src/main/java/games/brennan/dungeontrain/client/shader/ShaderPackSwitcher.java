package games.brennan.dungeontrain.client.shader;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.TrainDebugState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Cycles the active Iris shader pack in place, on <b>F3 + 7</b>. A testing tool, not a feature.
 *
 * <h2>Why</h2>
 * <p>Every shader finding in this work came from one person watching a moving train, and the only
 * way to compare packs was to quit, edit {@code iris.properties}, and sit through another world
 * load — minutes per pack, and the memory of what the last one looked like fading across the gap.
 * Switching in place makes it seconds, and puts two packs close enough together to actually
 * compare.</p>
 *
 * <p>Iris' public API can only turn shaders on and off, so the pack itself is set the way Iris'
 * own screen does it: write the name into {@code IrisConfig} and call {@code Iris.reload()}. Both
 * are reached reflectively, so Iris stays off the compile classpath and this is inert without it.</p>
 *
 * <p>The reload rebuilds every pipeline, so the Nether and End are pre-warmed again straight after —
 * otherwise the first band crossing on a freshly switched pack pays a compile stall that has
 * nothing to do with what is being tested.</p>
 *
 * <p>Gated on the same debug grant as the other chords, and the whole cycle includes an "off" entry
 * so vanilla is always one step away for a control.</p>
 */
public final class ShaderPackSwitcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A pack reload is heavy; this is well clear of a key repeat and of a double press. */
    private static final long MIN_STEP_MS = 900L;

    /**
     * Packs that do not compile on the pinned Iris, left out of the cycle.
     *
     * <p>Both fail inside Iris' own pipeline creation, which leaves shaders switched off entirely —
     * so cycling into one looks exactly like the switcher breaking. They are pack bugs against this
     * Iris rather than anything of ours; see {@code docs/shaders/compat-matrix.md}.</p>
     */
    private static final List<String> KNOWN_BROKEN = List.of("footage", "solas");

    /** {@code ""} is shaders off — the control, and always in the cycle. */
    private static List<String> packs = null;
    private static int index = 0;
    private static long lastStepAt = 0L;

    private static volatile boolean irisResolved = false;
    private static Method getIrisConfig;
    private static Method setShaderPackName;
    private static Method setShadersEnabled;
    private static Method saveConfig;
    private static Method reload;

    private ShaderPackSwitcher() {}

    /** Advance to the next pack and apply it. No-op without a live debug grant or without Iris. */
    public static void cycle() {
        if (!TrainDebugState.permitted()) return;
        long now = System.currentTimeMillis();
        if (now - lastStepAt < MIN_STEP_MS) return;
        lastStepAt = now;

        resolveIris();
        if (reload == null) {
            announce("Iris not available");
            return;
        }
        List<String> list = packList();
        if (list.size() <= 1) {
            announce("no shader packs in run/shaderpacks");
            return;
        }
        index = (index + 1) % list.size();
        apply(list.get(index), index, list.size());
    }

    /** The label for the diagnostics panel, or {@code ""} when nothing has been switched yet. */
    public static String describe() {
        List<String> list = packs;
        if (list == null || list.isEmpty()) return "";
        return (index + 1) + "/" + list.size() + " " + label(list.get(index));
    }

    private static void apply(String pack, int step, int total) {
        try {
            Object config = getIrisConfig.invoke(null);
            if (pack.isEmpty()) {
                setShadersEnabled.invoke(config, false);
            } else {
                setShadersEnabled.invoke(config, true);
                setShaderPackName.invoke(config, pack);
            }
            saveConfig.invoke(config);
            reload.invoke(null);
            LOGGER.info("[DungeonTrain] Shader pack switched to {} ({}/{})", label(pack), step + 1, total);
            // A reload throws away every pipeline, including the Nether and End ones, so warm them
            // again rather than paying the compile at the next band edge.
            ShaderWorld.reset();
            ShaderWorld.prewarm();
            announce("Shader " + (step + 1) + "/" + total + ": " + label(pack));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Shader pack switch failed: {}", t.toString());
            announce("switch failed: " + t.getClass().getSimpleName());
        }
    }

    /** {@code ""} plus every zip in {@code shaderpacks/}, minus the ones known not to compile. */
    private static List<String> packList() {
        if (packs != null) return packs;
        List<String> found = new ArrayList<>();
        found.add("");
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks");
            if (Files.isDirectory(dir)) {
                try (Stream<Path> stream = Files.list(dir)) {
                    stream.map(p -> p.getFileName().toString())
                        .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".zip"))
                        .filter(n -> KNOWN_BROKEN.stream().noneMatch(b -> n.toLowerCase(Locale.ROOT).contains(b)))
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .forEach(found::add);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not list shaderpacks: {}", t.toString());
        }
        packs = found;
        return packs;
    }

    private static String label(String pack) {
        return pack.isEmpty() ? "OFF (vanilla control)" : pack.replaceFirst("(?i)\\.zip$", "");
    }

    private static void announce(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null) mc.gui.setOverlayMessage(Component.literal(text), false);
    }

    private static void resolveIris() {
        if (irisResolved) return;
        synchronized (ShaderPackSwitcher.class) {
            if (irisResolved) return;
            try {
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                Class<?> cfg = Class.forName("net.irisshaders.iris.config.IrisConfig");
                getIrisConfig = iris.getMethod("getIrisConfig");
                reload = iris.getMethod("reload");
                setShaderPackName = cfg.getMethod("setShaderPackName", String.class);
                setShadersEnabled = cfg.getMethod("setShadersEnabled", boolean.class);
                saveConfig = cfg.getMethod("save");
            } catch (ClassNotFoundException absent) {
                // No Iris; the chord stays inert.
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] Iris pack-switch API unavailable: {}", t.toString());
            } finally {
                irisResolved = true;
            }
        }
    }
}
