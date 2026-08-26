package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Stamps and captures {@link WholeCarriage} builds — one template covering the whole
 * {@code length × height × width} box, shell and interior in a single pass.
 *
 * <p>Simpler than {@link CarriagePlacer} on purpose. That class stamps a shell, then rolls a
 * contents template over the top, then applies per-variant block pools; a whole carriage went onto
 * disk as one capture and comes back as one stamp, with nothing rolled against it.</p>
 *
 * <p>Relights. Whole carriages are placed into builder worlds — plots a player stands in and looks
 * at — never into a train being lifted into a Sable sub-level, so there is no relight pass
 * downstream to inherit and the section-local fast path in {@code CarriagePlacer} would leave the
 * build dark.</p>
 *
 * <p>No fallback. A carriage shell with no saved template drops to the hardcoded generator, because
 * a train always needs <em>a</em> carriage. Nothing needs a whole carriage, so a missing file
 * places nothing and says so in the log.</p>
 */
public final class WholeCarriagePlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private WholeCarriagePlacer() {}

    /**
     * Stamp {@code wholeCarriage} at {@code origin}, which is the carriage-shell anchor — the same
     * corner {@link CarriagePlacer#placeAt} uses, so a whole carriage lands exactly where the shell
     * it was built on would have.
     *
     * <p><b>Erases first.</b> A capture excludes air cells (that is what the {@code toIgnore}
     * argument to {@code fillFromWorld} does), so stamping alone can only ever add blocks — every
     * empty cell in the template leaves whatever was already in the world untouched. For a template
     * that <em>is</em> the whole volume, that is wrong in a way you can walk into: a build with a
     * doorway cut through it, stamped over a shell, gets the shell's wall back across the doorway.
     * The same reasoning is why {@code Template.Carriage.eraseEditorPlot} exists.</p>
     *
     * @return true when a template was found and placed
     */
    public static boolean placeAt(ServerLevel level, BlockPos origin, WholeCarriage wholeCarriage,
                                  CarriageDims dims) {
        Optional<StructureTemplate> template = WholeCarriageTemplateStore.get(level, wholeCarriage, dims);
        if (template.isEmpty()) {
            LOGGER.warn("[DungeonTrain] No whole-carriage template for '{}' — placing nothing.",
                wholeCarriage.id());
            return false;
        }
        CarriagePlacer.eraseAt(level, origin, dims);
        // Entities are ignored to match the capture, which fills from world without them.
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
        template.get().placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_ALL);
        return true;
    }

    /**
     * Capture the full carriage volume at {@code origin}.
     *
     * <p>Delegates to {@link CarriageEditor#captureTemplate}, which already fills from the whole
     * {@code dims} box — the interior is inside that box, so a shell capture and a whole-carriage
     * capture are byte-identical operations. The two differ only in which store they are written
     * to, and this method exists to name that difference at the call site.</p>
     */
    public static StructureTemplate captureTemplate(ServerLevel level, BlockPos origin, CarriageDims dims) {
        return CarriageEditor.captureTemplate(level, origin, dims);
    }
}
