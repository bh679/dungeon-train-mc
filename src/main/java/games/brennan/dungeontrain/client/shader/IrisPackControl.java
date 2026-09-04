package games.brennan.dungeontrain.client.shader;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.GraphicsCapabilities;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Turns Iris' shader pack on, off, or over to another one — the single place that knows how.
 *
 * <p>Iris' public {@code IrisApi} can only report whether a pack is in use and toggle shaders; it
 * cannot name or choose one. So the pack is set the way Iris' own screen sets it: write the name
 * into {@code IrisConfig}, save, and call {@code Iris.reload()}. All of it reflectively, so Iris
 * stays off the compile classpath and every call here is inert without it.</p>
 *
 * <p>Extracted from {@link ShaderPackSwitcher} — the F3+7 dev chord that had this first — when the
 * player-facing shader menu needed the same three lines. Behaviour is unchanged; the switcher's
 * extras (the pre-warm, the on-screen announcement) stayed with the switcher because they only make
 * sense in a loaded world, and the menu runs on the title screen where there is none.</p>
 */
public final class IrisPackControl {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile boolean resolved = false;
    private static Method getIrisConfig;
    private static Method setShaderPackName;
    private static Method setShadersEnabled;
    private static Method saveConfig;
    private static Method reload;

    private IrisPackControl() {}

    /** Whether Iris is present and its config seams were found. Everything else no-ops without it. */
    public static boolean available() {
        resolve();
        return reload != null;
    }

    /**
     * The pack {@code config/iris.properties} names, or {@code ""} when shaders are off.
     *
     * <p>Deliberately the configured pack rather than the one Iris reports in use: on the title
     * screen nothing is rendering, so "in use" is false for every pack including the one that will
     * load with the next world.</p>
     */
    public static String currentPackName() {
        return GraphicsCapabilities.configuredShaderPack();
    }

    /** Enable shaders and switch to {@code zipName}. False if Iris is absent or the reload threw. */
    public static boolean apply(String zipName) {
        return set(zipName, true);
    }

    /** Switch shaders off, leaving the pack selection alone. */
    public static boolean disable() {
        return set(null, false);
    }

    private static boolean set(String zipName, boolean enabled) {
        if (!available()) {
            return false;
        }
        try {
            Object config = getIrisConfig.invoke(null);
            setShadersEnabled.invoke(config, enabled);
            if (enabled) {
                setShaderPackName.invoke(config, zipName);
            }
            saveConfig.invoke(config);
            reload.invoke(null);
            LOGGER.info("[DungeonTrain] Iris shaders {}{}", enabled ? "on: " : "off",
                    enabled ? zipName : "");
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Iris pack change failed: {}", t.toString());
            return false;
        }
    }

    private static void resolve() {
        if (resolved) return;
        synchronized (IrisPackControl.class) {
            if (resolved) return;
            try {
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                Class<?> cfg = Class.forName("net.irisshaders.iris.config.IrisConfig");
                getIrisConfig = iris.getMethod("getIrisConfig");
                reload = iris.getMethod("reload");
                setShaderPackName = cfg.getMethod("setShaderPackName", String.class);
                setShadersEnabled = cfg.getMethod("setShadersEnabled", boolean.class);
                saveConfig = cfg.getMethod("save");
            } catch (ClassNotFoundException absent) {
                // No Iris; every entry point here stays inert.
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] Iris pack-switch API unavailable: {}", t.toString());
            } finally {
                resolved = true;
            }
        }
    }
}
