package games.brennan.dungeontrain.editor.surrounding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * One stamp a {@link SurroundingStructureCategory} wants placed relative to the plot currently being
 * edited. {@link #worldOffset()} is <b>relative to the located plot's own editor-origin</b> (i.e. add
 * it to {@code located.model().editorPlotOrigin(level, dims)} to get the absolute world position) —
 * documented here rather than switching to absolute positions so a category's resolver doesn't need
 * the plot origin threaded through every call site, and so the same spec list is reusable for both the
 * SOLID stamp and the GHOST capture pass.
 *
 * <p>{@link #templateSupplier()} is a {@link Supplier} rather than a plain {@link StructureTemplate} so
 * resolution only touches the {@code ServerLevel} (for bundled-resource fallback lookups) at the moment
 * it's actually needed — the same lazy pattern the existing {@code *TemplateStore} classes use.</p>
 */
public record PlacementSpec(
    SurroundingStructureCategory category,
    BlockPos worldOffset,
    Mirror mirror,
    Supplier<Optional<StructureTemplate>> templateSupplier
) {
}
