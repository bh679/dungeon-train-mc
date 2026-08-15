package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.VariantGroupResolver;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The carriage a Train Builder world is authoring, as a {@link BlockVariantPlot}.
 *
 * <p>Existing to answer "which build is this?" <b>without asking where the player is standing</b>.
 * The editor's plots are a grid at Y 250 and every mirror path resolves against it, which is why
 * mirroring silently did nothing down here at Y 4. A builder world holds exactly one build, so the
 * answer comes from world data and the toggle works from anywhere — the platform, the track, inside
 * the carriage.</p>
 *
 * <p>Mirror flags read and write {@link DungeonTrainWorldData#builderMirror()} rather than a
 * template sidecar; see {@link BuilderMirrorFlags} for why. The variant-pool entries are inert:
 * the builder has no per-block variant menu yet, and pointing those writes at the template the
 * build was copied from would edit a file the builder never opened. The "V" flag is still stored
 * and carried onto the saved template, so the editor honours it later.</p>
 */
public final class BuilderCarriagePlot implements BlockVariantPlot {

    private final ServerLevel level;
    private final BlockPos origin;
    private final Vec3i footprint;

    private BuilderCarriagePlot(ServerLevel level, BlockPos origin, Vec3i footprint) {
        this.level = level;
        this.origin = origin;
        this.footprint = footprint;
    }

    /**
     * The plot for {@code level}'s build, or null if it isn't a builder world or has no carriage
     * (the track and portal modes park none).
     *
     * @param pos where the player is — used only to pick which of several parked carriages is
     *            meant, never to decide whether there is a plot at all
     */
    public static @Nullable BuilderCarriagePlot of(ServerLevel level, BlockPos pos, CarriageDims dims) {
        if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
            return null;
        }
        List<BoundingBox> volumes = BuilderBounds.volumesFor(level);
        if (volumes.isEmpty()) {
            return null;
        }
        BoundingBox box = volumes.stream()
                .filter(b -> pos != null && b.isInside(pos))
                .findFirst()
                .orElse(volumes.get(0));
        // Size from the box, not from dims: a portal room volume is the author's size and only
        // matches the carriage figures by coincidence.
        return new BuilderCarriagePlot(level, BuilderBounds.originOf(box), BuilderBounds.sizeOf(box));
    }

    @Override
    public String key() {
        // Deliberately not the source variant's id: two builder worlds started from `standard` are
        // different builds, and a key that said `carriage:standard` would let one authorise edits
        // against the other's plot.
        return "builder:carriage";
    }

    @Override
    public BlockPos origin() {
        return origin;
    }

    @Override
    public Vec3i footprint() {
        return footprint;
    }

    // ---- mirror: the whole reason this class exists ----

    private BuilderMirrorFlags flags() {
        return DungeonTrainWorldData.get(level).builderMirror();
    }

    @Override
    public boolean mirrorX() {
        return flags().x();
    }

    @Override
    public boolean mirrorY() {
        return flags().y();
    }

    @Override
    public boolean mirrorZ() {
        return flags().z();
    }

    @Override
    public boolean mirrorVariants() {
        return flags().variants();
    }

    @Override
    public void setMirrorAxes(boolean x, boolean y, boolean z) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        data.setBuilderMirror(new BuilderMirrorFlags(x, y, z, data.builderMirror().variants()));
    }

    @Override
    public void setMirrorVariants(boolean v) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        BuilderMirrorFlags current = data.builderMirror();
        data.setBuilderMirror(new BuilderMirrorFlags(current.x(), current.y(), current.z(), v));
    }

    @Override
    public void save() {
        // Already persisted: the setters write world data, which saves with the world. Nothing to
        // flush, and no sidecar to write until the build is named and saved.
    }

    // ---- variant pools: not authored in the builder (yet) ----

    @Override
    public @Nullable List<VariantState> statesAt(BlockPos localPos) {
        return null;
    }

    @Override
    public void put(BlockPos localPos, List<VariantState> states) {
    }

    @Override
    public boolean remove(BlockPos localPos) {
        return false;
    }

    @Override
    public int lockIdAt(BlockPos localPos) {
        return 0;
    }

    @Override
    public void setLockId(BlockPos localPos, int lockId) {
    }

    @Override
    public Set<BlockPos> positionsWithLockId(int lockId) {
        return Set.of();
    }

    @Override
    public Map<BlockPos, Integer> allLockIds() {
        return Collections.emptyMap();
    }

    @Override
    public Set<BlockPos> allFlaggedPositions() {
        return Set.of();
    }

    @Override
    public int nextFreeLockId() {
        return 1;
    }

    /**
     * An empty resolver — this plot has no lock groups to resolve references between.
     *
     * <p>The same answer the rest of this class gives the v9 lock-group API, and for the same
     * reason: the builder edits one volume with no sidecar behind it until the build is named and
     * saved, so there are no groups, no references, and nothing for the graph to analyse. A resolver
     * over two empty maps reports exactly that, where returning null would make every caller that
     * merely asks the question fall over.</p>
     */
    @Override
    public VariantGroupResolver groupRefs() {
        return new VariantGroupResolver(Collections.emptyMap(), Collections.emptyMap());
    }
}
