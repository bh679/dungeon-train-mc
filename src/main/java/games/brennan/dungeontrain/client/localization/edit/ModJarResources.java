package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reads Dungeon Train's own shipped files straight out of its mod file.
 *
 * <p>Exists for one reason: the narrative books live under {@code data/}, which is the
 * <b>server</b> datapack channel, and the translation editor opens at the title screen where no
 * server (and therefore no server {@code ResourceManager}) exists. The client resource manager
 * only ever sees {@code assets/}.</p>
 *
 * <p>Scoped deliberately to DT's own mod file rather than the whole datapack stack. The editor
 * translates the prose Dungeon Train ships; a book added by somebody's datapack is not ours to
 * put in a submission. Works in a dev run too — {@code IModFile#findResource} hands back a path
 * into a jar filesystem in production and into {@code build/resources/main} in development, and
 * both walk identically.</p>
 *
 * <p>Lang files deliberately do <b>not</b> come through here: those go through the client
 * {@code ResourceManager} so a localization resource pack overriding a lang file is what the
 * editor shows.</p>
 */
public final class ModJarResources {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ModJarResources() {}

    /**
     * Every file under {@code prefix} in DT's mod file, as path → UTF-8 text, sorted by path so
     * the editor's list order is stable across launches. Unreadable entries are skipped with a
     * warning; a missing prefix yields an empty map.
     *
     * @param prefix a directory path with no leading slash, e.g.
     *               {@code data/dungeontrain/narratives}
     * @param suffix only files ending with this are returned (e.g. {@code .json})
     */
    public static Map<String, String> readAll(String prefix, String suffix) {
        Map<String, String> out = new TreeMap<>();
        Path root = resolve(prefix);
        if (root == null || !Files.isDirectory(root)) {
            return out;
        }
        // The mod file's paths come from a jar filesystem in production and a plain directory in
        // a dev run; both walk the same way, but only the jar FS guarantees '/' separators.
        String separator = root.getFileSystem().getSeparator();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String relative = root.relativize(file).toString().replace(separator, "/");
                if (!relative.endsWith(suffix)) {
                    return;
                }
                try {
                    out.put(prefix + "/" + relative, Files.readString(file, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    LOGGER.warn("[DungeonTrain] Translations: could not read {} — {}",
                        file, e.toString());
                }
            });
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: could not walk {} — {}", prefix, e.toString());
        }
        return out;
    }

    /** One file's UTF-8 text, or null when it is absent or unreadable. */
    public static String read(String path) {
        Path file = resolve(path);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: could not read {} — {}", path, e.toString());
            return null;
        }
    }

    /** A path inside DT's mod file, or null when the mod file or that entry is unavailable. */
    private static Path resolve(String path) {
        try {
            IModFileInfo info = ModList.get().getModFileById(DungeonTrain.MOD_ID);
            return info == null ? null : info.getFile().findResource(path);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: mod file unavailable — {}", e.toString());
            return null;
        }
    }
}
