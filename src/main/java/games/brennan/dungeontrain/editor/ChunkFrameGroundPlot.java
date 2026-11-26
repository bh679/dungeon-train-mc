package games.brennan.dungeontrain.editor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMeta;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMetaStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An Endless open frame's ground ({@link ChunkFrameMeta#ground}) presented as a one-cell
 * {@link BlockVariantPlot}, so the Block Variant menu authors it — the frame twin of
 * {@code PortalRoomCopiesPlot}, and inert in the same places for the same reasons: one cell has no
 * lock group and no box to mirror across.
 *
 * <p>The value lives in the frame's {@code .frame.json}, so saving writes the meta and undo restores
 * the meta's text as it stood.</p>
 */
public final class ChunkFrameGroundPlot implements BlockVariantPlot {

    /** Key prefix, alongside {@code copies:} and the frame plot's own. */
    public static final String KEY_PREFIX = "frameground:";

    /** The one address this plot has. */
    public static final BlockPos CELL = BlockPos.ZERO;

    private static final Vec3i FOOTPRINT = new Vec3i(1, 1, 1);

    private final String frame;
    private final BlockPos origin;
    private List<VariantState> ground;

    public ChunkFrameGroundPlot(String frame, BlockPos origin) {
        this.frame = frame;
        this.origin = origin;
        this.ground = ChunkFrameMetaStore.get(frame).ground();
    }

    public static boolean isGroundKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }

    /** The frame a ground key names, or null for any other key. */
    @Nullable
    public static String frameOf(String key) {
        return isGroundKey(key) ? key.substring(KEY_PREFIX.length()) : null;
    }

    public String frame() {
        return frame;
    }

    @Override public String key() { return KEY_PREFIX + frame; }
    @Override public String dirtySnapshotKey() { return ChunkFrameEditor.snapshotKey(frame); }
    @Override public BlockPos origin() { return origin; }
    @Override public Vec3i footprint() { return FOOTPRINT; }

    @Override
    @Nullable
    public List<VariantState> statesAt(BlockPos localPos) {
        return CELL.equals(localPos) && !ground.isEmpty() ? ground : null;
    }

    @Override
    public void put(BlockPos localPos, List<VariantState> states) {
        if (CELL.equals(localPos)) ground = states == null ? List.of() : List.copyOf(states);
    }

    @Override
    public boolean remove(BlockPos localPos) {
        if (!CELL.equals(localPos) || ground.isEmpty()) return false;
        ground = List.of();
        return true;
    }

    @Override
    public void save() throws IOException {
        BlockVariantPlot.noteEdit(this, null);
        ChunkFrameMetaStore.save(frame, ChunkFrameMetaStore.get(frame).withGround(ground),
            EditorDevMode.isEnabled());
    }

    @Override
    public String snapshotJson() {
        return new GsonBuilder().setPrettyPrinting().create().toJson(ChunkFrameMetaStore.get(frame).toJson());
    }

    @Override
    public void restoreJson(String json) throws IOException {
        ChunkFrameMeta meta = ChunkFrameMeta.fromJson(JsonParser.parseString(json).getAsJsonObject());
        ChunkFrameMetaStore.save(frame, meta, EditorDevMode.isEnabled());
    }

    // ---------- inert: a single cell has no group and no box ----------

    @Override public boolean mirrorX() { return false; }
    @Override public boolean mirrorY() { return false; }
    @Override public boolean mirrorZ() { return false; }
    @Override public boolean mirrorVariants() { return false; }
    @Override public void setMirrorAxes(boolean x, boolean y, boolean z) {}
    @Override public void setMirrorVariants(boolean v) {}

    @Override public int lockIdAt(BlockPos localPos) { return 0; }
    @Override public void setLockId(BlockPos localPos, int lockId) {}
    @Override public Set<BlockPos> positionsWithLockId(int lockId) { return Set.of(); }
    @Override public Map<BlockPos, Integer> allLockIds() { return Map.of(); }
    @Override public int nextFreeLockId() { return 1; }

    @Override
    public Set<BlockPos> allFlaggedPositions() {
        return ground.isEmpty() ? Set.of() : Set.of(CELL);
    }

    @Override
    public VariantGroupResolver groupRefs() {
        Map<BlockPos, List<VariantState>> entries = new LinkedHashMap<>();
        if (!ground.isEmpty()) entries.put(CELL, ground);
        return new VariantGroupResolver(entries, new LinkedHashMap<>());
    }
}
