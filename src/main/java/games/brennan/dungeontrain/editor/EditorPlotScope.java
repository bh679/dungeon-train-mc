package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * The editor plot a player is standing in, reduced to the three things the
 * undo / redo system needs: a stable key to file history under, and an origin +
 * footprint to bound recording with.
 *
 * <p>Deliberately resolved through {@link EditorCategory#locate} rather than
 * {@link BlockVariantPlot#resolveAt}: the latter is the richer handle (it
 * carries the variant sidecar and the mirror axes) but its cascade has no
 * portal-room arm, so a recorder built on it alone would silently skip portal
 * rooms. {@code locate} covers every category, and {@link Template} exposes a
 * uniform {@link Template#editorPlotOrigin} / {@link Template#plotSize} pair to
 * turn its result into a box.</p>
 *
 * <p>For contents plots the box is the whole carriage shell rather than the
 * interior {@code BlockVariantPlot} works in. That is deliberate — a superset
 * is the right bound for "did the author edit inside their plot", and it keeps
 * cage-wall edits undoable too.</p>
 */
public record EditorPlotScope(String key, BlockPos origin, Vec3i size) {

    /**
     * Resolve the plot {@code player} is standing in, or empty when they are
     * outside every editor plot (or the plot has no registered origin — an
     * unknown contents id, a missing carriage variant).
     */
    public static Optional<EditorPlotScope> resolveAt(ServerPlayer player, ServerLevel level) {
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        Optional<EditorCategory.Located> located = EditorCategory.locate(player, dims);
        if (located.isEmpty()) return Optional.empty();

        Template model = located.get().model();
        BlockPos origin = model.editorPlotOrigin(level, dims);
        if (origin == null) return Optional.empty();
        Vec3i size = model.plotSize(dims);
        if (size == null || size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new EditorPlotScope(keyFor(located.get()), origin, size));
    }

    /**
     * Stable history key for a located plot: {@code "<category>:<kind>:<id>:<name>"}.
     *
     * <p>All four segments are needed. {@link Template#id()} alone is not unique
     * — every portal room returns the kind tag {@code "portal_room"} and gets
     * told apart by {@link Template#variantName()}, while carriages are the
     * other way round. Two plots resolved at different moments in the same plot
     * produce the same key, which is the whole contract.</p>
     */
    private static String keyFor(EditorCategory.Located located) {
        Template model = located.model();
        return located.category().name() + ":" + model.kind().name()
            + ":" + model.id() + ":" + model.variantName();
    }

    /** True when {@code worldPos} falls inside this plot's box. */
    public boolean contains(BlockPos worldPos) {
        int dx = worldPos.getX() - origin.getX();
        int dy = worldPos.getY() - origin.getY();
        int dz = worldPos.getZ() - origin.getZ();
        return dx >= 0 && dx < size.getX()
            && dy >= 0 && dy < size.getY()
            && dz >= 0 && dz < size.getZ();
    }

    /**
     * True when {@code player} is standing inside any editor plot.
     *
     * <p>Coarse membership test for callers that only need "is this an authoring context?" and
     * don't want the origin / size handle — currently the book-signing and book-held paths, which
     * keep an editor-authored book inert while its author is still building with it (see
     * {@code EditorAuthoredBookTag}).</p>
     *
     * <p>Cheap for the common case: every plot sits at or above {@link EditorLayout#PLOT_Y}, so a
     * player below it is rejected without touching the {@link EditorCategory#locate} cascade or
     * the world's {@code CarriageDims}.</p>
     */
    public static boolean isInsideAnyPlot(ServerPlayer player) {
        if (player == null) return false;
        if (player.blockPosition().getY() < EditorLayout.PLOT_Y) return false;
        ServerLevel level = player.serverLevel();
        if (level == null) return false;
        return resolveAt(player, level).isPresent();
    }

    /** Total cell count of the box — used to reject region diffs that would blow the step cap. */
    public int volume() {
        return size.getX() * size.getY() * size.getZ();
    }
}
