package games.brennan.dungeontrain.client.version;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Best-effort detection of the launcher Minecraft is running under, so
 * the version-status widget can link to <em>that</em> launcher's mod page
 * (where the player will actually update from) rather than a generic
 * GitHub release tag.
 *
 * <p>Detection looks at the game directory in two passes:</p>
 * <ol>
 *   <li><strong>Path components</strong> — most launchers anchor instances under
 *       a folder whose name includes their brand (e.g. {@code curseforge/minecraft/Instances/...},
 *       {@code com.modrinth.theseus/profiles/...}).</li>
 *   <li><strong>Signature files</strong> — CurseForge instances contain
 *       {@code minecraftinstance.json}, Modrinth modpacks contain
 *       {@code modrinth.index.json}.</li>
 * </ol>
 *
 * <p>When neither pass matches (Prism, MultiMC, ATLauncher, the vanilla
 * launcher, a portable install, or anything we don't recognise) the
 * detector falls back to <strong>Modrinth</strong>, which the project
 * README marks as the recommended download source.</p>
 *
 * <p>Result is cached after the first call — detection runs at most once
 * per JVM.</p>
 */
public final class LauncherDetector {

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Source { MODRINTH, CURSEFORGE, GITHUB }

    /**
     * The launcher itself, finer-grained than {@link Source}: where {@code Source} only needs to
     * know which download page to send an update to (and lumps Prism, MultiMC and ATLauncher into
     * {@link Source#GITHUB}), help text about a launcher's <em>settings</em> needs to name the
     * actual launcher. {@link #UNKNOWN} covers portable installs and anything unrecognised — callers
     * should fall back to something launcher-neutral rather than guess.
     */
    public enum Launcher { CURSEFORGE, MODRINTH, MINECRAFT_LAUNCHER, PRISM, MULTIMC, ATLAUNCHER, UNKNOWN }

    private static volatile Launcher cachedLauncher;

    private static final String MODRINTH_URL   = "https://modrinth.com/mod/dungeon-train";
    private static final String CURSEFORGE_URL = "https://www.curseforge.com/minecraft/mc-mods/dungeon-train";
    private static final String GITHUB_URL     = "https://github.com/bh679/dungeon-train-mc/releases";

    private static volatile Source cached;

    private LauncherDetector() {}

    public static String getUpdateUrl() {
        return urlFor(source());
    }

    public static Source source() {
        Source s = cached;
        if (s == null) {
            s = detect();
            cached = s;
            LOGGER.info("LauncherDetector: detected launcher = {}", s);
        }
        return s;
    }

    /** The launcher this game was started from; see {@link Launcher}. Cached after the first call. */
    public static Launcher launcher() {
        Launcher l = cachedLauncher;
        if (l == null) {
            l = detectLauncher();
            cachedLauncher = l;
            LOGGER.info("LauncherDetector: detected launcher app = {}", l);
        }
        return l;
    }

    private static Launcher detectLauncher() {
        try {
            Path gameDir = FMLPaths.GAMEDIR.get().toAbsolutePath();
            return classify(gameDir.toString(),
                Files.exists(gameDir.resolve("minecraftinstance.json")),
                Files.exists(gameDir.resolve("modrinth.index.json")));
        } catch (Throwable t) {
            LOGGER.debug("LauncherDetector: launcher-app probe failed: {}", t.toString());
            return Launcher.UNKNOWN;
        }
    }

    /**
     * Pure classification behind {@link #launcher()}: the same brand-in-path and signature-file
     * rules as {@link #detect()}, but naming each launcher rather than its download page. The
     * official Minecraft Launcher has no brand in its path, so it is recognised last, by its default
     * game folder — {@code .minecraft} (Windows, Linux) or {@code Application Support/minecraft}
     * (macOS). Anything else is {@link Launcher#UNKNOWN}.
     */
    static Launcher classify(String gameDirPath, boolean hasCurseForgeSignature, boolean hasModrinthSignature) {
        String p = gameDirPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.contains("curseforge")) return Launcher.CURSEFORGE;
        if (p.contains("modrinth") || p.contains("theseus")) return Launcher.MODRINTH;
        if (p.contains("prismlauncher")) return Launcher.PRISM;
        if (p.contains("multimc")) return Launcher.MULTIMC;
        if (p.contains("atlauncher")) return Launcher.ATLAUNCHER;
        if (hasCurseForgeSignature) return Launcher.CURSEFORGE;
        if (hasModrinthSignature) return Launcher.MODRINTH;
        if (p.endsWith("/.minecraft") || p.endsWith("/application support/minecraft")) {
            return Launcher.MINECRAFT_LAUNCHER;
        }
        return Launcher.UNKNOWN;
    }

    private static String urlFor(Source s) {
        return switch (s) {
            case MODRINTH   -> MODRINTH_URL;
            case CURSEFORGE -> CURSEFORGE_URL;
            case GITHUB     -> GITHUB_URL;
        };
    }

    private static Source detect() {
        Path gameDir;
        try {
            gameDir = FMLPaths.GAMEDIR.get();
        } catch (Throwable t) {
            LOGGER.warn("LauncherDetector: FMLPaths.GAMEDIR unavailable ({}); defaulting to Modrinth", t.toString());
            return Source.MODRINTH;
        }

        String pathLower = gameDir.toAbsolutePath().toString().toLowerCase(Locale.ROOT);
        if (pathLower.contains("curseforge")) return Source.CURSEFORGE;
        if (pathLower.contains("modrinth") || pathLower.contains("theseus")) return Source.MODRINTH;
        if (pathLower.contains("prismlauncher")
                || pathLower.contains("multimc")
                || pathLower.contains("atlauncher")) {
            return Source.GITHUB;
        }

        try {
            if (Files.exists(gameDir.resolve("minecraftinstance.json"))) return Source.CURSEFORGE;
            if (Files.exists(gameDir.resolve("modrinth.index.json")))    return Source.MODRINTH;
        } catch (Throwable t) {
            LOGGER.debug("LauncherDetector: signature-file probe failed: {}", t.toString());
        }

        return Source.MODRINTH;
    }
}
