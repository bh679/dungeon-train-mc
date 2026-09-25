package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.portal.chunkframe.ChunkFrame;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameVariants;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A chunk frame's plot, as the block-variant tools see it — what makes mirror, shift-right-click
 * variants, lock groups, the clipboard, prefabs and container links work inside a frame the way they
 * do inside any other template.
 *
 * <p>The sidecar is a {@link TrackVariantBlocks} kept by {@link ChunkFrameVariants}; this class only
 * adds where the plot stands and routes saves to the frame's own file.</p>
 */
public final class ChunkFramePlot implements BlockVariantPlot {

    /** Key prefix for {@link BlockVariantPlot#resolveByKey}. */
    public static final String KEY_PREFIX = ChunkFrameEditor.MODEL_ID + ":";

    private final String name;
    private final BlockPos origin;
    private final TrackVariantBlocks sidecar;

    public ChunkFramePlot(String name, BlockPos origin) {
        this.name = name;
        this.origin = origin;
        this.sidecar = ChunkFrameVariants.loadFor(name);
    }

    /** The plot of registered frame {@code name}, or null when it has none. */
    static ChunkFramePlot of(String name) {
        BlockPos origin = ChunkFrameEditor.registeredPlotOrigin(name);
        return origin == null ? null : new ChunkFramePlot(name, origin);
    }

    public String name() { return name; }

    @Override public String key() { return KEY_PREFIX + name; }
    @Override public String dirtySnapshotKey() { return null; }
    @Override public Path sidecarFile() { return ChunkFrameVariants.configPathFor(name); }
    @Override public BlockPos origin() { return origin; }
    @Override public Vec3i footprint() { return ChunkFrame.SIZE; }
    @Override public List<VariantState> statesAt(BlockPos l) { return sidecar.statesAt(l); }
    @Override public void put(BlockPos l, List<VariantState> s) { sidecar.put(l, s); BlockVariantPlot.noteEdit(this, l); }
    @Override public boolean remove(BlockPos l) { BlockVariantPlot.noteEdit(this, l); return sidecar.remove(l); }
    @Override public void save() throws IOException {
        BlockVariantPlot.noteEdit(this, null);
        ChunkFrameVariants.save(name, sidecar, EditorDevMode.isEnabled());
    }
    @Override public String snapshotJson() { return sidecar.toJsonText(); }
    @Override public void restoreJson(String json) throws IOException { ChunkFrameVariants.restore(name, json); }
    @Override public int lockIdAt(BlockPos l) { return sidecar.lockIdAt(l); }
    @Override public void setLockId(BlockPos l, int id) { sidecar.setLockId(l, id); }
    @Override public VariantSpan spanAt(BlockPos l) { return sidecar.spanAt(l); }
    @Override public void setSpan(BlockPos l, VariantSpan span) { sidecar.setSpan(l, span); }
    @Override public Set<BlockPos> positionsWithLockId(int id) { return sidecar.positionsWithLockId(id); }
    @Override public VariantGroupResolver groupRefs() { return sidecar.groupRefs(); }
    @Override public Map<BlockPos, Integer> allLockIds() { return sidecar.allLockIds(); }
    @Override public int nextFreeLockId() { return sidecar.nextFreeLockId(); }
    @Override public Set<BlockPos> allFlaggedPositions() {
        Set<BlockPos> out = new LinkedHashSet<>();
        for (CarriageVariantBlocks.Entry e : sidecar.entries()) out.add(e.localPos());
        return out;
    }
    @Override public boolean mirrorX() { return sidecar.mirrorX(); }
    @Override public boolean mirrorY() { return sidecar.mirrorY(); }
    @Override public boolean mirrorZ() { return sidecar.mirrorZ(); }
    @Override public boolean mirrorVariants() { return sidecar.mirrorVariants(); }
    @Override public void setMirrorAxes(boolean x, boolean y, boolean z) { sidecar.setMirrorAxes(x, y, z); }
    @Override public void setMirrorVariants(boolean v) { sidecar.setMirrorVariants(v); }
}
