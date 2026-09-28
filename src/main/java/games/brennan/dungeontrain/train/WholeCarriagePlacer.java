package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import games.brennan.dungeontrain.template.TemplateDecor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
        CarriageStampGuard.run(() -> {
            CarriagePlacer.eraseAt(level, origin, dims);
            // Vanilla's own entity pass stays off, as it does at every stamp site in this mod — the
            // processor chain is in charge of what lands. The decoration follows separately, through
            // TemplateDecor, exactly as CarriagePlacer.stampTemplate does for a shell. It has to:
            // captureTemplate below keeps the author's armor stands, pictures, mobs and minecarts,
            // so a stamp that put none back would lose them on the next save.
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            template.get().placeInWorld(level, origin, origin, settings, level.getRandom(), CarriageStampGuard.STAMP_FLAGS);
            // replace, not spawn: a caller may stamp over a plot nobody cleared first
            // (Template.Carriage, BuilderWorldSetup's open path), and a re-stamp must not hang a
            // second copy of every picture through the first.
            TemplateDecor.replace(level, origin, template.get(), new StructurePlaceSettings(),
                    /*mark*/ null, TemplateDecor.Rule.CARRIAGE);
        });
        return true;
    }

    /**
     * The train path: stamp {@code template} section-local with no relight — the lift into the
     * Sable sub-level relights — and return the footprint the shipyard assembles. Erases first for
     * the reason {@link #placeAt} gives. The caller holds the stage scope.
     */
    public static java.util.Set<BlockPos> placeForTrain(ServerLevel level, BlockPos origin,
                                                        StructureTemplate template, CarriageDims dims) {
        return CarriageStampGuard.call(() -> {
            CarriagePlacer.eraseAt(level, origin, dims);
            CarriagePlacer.stampTemplateAt(level, origin, template, /*relight*/ false);
            return CarriagePlacer.collectFootprint(level, origin, dims);
        });
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
