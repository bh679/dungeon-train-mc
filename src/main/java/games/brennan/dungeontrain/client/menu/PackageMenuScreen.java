package games.brennan.dungeontrain.client.menu;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.UserContentImporter;
import games.brennan.dungeontrain.editor.UserContentPaths;
import net.minecraft.Util;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Sub-menu reached from {@link EditorMenuScreen} via the "Package" drill-in.
 * Surfaces the three export-related actions side by side with a read-only
 * companion panel listing every user-authored template — see
 * {@link UserTemplatesScreen}.
 *
 * <p>Entries:
 * <ul>
 *   <li><b>Export Package</b> — dispatches {@code /dungeontrain editor
 *       export} on the server, same as the previous Export Content row.</li>
 *   <li><b>Contents Folder</b> — opens
 *       {@code <config>/dungeontrain/user/} in the OS file manager so the
 *       player can inspect or hand-edit the saved templates.</li>
 *   <li><b>Open Package Folder</b> — opens {@code <game>/exports/} so
 *       previously-exported zips are easy to find for sharing.</li>
 *   <li><b>Open Import Folder | Reload</b> — split row. The left side opens
 *       {@code <game>/imports/}, the drop-zone that {@link UserContentImporter}
 *       scans on every server start. The right side dispatches
 *       {@code /dungeontrain editor import}, which runs the same scan now
 *       and refreshes every editor registry so newly-dropped zips become
 *       visible without a restart.</li>
 * </ul>
 *
 * <p>The folder-open rows use {@link CommandMenuEntry.ClientAction} +
 * {@link Util#getPlatform()} so they sidestep the slash-command pipeline
 * (which would round-trip through the server) and run purely client-side.
 * On the rare path where the folder doesn't exist yet (a brand-new install
 * that has never exported) the directory is created first so the OS file
 * manager has something to open.</p>
 */
public final class PackageMenuScreen implements MenuScreen {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override public String title() { return "Package"; }

    @Override public List<CommandMenuEntry> entries() {
        return List.of(
            new CommandMenuEntry.Run("Export Package", "dungeontrain editor export"),
            new CommandMenuEntry.ClientAction("Contents Folder", PackageMenuScreen::openUserContentFolder),
            new CommandMenuEntry.ClientAction("Open Package Folder", PackageMenuScreen::openExportFolder),
            new CommandMenuEntry.Split(
                new CommandMenuEntry.ClientAction("Open Import Folder", PackageMenuScreen::openImportFolder),
                new CommandMenuEntry.Stay("Reload", "dungeontrain editor import"),
                0.70
            ),
            new CommandMenuEntry.Back("< Back")
        );
    }

    @Override public MenuScreen sidePanel() {
        return new UserTemplatesScreen();
    }

    private static void openUserContentFolder() {
        openFolder(UserContentPaths.root(), "user-content folder");
    }

    private static void openExportFolder() {
        openFolder(FMLPaths.GAMEDIR.get().resolve("exports"), "export folder");
    }

    private static void openImportFolder() {
        openFolder(UserContentImporter.directory(), "import folder");
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
