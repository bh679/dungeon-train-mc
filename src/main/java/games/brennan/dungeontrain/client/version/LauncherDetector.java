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
