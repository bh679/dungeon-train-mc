package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Client-side probe of the player's <em>graphics stack</em> — whether Distant Horizons is present, an
 * Iris/Oculus shaderpack is active, and the vanilla graphics mode (Fast / Fancy / Fabulous).
 *
 * <p>Used by the ride-photo capture path ({@link games.brennan.dungeontrain.client.snapshot.RideSnapshotCapture})
 * to decide when a shot is worth storing/uploading at a higher resolution — a Distant Horizons render with
 * shaders or Fabulous graphics is the case where the extra pixels actually carry detail — and to stamp a
 * compact {@code gfx} facet on each uploaded photo so the relay Photos page records how it was rendered.</p>
 *
 * <p>Every probe is best-effort and exception-safe: neither Iris nor Distant Horizons is a compile
 * dependency, so the Iris API is reached reflectively (its handle cached after the first successful
 * lookup) and any failure degrades to "not active" rather than throwing into the render path. Reads
 * {@link Minecraft} options and (indirectly) the Iris render state, so it must be called on the render
 * thread — the same place {@code RideSnapshotCapture#grab} already samples per-photo context.</p>
 */
public final class GraphicsCapabilities {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** NeoForge mod id for Distant Horizons (both the 1.21 and legacy jars register under this id). */
    private static final String DISTANT_HORIZONS_ID = "distanthorizons";

    /**
     * Iris' stable public API. Oculus (the Forge/NeoForge fork) exposes the very same
     * {@code net.irisshaders.iris.api.v0.IrisApi} type, so one reflective path covers both.
     */
    private static final String IRIS_API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";

    // Reflection handles resolved once, lazily. `resolved` guards the one-time attempt (so a missing
    // Iris doesn't re-scan the classpath every capture); the methods stay null when Iris is absent.
    private static volatile boolean irisResolved = false;
    private static Object irisApiInstance;
    private static Method isShaderPackInUse;
    private static Method getCurrentPackName;

    // Memo of config/iris.properties' shaderPack, keyed on the file's mtime.
    private static volatile String irisConfigPackName = "";
    private static volatile long irisConfigStamp = Long.MIN_VALUE;

    private GraphicsCapabilities() {}

    /** Whether Distant Horizons is loaded. Loaded is treated as "in use" — DH has no cheap, stable
     *  per-world "currently rendering" probe, and a player who installed it is rendering LODs in practice. */
    public static boolean distantHorizonsActive() {
        try {
            return ModList.get().isLoaded(DISTANT_HORIZONS_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether an Iris/Oculus shaderpack is currently active (not merely installed). {@code false} if
     *  Iris is absent or the API cannot be reached. */
    public static boolean shaderPackActive() {
        try {
            resolveIris();
            if (irisApiInstance == null || isShaderPackInUse == null) return false;
            Object v = isShaderPackInUse.invoke(irisApiInstance);
            return v instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The active Iris/Oculus shaderpack's name (e.g. {@code "ComplementaryReimagined_r5.8.1.zip"}), or
     * {@code ""} when no pack is active or Iris is absent.
     *
     * <p><b>The API cannot be relied on for this.</b> {@code IrisApi} v0 as shipped in Iris
     * 1.8.14 declares no pack-name getter at all — {@code isShaderPackInUse}, {@code getConfig},
     * {@code getSunPathRotation} and little else — so the reflective lookup finds nothing and this
     * returned {@code ""} for every pack. It is read reflectively anyway in case a later API adds
     * one, and falls back to {@code config/iris.properties}, which is where Iris persists the
     * active pack and is authoritative for what is loaded right now.</p>
     *
     * <p>The fallback is what makes the pack name usable at all: the photo {@code gfx} facet
     * ({@code SnapshotMeta}) and the shader-compatibility matrix both key on it, and an empty
     * name silently collapses every pack into one bucket.</p>
     */
    public static String shaderPackName() {
        try {
            resolveIris();
            if (!shaderPackActive()) return "";
            if (irisApiInstance != null && getCurrentPackName != null) {
                Object v = getCurrentPackName.invoke(irisApiInstance);
                if (v instanceof String s && !s.isEmpty()) return s;
            }
            return packNameFromIrisConfig();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * {@code shaderPack} out of {@code <gameDir>/config/iris.properties}, memoised against the
     * file's modification time so switching packs in the Video Settings GUI is picked up without
     * re-reading the file on every frame.
     */
    private static String packNameFromIrisConfig() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gameDirectory == null) return "";
            Path file = mc.gameDirectory.toPath().resolve("config").resolve("iris.properties");
            if (!Files.isRegularFile(file)) return "";
            long stamp = Files.getLastModifiedTime(file).toMillis();
            if (stamp == irisConfigStamp) return irisConfigPackName;
            String name = readShaderPackKey(file);
            irisConfigPackName = name;
            irisConfigStamp = stamp;
            return name;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readShaderPackKey(Path file) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        String name = props.getProperty("shaderPack");
        return name == null ? "" : name.trim();
    }

    /** The vanilla graphics mode (FAST / FANCY / FABULOUS), or {@code null} if it can't be read. */
    public static GraphicsMode graphicsMode() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.options == null) return null;
            OptionInstance<net.minecraft.client.GraphicsStatus> opt = mc.options.graphicsMode();
            net.minecraft.client.GraphicsStatus status = opt == null ? null : opt.get();
            if (status == null) return null;
            return switch (status) {
                case FAST -> GraphicsMode.FAST;
                case FANCY -> GraphicsMode.FANCY;
                case FABULOUS -> GraphicsMode.FABULOUS;
            };
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether this frame is worth capturing/uploading at a higher resolution: Distant Horizons active
     * <em>and</em> (a shaderpack active <em>or</em> Fabulous graphics). This is the exact trigger the
     * feature is scoped to — the combination where the extra pixels carry real detail.
     */
    public static boolean wantsHiRes() {
        return distantHorizonsActive() && (shaderPackActive() || graphicsMode() == GraphicsMode.FABULOUS);
    }

    /**
     * A compact, filterable {@code gfx} facet describing the graphics stack, e.g. {@code "dh+shaders+fabulous"},
     * {@code "dh+fancy"}, {@code "fancy"}, or {@code "fast"}. The graphics mode is always the trailing token so
     * the relay always has a mode; {@code dh}/{@code shaders} lead when present. Never throws — worst case
     * an empty string, which the relay treats as "untagged".
     */
    public static String facet() {
        try {
            List<String> tokens = new ArrayList<>(3);
            if (distantHorizonsActive()) tokens.add("dh");
            if (shaderPackActive()) tokens.add("shaders");
            GraphicsMode mode = graphicsMode();
            if (mode != null) tokens.add(mode.name().toLowerCase(Locale.ROOT));
            return String.join("+", tokens);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Resolve (once) the Iris API singleton + {@code isShaderPackInUse} method, tolerating Iris' absence. */
    private static void resolveIris() {
        if (irisResolved) return;
        synchronized (GraphicsCapabilities.class) {
            if (irisResolved) return;
            try {
                Class<?> api = Class.forName(IRIS_API_CLASS);
                Object instance = api.getMethod("getInstance").invoke(null);
                Method inUse = api.getMethod("isShaderPackInUse");
                irisApiInstance = instance;
                isShaderPackInUse = inUse;
                try {
                    getCurrentPackName = api.getMethod("getCurrentPackName");
                } catch (NoSuchMethodException noName) {
                    // Older Iris API without the pack-name getter — pack name simply stays blank.
                }
            } catch (ClassNotFoundException notLoaded) {
                // Iris/Oculus not installed — normal; stay silent and treat shaders as inactive.
            } catch (Throwable t) {
                LOGGER.debug("[DungeonTrain] Iris API probe unavailable: {}", t.toString());
            } finally {
                irisResolved = true;
            }
        }
    }

    /** Vanilla graphics tiers, decoupled from the {@code net.minecraft} enum for a stable facet vocabulary. */
    public enum GraphicsMode { FAST, FANCY, FABULOUS }
}
