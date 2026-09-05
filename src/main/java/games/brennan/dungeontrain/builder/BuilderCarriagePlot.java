package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.ContainerContentsStore;
import games.brennan.dungeontrain.editor.VariantGroupResolver;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.LinkedHashSet;
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
 * template sidecar; see {@link BuilderMirrorFlags} for why. The "V" flag is still stored and
 * carried onto the saved template, so the editor honours it later.</p>
 *
 * <p>The variant pools behind the Z menu, and the container contents behind the C menu, are held
 * in this build's <b>own</b> documents inside the world save ({@link BuilderStorePaths}) — not in
 * the template the build was copied from, which the builder never opened and must not edit.
 * {@code BuilderWorldSetup} seeds them from that template on open, and {@code BuilderSave} writes
 * them onto whatever template the build is saved as.</p>
 */
public final class BuilderCarriagePlot implements BlockVariantPlot {

    /**
     * Deliberately not the source variant's id: two builder worlds started from `standard` are
     * different builds, and a key that said `carriage:standard` would let one authorise edits
     * against the other's plot.
     *
     * <p>It is the same constant in <i>every</i> builder world, which is fine because it is only an
     * authorisation and dedup token — the documents it names live per-world (see
     * {@link BuilderStorePaths}), not in a file named after the key.</p>
     */
    static final String KEY = "builder:carriage";

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
        // The C menu's store is keyed by plot key, and this plot's key is the same constant in
        // every builder world — so point that key at this world's own file before anyone loads it.
        // Idempotent, which is what lets it sit on the resolve path every menu open goes through.
        ContainerContentsStore.setPathOverride(KEY, BuilderStorePaths.contentsFile(level));
        // Size from the box, not from dims: a portal room volume is the author's size and only
        // matches the carriage figures by coincidence.
        return new BuilderCarriagePlot(level, BuilderBounds.originOf(box), BuilderBounds.sizeOf(box));
    }

    /**
     * This build's variant sidecar, read on first use and cached by {@link BuilderVariantStore}.
     *
     * <p>Resolved lazily rather than in the constructor: {@link #of} runs on every plot resolve,
     * including the ones that only want the origin or the mirror flags.</p>
     */
    private TrackVariantBlocks doc() {
        return BuilderVariantStore.loadFor(level, footprint);
    }

    @Override
    public String key() {
        return KEY;
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
    public void save() throws IOException {
        // The mirror flags are already persisted — their setters write world data, which saves with
        // the world. The variant cells are not: they live in this build's own sidecar file.
        BuilderVariantStore.save(level, doc(), footprint);
    }

    // ---- sidecar snapshots ----

    @Override
    public VariantGroupResolver groupRefs() {
        return doc().groupRefs();
    }

    /**
     * This build's sidecar as text. Editor undo snapshots a plot's sidecar around each edit; the
     * builder does not reach that history yet ({@link BlockVariantPlot#resolveByKey} understands
     * the four editor prefixes and not this class's key), but the document is real, so answering
     * with it is cheaper than explaining why it is empty.
     */
    @Override
    public String snapshotJson() {
        return doc().asJsonText();
    }

    @Override
    public void restoreJson(String json) throws IOException {
        BuilderVariantStore.replace(level, json);
    }

    // ---- variant pools ----

    @Override
    public @Nullable List<VariantState> statesAt(BlockPos localPos) {
        return doc().statesAt(localPos);
    }

    @Override
    public void put(BlockPos localPos, List<VariantState> states) {
        doc().put(localPos, states);
    }

    @Override
    public boolean remove(BlockPos localPos) {
        return doc().remove(localPos);
    }

    @Override
    public int lockIdAt(BlockPos localPos) {
        return doc().lockIdAt(localPos);
    }

    @Override
    public void setLockId(BlockPos localPos, int lockId) {
        doc().setLockId(localPos, lockId);
    }

    @Override
    public Set<BlockPos> positionsWithLockId(int lockId) {
        return doc().positionsWithLockId(lockId);
    }

    @Override
    public Map<BlockPos, Integer> allLockIds() {
        return doc().allLockIds();
    }

    @Override
    public Set<BlockPos> allFlaggedPositions() {
        Set<BlockPos> out = new LinkedHashSet<>();
        for (CarriageVariantBlocks.Entry e : doc().entries()) {
            out.add(e.localPos());
        }
        return out;
    }

    @Override
    public int nextFreeLockId() {
        return doc().nextFreeLockId();
    }
}
