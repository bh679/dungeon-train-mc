package games.brennan.dungeontrain.client;
import games.brennan.dungeontrain.platform.DtPlatform;
import games.brennan.dungeontrain.DtCore;

import com.mojang.blaze3d.platform.GlUtil;
import com.mojang.logging.LogUtils;

import games.brennan.dungeontrain.discord.LauncherInfo;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Client-side collector of a short, human-readable <em>system-spec summary</em> for a "Lag"
 * bug report: the memory the launcher allocated to the game ({@code -Xmx} / JVM max heap), total
 * physical RAM, CPU/GPU, current FPS, OS, Java, launcher (CurseForge/Modrinth) and mod versions.
 *
 * <p>The headline figure is the <strong>allocated game memory</strong>: {@code -Xmx} is exactly what
 * CurseForge/Modrinth set when they launch Minecraft, and the JVM exposes it as
 * {@code Runtime.getRuntime().maxMemory()} plus the literal flags from the process input arguments —
 * so no launcher config files need to be read.</p>
 *
 * <p>Every field is best-effort and exception-safe: a section that can't be read is simply omitted,
 * never throwing into the death-screen flow. The assembled text is run through the same home-dir
 * redaction as {@link LogCollector} so GPU/CPU/path strings can't leak the user's account name.</p>
 *
 * <p>Reads {@link GlUtil} GPU strings and {@link Minecraft#getFps()}, so it must be called on the
 * render thread.</p>
 */
public final class SystemSpecCollector {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Cap on the rendered summary (it sits inside a Discord message well under the 2000-char limit). */
    private static final int MAX_CHARS = 1500;

    private SystemSpecCollector() {}

    /** A {@code String} supplier whose body may throw; used to wrap individual {@link GlUtil} probes. */
    @FunctionalInterface
    private interface ThrowingStr {
        String get() throws Throwable;
    }

    /** Build the redacted, length-capped spec summary; returns {@code ""} if nothing could be read. */
    public static String collect() {
        try {
            List<String> lines = new ArrayList<>();
            addMemory(lines);
            addSystem(lines);
            addGraphics(lines);
            addLauncher(lines);
            addVersions(lines);
            if (lines.isEmpty()) {
                return "";
            }
            String text = redact(String.join("\n", lines));
            return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + " …" : text;
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] SystemSpecCollector failed: {}", t.toString());
            return "";
        }
    }

    // ---- Sections (each independently guarded) ----

    private static void addMemory(List<String> lines) {
        try {
            Runtime rt = Runtime.getRuntime();
            long max = rt.maxMemory();
            long committed = rt.totalMemory();
            long used = committed - rt.freeMemory();
            lines.add("Allocated to game (JVM max heap / -Xmx): " + mib(max));
            lines.add("Heap in use: " + mib(used) + " of " + mib(committed) + " committed");
            String flags = memoryFlags();
            if (!flags.isEmpty()) {
                lines.add("Launcher memory flags: " + flags);
            }
            long physical = physicalMemoryBytes();
            if (physical > 0) {
                lines.add("System RAM (physical): " + mib(physical));
            }
        } catch (Throwable ignored) {
            // best-effort: skip the memory block
        }
    }

    private static void addSystem(List<String> lines) {
        try {
            lines.add("OS: " + prop("os.name") + " " + prop("os.version") + " (" + prop("os.arch") + ")");
            lines.add("CPU cores (logical): " + Runtime.getRuntime().availableProcessors());
            lines.add("Java: " + prop("java.version") + " (" + prop("java.vendor") + ")");
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    private static void addGraphics(List<String> lines) {
        try {
            String renderer = safe(GlUtil::getRenderer);
            String vendor = safe(GlUtil::getVendor);
            String gl = safe(GlUtil::getOpenGLVersion);
            String cpu = safe(GlUtil::getCpuInfo);
            if (!cpu.isEmpty()) {
                lines.add("CPU: " + cpu);
            }
            if (!renderer.isEmpty()) {
                lines.add("GPU: " + renderer + (vendor.isEmpty() ? "" : " — " + vendor));
            }
            if (!gl.isEmpty()) {
                lines.add("OpenGL: " + gl);
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                lines.add("FPS (at report time): " + mc.getFps());
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    private static void addLauncher(List<String> lines) {
        try {
            lines.add("Launcher: " + LauncherInfo.describe(false));
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    private static void addVersions(List<String> lines) {
        try {
            lines.add("Versions: MC " + modVer("minecraft")
                    + " · NeoForge " + modVer("neoforge")
                    + " · DungeonTrain " + modVer(DtCore.MOD_ID));
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    // ---- Helpers ----

    /** Extract only the memory-relevant JVM flags ({@code -Xmx}/{@code -Xms}/heap {@code -XX:}) — never the
     *  full argument list, which can contain user paths. */
    private static String memoryFlags() {
        try {
            List<String> out = new ArrayList<>();
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                String a = arg == null ? "" : arg.trim();
                String lower = a.toLowerCase(Locale.ROOT);
                if (lower.startsWith("-xmx") || lower.startsWith("-xms")
                        || (lower.startsWith("-xx:") && lower.contains("heap"))) {
                    out.add(a);
                }
            }
            return String.join(" ", out);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Total physical RAM via the {@code com.sun} OS bean, reflectively so a non-HotSpot JVM degrades. */
    private static long physicalMemoryBytes() {
        try {
            Object os = ManagementFactory.getOperatingSystemMXBean();
            Class<?> sun = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (!sun.isInstance(os)) {
                return 0L;
            }
            Object v = sun.getMethod("getTotalMemorySize").invoke(os);
            return v instanceof Long l ? l : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String modVer(String id) {
        return DtPlatform.get().getModVersion(id).orElse("?");
    }

    private static String mib(long bytes) {
        if (bytes <= 0) {
            return "?";
        }
        double mib = bytes / (1024.0 * 1024.0);
        return mib >= 1024.0
                ? String.format(Locale.ROOT, "%.1f GB", mib / 1024.0)
                : String.format(Locale.ROOT, "%.0f MB", mib);
    }

    private static String safe(ThrowingStr s) {
        try {
            String v = s.get();
            return v == null ? "" : v.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String prop(String key) {
        String v = System.getProperty(key);
        return v == null ? "?" : v.trim();
    }

    private static String redact(String text) {
        try {
            return new String(LogCollector.redactHome(text.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return text;
        }
    }
}
