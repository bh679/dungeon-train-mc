package games.brennan.dungeontrain.client.menu;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.PackageInfo;
import games.brennan.dungeontrain.editor.PackageRegistry;
import games.brennan.dungeontrain.editor.UserContentPaths;
import net.minecraft.Util;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared client-side actions for the package menu — opening the dtpacks
 * root or a specific package's working folder via the OS file manager.
 *
 * <p>Extracted from {@link PackageListScreen} so the worldspace
 * {@code EditorTypeMenuInputHandler} can reuse the same paths without
 * duplicating the folder-creation + URI-launch dance.</p>
 */
public final class PackageMenuActions {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PackageMenuActions() {}

    /** Open the {@code dtpacks/} root in the OS file manager. */
    public static void openDtpacksFolder() {
        openFolder(PackageRegistry.dtpacksRoot(), "dtpacks folder");
    }

    /**
     * Open a specific package's working folder. For the synthetic unsaved
     * pseudo-package this is the user-content root; for any other package
     * it's {@code dtpacks/<name>/}.
     */
    public static void openWorkingFolder(String packageName) {
        Path target = PackageInfo.UNSAVED_NAME.equals(packageName)
            ? UserContentPaths.root()
            : PackageRegistry.dtpacksRoot().resolve(packageName);
        openFolder(target, "package '" + packageName + "' folder");
    }

    private static void openFolder(Path path, String label) {
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Couldn't ensure {} at {}: {}", label, path, e.toString());
        }
        Util.getPlatform().openUri(path.toUri());
        LOGGER.info("[DungeonTrain] Opened {} at {}", label, path);
    }
}
