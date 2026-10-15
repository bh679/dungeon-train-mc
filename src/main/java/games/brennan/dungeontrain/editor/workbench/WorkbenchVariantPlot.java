package games.brennan.dungeontrain.editor.workbench;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.ContainerContentsStore;
import games.brennan.dungeontrain.editor.TemplateSidecars;
import games.brennan.dungeontrain.editor.VariantCopyRoll;
import games.brennan.dungeontrain.editor.VariantCopyScope;
import games.brennan.dungeontrain.editor.VariantGroupResolver;
import games.brennan.dungeontrain.editor.VariantSpan;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.editor.WorkbenchEditor;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A staged build as a {@link BlockVariantPlot} — what gives a build standing in the Workbench the
 * block-variant tools its own kind's editor gives it: the variant menu, clipboard paste, mirror, lock
 * ids, mob ghosts, the outline and the container menu.
 *
 * <p>Every one of those resolves "the plot here" through {@link BlockVariantPlot#resolveAtPos}, whose
 * per-category arms each answer only for their own resident category. This is the Workbench's arm.
 * What a kind's own plot adds on top of the shared document — copy settings for a repeating room,
 * the connect toggle for the carriage placers — follows the staged build's kind
 * ({@link WorkbenchStagedPlot#kind}), so a staged room offers what a Dimensions room offers and a
 * staged carriage what a carriage offers.</p>
 *
 * <p>The candidates are a {@link WorkbenchVariantStore} document and the chests a
 * {@link ContainerContentsStore} pointed at the build's own folder, both edited in place on the
 * shelf and carried into a commit ({@link WorkbenchSidecarFold}).</p>
 */
public final class WorkbenchVariantPlot implements BlockVariantPlot {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Every staged plot's key starts with this; the staged id follows. */
    public static final String KEY_PREFIX = "workbench:";

    private final WorkbenchStagedPlot plot;

    private WorkbenchVariantPlot(WorkbenchStagedPlot plot) {
        this.plot = plot;
    }

    /** The key of {@code stagedId}'s plot. */
    public static String keyFor(String stagedId) {
        return KEY_PREFIX + stagedId;
    }

    /** The staged id {@code key} names, or null when it is not a Workbench plot key. */
    public static @Nullable String stagedIdOfKey(@Nullable String key) {
        if (key == null || !key.startsWith(KEY_PREFIX)) return null;
        String id = key.substring(KEY_PREFIX.length());
        return id.isEmpty() ? null : id;
    }

    /** The plot holding {@code pos}, or null unless the Workbench is resident and a staged build stands there. */
    public static @Nullable WorkbenchVariantPlot at(ServerLevel level, BlockPos pos) {
        Optional<String> stagedId = WorkbenchEditor.plotContaining(level, pos);
        return stagedId.isEmpty() ? null : ofStaged(level, stagedId.get());
    }

    /** {@code stagedId}'s plot, or null when it is not staged or has no slot — what a menu re-resolves by key. */
    public static @Nullable WorkbenchVariantPlot ofStaged(ServerLevel level, String stagedId) {
        Optional<WorkbenchStagedPlot> plot = WorkbenchStagedPlot.of(level, stagedId);
        if (plot.isEmpty()) return null;
        pointContainersStore(stagedId);
        return new WorkbenchVariantPlot(plot.get());
    }

    /** The staged build this plot is. */
    public WorkbenchStagedPlot staged() {
        return plot;
    }

    /**
     * Point the chest store at this build's own file, seeding it from the document the first time —
     * idempotent, so it can sit on the resolve path every menu open goes through.
     */
    private static void pointContainersStore(String stagedId) {
        Path file = WorkbenchStagingStore.containersFile(stagedId);
        if (!Files.isRegularFile(file)) {
            Optional<String> carried = WorkbenchStagingStore.find(stagedId)
                    .flatMap(b -> TemplateSidecars.containersTextOf(b.sidecars()));
            if (carried.isPresent()) {
                try {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, carried.get(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    LOGGER.warn("[DungeonTrain] Workbench: could not seed {}'s chest store: {}", stagedId, e.toString());
                }
            }
        }
        ContainerContentsStore.setPathOverride(keyFor(stagedId), file);
    }

    private TrackVariantBlocks doc() {
        return WorkbenchVariantStore.loadFor(plot.stagedId(), plot.size());
    }

    @Override public String key() { return keyFor(plot.stagedId()); }
    @Override public BlockPos origin() { return plot.origin(); }
    @Override public Vec3i footprint() { return plot.size(); }
    @Override public String dirtySnapshotKey() { return WorkbenchEditor.snapshotKey(plot.stagedId()); }

    @Override
    public void save() throws IOException {
        BlockVariantPlot.noteEdit(this, null);
        WorkbenchVariantStore.save(plot.stagedId(), doc(), plot.size());
    }

    @Override public String snapshotJson() { return doc().asJsonText(); }

    @Override
    public void restoreJson(String json) throws IOException {
        WorkbenchVariantStore.replace(plot.stagedId(), json);
    }

    // ---- what the build's kind adds ----

    /** A staged room repeats as a Dimensions room does, so its cells get the copy settings. */
    @Override
    public boolean supportsCopySettings() {
        return plot.isRoom();
    }

    /** The carriage-side kinds spawn through placers that honour the connect mode; rooms and track don't. */
    @Override
    public boolean supportsConnectMode() {
        BuilderPhotoPaths.Kind kind = plot.kind();
        return kind == BuilderPhotoPaths.Kind.CARRIAGE || kind == BuilderPhotoPaths.Kind.CARRIAGE_GROUP
                || kind == BuilderPhotoPaths.Kind.CONTENTS || kind == BuilderPhotoPaths.Kind.PART;
    }

    // ---- mirror ----

    @Override public boolean mirrorX() { return doc().mirrorX(); }
    @Override public boolean mirrorY() { return doc().mirrorY(); }
    @Override public boolean mirrorZ() { return doc().mirrorZ(); }
    @Override public boolean mirrorVariants() { return doc().mirrorVariants(); }
    @Override public void setMirrorAxes(boolean x, boolean y, boolean z) { doc().setMirrorAxes(x, y, z); }
    @Override public void setMirrorVariants(boolean v) { doc().setMirrorVariants(v); }

    // ---- variant pools ----

    @Override public @Nullable List<VariantState> statesAt(BlockPos localPos) { return doc().statesAt(localPos); }

    @Override
    public void put(BlockPos localPos, List<VariantState> states) {
        doc().put(localPos, states);
        BlockVariantPlot.noteEdit(this, localPos);
    }

    @Override
    public boolean remove(BlockPos localPos) {
        BlockVariantPlot.noteEdit(this, localPos);
        return doc().remove(localPos);
    }

    @Override public int lockIdAt(BlockPos localPos) { return doc().lockIdAt(localPos); }
    @Override public void setLockId(BlockPos localPos, int lockId) { doc().setLockId(localPos, lockId); }
    @Override public Set<BlockPos> positionsWithLockId(int lockId) { return doc().positionsWithLockId(lockId); }
    @Override public Map<BlockPos, Integer> allLockIds() { return doc().allLockIds(); }
    @Override public int nextFreeLockId() { return doc().nextFreeLockId(); }
    @Override public VariantSpan spanAt(BlockPos localPos) { return doc().spanAt(localPos); }
    @Override public void setSpan(BlockPos localPos, VariantSpan span) { doc().setSpan(localPos, span); }
    @Override public VariantCopyRoll copyRollAt(BlockPos localPos) { return doc().copyRollAt(localPos); }
    @Override public void setCopyRoll(BlockPos localPos, VariantCopyRoll roll) { doc().setCopyRoll(localPos, roll); }
    @Override public VariantCopyScope copyScopeAt(BlockPos localPos) { return doc().copyScopeAt(localPos); }
    @Override public void setCopyScope(BlockPos localPos, VariantCopyScope scope) { doc().setCopyScope(localPos, scope); }
    @Override public VariantGroupResolver groupRefs() { return doc().groupRefs(); }

    @Override
    public Set<BlockPos> allFlaggedPositions() {
        Set<BlockPos> out = new LinkedHashSet<>();
        for (CarriageVariantBlocks.Entry e : doc().entries()) out.add(e.localPos());
        return out;
    }
}
