package games.brennan.dungeontrain.editor.workbench;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.ContainerContentsStore;
import games.brennan.dungeontrain.editor.TemplateSidecars;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Brings what a staged build's tools wrote while it stood in the Workbench back into its sidecar
 * document, so a commit installs the build as it was left rather than as it arrived.
 *
 * <p>The variant candidates and room settings are written into the document as they are edited
 * ({@link WorkbenchVariantStore}, {@link WorkbenchStagedPlot#roomSettings}). The chests are not:
 * the container menu writes a store file of its own ({@link WorkbenchStagingStore#containersFile}),
 * because that store is shared machinery keyed by plot and file. This folds that file in.</p>
 */
public final class WorkbenchSidecarFold {

    private static final Logger LOGGER = LogUtils.getLogger();

    private WorkbenchSidecarFold() {}

    /**
     * {@code stagedId}'s sidecar document with its chest store folded in, written back to the shelf
     * as well. A build whose chests were never opened here is handed back unchanged.
     */
    public static String fold(String stagedId) {
        String doc = WorkbenchStagingStore.find(stagedId).map(WorkbenchStagedBuild::sidecars).orElse("");
        Path file = WorkbenchStagingStore.containersFile(stagedId);
        if (!Files.isRegularFile(file)) return doc;
        try {
            String folded = TemplateSidecars.withFileText(doc, TemplateSidecars.ROLE_CONTAINERS,
                    Files.readString(file, StandardCharsets.UTF_8));
            WorkbenchStagingStore.replaceSidecars(stagedId, folded);
            return folded;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Workbench: could not fold {}'s chests into its document — committing "
                    + "the chests it arrived with: {}", stagedId, e.toString());
            return doc;
        }
    }

    /** Forget every cache and store pointer {@code stagedId} holds — after it leaves the shelf. */
    public static void forget(String stagedId) {
        WorkbenchVariantStore.invalidate(stagedId);
        ContainerContentsStore.setPathOverride(WorkbenchVariantPlot.keyFor(stagedId), null);
    }
}
