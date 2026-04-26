package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsTemplate;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Common API over the four block-variant sidecar types so the
 * world-space block-variant menu (and the variant clipboard item) can
 * read / write entries without caring whether the player is standing in
 * a carriage variant, contents, part, or track-side editor plot.
 *
 * <p>Resolution at the player's position cascades through the same four
 * cases as {@link VariantOverlayRenderer#onLevelTick} so the menu's view
 * always matches the overlay particles the player sees:
 * <ol>
 *   <li>{@link CarriageEditor#plotContaining} → carriage variant</li>
 *   <li>{@link CarriageContentsEditor#plotContaining} → contents</li>
 *   <li>{@link CarriagePartEditor#plotContaining} → carriage part</li>
 *   <li>{@link games.brennan.dungeontrain.editor.TrackPlotLocator#locate} → track side</li>
 * </ol></p>
 *
 * <p>{@link #key()} is a stable string used to dedup syncs and verify
 * edit-packet authorisation: {@code "carriage:<variantId>"},
 * {@code "contents:<contentsId>"}, {@code "part:<kind>:<name>"}, or
 * {@code "track:<kind>:<name>"}. Two plots resolved at different times in
 * the same plot return the same key.</p>
 */
public interface BlockVariantPlot {

    /** Stable string key — see class docstring for format. */
    String key();

    /** World-space origin for {@link BlockPos} → localPos translation. */
    BlockPos origin();

    /** Footprint dimensions; bounds-check localPos against this. */
    Vec3i footprint();

    /** Current candidate list at {@code localPos}, or {@code null} if no entry. */
    @Nullable List<VariantState> statesAt(BlockPos localPos);

    /**
     * Replace the candidate list. Caller is responsible for honouring the
     * sidecar's {@code MIN_STATES_PER_ENTRY} invariant — pass at least 2 entries
     * or call {@link #remove} instead.
     */
    void put(BlockPos localPos, List<VariantState> states);

    /** Remove the entry. Returns true if one existed. */
    boolean remove(BlockPos localPos);

    /** Persist to disk. */
    void save() throws IOException;

    /** Lock-id at {@code localPos}; 0 if unlocked or no cell. */
    int lockIdAt(BlockPos localPos);

    /** Set the lock-id for an existing cell. Pass 0 to unlock. */
    void setLockId(BlockPos localPos, int lockId);

    /** Positions in this plot sharing the given lock-id. Empty for {@code lockId == 0}. */
    java.util.Set<BlockPos> positionsWithLockId(int lockId);

    /**
     * Snapshot of every {@code (localPos, lockId)} pair in this plot with
     * {@code lockId > 0}. Defensive copy — callers may iterate freely. Used
     * by the lock-id all-faces overlay to enumerate which cells need labels.
     */
    Map<BlockPos, Integer> allLockIds();

    /**
     * Smallest positive integer not currently used by any cell in this
     * plot as a lock-id. Used by the menu's Lock toolbar button to
     * allocate a new group on cycle.
     */
    int nextFreeLockId();

    /** True when {@code localPos} is strictly inside the footprint. Use for edit/paste paths that mutate the sidecar. */
    default boolean inBounds(BlockPos localPos) {
        Vec3i f = footprint();
        return localPos.getX() >= 0 && localPos.getX() < f.getX()
            && localPos.getY() >= 0 && localPos.getY() < f.getY()
            && localPos.getZ() >= 0 && localPos.getZ() < f.getZ();
    }

    /**
     * True when {@code localPos} is inside the footprint plus a 1-block
     * margin on every axis. Matches the tolerance used by
     * {@link CarriagePartEditor#plotContaining} and the carriage / contents
     * cage outlines, so clicking on a cage-wall block adjacent to the
     * actual part still resolves a sensible cell. Used by the menu's open
     * path; mutating ops still bounds-check via {@link #inBounds}.
     */
    default boolean inBoundsTolerant(BlockPos localPos) {
        Vec3i f = footprint();
        return localPos.getX() >= -1 && localPos.getX() <= f.getX()
            && localPos.getY() >= -1 && localPos.getY() <= f.getY()
            && localPos.getZ() >= -1 && localPos.getZ() <= f.getZ();
    }

    // ---------- Resolution ----------

    /**
     * Resolve the plot the player is currently standing in. Cascade
     * matches {@link VariantOverlayRenderer#onLevelTick} — carriage,
     * then contents, then part, then track-side. Returns {@code null} if
     * the player isn't in any plot.
     */
    static @Nullable BlockVariantPlot resolveAt(ServerPlayer player, CarriageDims dims) {
        BlockPos pos = player.blockPosition();
        CarriageVariant carriage = CarriageEditor.plotContaining(pos, dims);
        if (carriage != null) {
            BlockPos origin = CarriageEditor.plotOrigin(carriage, dims);
            if (origin == null) return null;
            return new CarriagePlot(carriage, origin, new Vec3i(dims.length(), dims.height(), dims.width()), dims);
        }
        CarriageContents contents = CarriageContentsEditor.plotContaining(pos, dims);
        if (contents != null) {
            BlockPos carriageOrigin = CarriageContentsEditor.plotOrigin(contents, dims);
            if (carriageOrigin == null) return null;
            BlockPos interiorOrigin = carriageOrigin.offset(1, 1, 1);
            Vec3i interiorSize = CarriageContentsTemplate.interiorSize(dims);
            return new ContentsPlot(contents, interiorOrigin, interiorSize);
        }
        CarriagePartEditor.PlotLocation partLoc = CarriagePartEditor.plotContaining(pos, dims);
        if (partLoc != null) {
            BlockPos origin = CarriagePartEditor.plotOrigin(partLoc.kind(), partLoc.name(), dims);
            if (origin == null) return null;
            Vec3i partSize = partLoc.kind().dims(dims);
            return new PartPlot(partLoc.kind(), partLoc.name(), origin, partSize);
        }
        TrackPlotLocator.PlotInfo trackLoc = TrackPlotLocator.locate(player, dims);
        if (trackLoc != null) {
            return new TrackPlot(trackLoc.kind(), trackLoc.name(), trackLoc.origin(), trackLoc.footprint());
        }
        return null;
    }

    // ---------- Implementations ----------

    /** Wraps a {@link CarriageVariantBlocks} sidecar. */
    final class CarriagePlot implements BlockVariantPlot {
        private final CarriageVariant variant;
        private final BlockPos origin;
        private final Vec3i footprint;
        private final CarriageVariantBlocks sidecar;
        private final CarriageDims dims;

        CarriagePlot(CarriageVariant variant, BlockPos origin, Vec3i footprint, CarriageDims dims) {
            this.variant = variant;
            this.origin = origin;
            this.footprint = footprint;
            this.dims = dims;
            this.sidecar = CarriageVariantBlocks.loadFor(variant, dims);
        }

        @Override public String key() { return "carriage:" + variant.id(); }
        @Override public BlockPos origin() { return origin; }
        @Override public Vec3i footprint() { return footprint; }
        @Override public List<VariantState> statesAt(BlockPos l) { return sidecar.statesAt(l); }
        @Override public void put(BlockPos l, List<VariantState> s) { sidecar.put(l, s); }
        @Override public boolean remove(BlockPos l) { return sidecar.remove(l); }
        @Override public void save() throws IOException { sidecar.save(variant); }
        @Override public int lockIdAt(BlockPos l) { return sidecar.lockIdAt(l); }
        @Override public void setLockId(BlockPos l, int id) { sidecar.setLockId(l, id); }
        @Override public java.util.Set<BlockPos> positionsWithLockId(int id) { return sidecar.positionsWithLockId(id); }
        @Override public Map<BlockPos, Integer> allLockIds() { return sidecar.allLockIds(); }
        @Override public int nextFreeLockId() { return sidecar.nextFreeLockId(); }
    }

    /** Wraps a {@link CarriageContentsVariantBlocks} sidecar. */
    final class ContentsPlot implements BlockVariantPlot {
        private final CarriageContents contents;
        private final BlockPos origin;
        private final Vec3i footprint;
        private final CarriageContentsVariantBlocks sidecar;

        ContentsPlot(CarriageContents contents, BlockPos interiorOrigin, Vec3i interiorSize) {
            this.contents = contents;
            this.origin = interiorOrigin;
            this.footprint = interiorSize;
            this.sidecar = CarriageContentsVariantBlocks.loadFor(contents, interiorSize);
        }

        @Override public String key() { return "contents:" + contents.id(); }
        @Override public BlockPos origin() { return origin; }
        @Override public Vec3i footprint() { return footprint; }
        @Override public List<VariantState> statesAt(BlockPos l) { return sidecar.statesAt(l); }
        @Override public void put(BlockPos l, List<VariantState> s) { sidecar.put(l, s); }
        @Override public boolean remove(BlockPos l) { return sidecar.remove(l); }
        @Override public void save() throws IOException { sidecar.save(contents); }
        @Override public int lockIdAt(BlockPos l) { return sidecar.lockIdAt(l); }
        @Override public void setLockId(BlockPos l, int id) { sidecar.setLockId(l, id); }
        @Override public java.util.Set<BlockPos> positionsWithLockId(int id) { return sidecar.positionsWithLockId(id); }
        @Override public Map<BlockPos, Integer> allLockIds() { return sidecar.allLockIds(); }
        @Override public int nextFreeLockId() { return sidecar.nextFreeLockId(); }
    }

    /** Wraps a {@link CarriagePartVariantBlocks} sidecar. */
    final class PartPlot implements BlockVariantPlot {
        private final CarriagePartKind kind;
        private final String name;
        private final BlockPos origin;
        private final Vec3i footprint;
        private final CarriagePartVariantBlocks sidecar;

        PartPlot(CarriagePartKind kind, String name, BlockPos origin, Vec3i partSize) {
            this.kind = kind;
            this.name = name;
            this.origin = origin;
            this.footprint = partSize;
            this.sidecar = CarriagePartVariantBlocks.loadFor(kind, name, partSize);
        }

        @Override public String key() { return "part:" + kind.id() + ":" + name; }
        @Override public BlockPos origin() { return origin; }
        @Override public Vec3i footprint() { return footprint; }
        @Override public List<VariantState> statesAt(BlockPos l) { return sidecar.statesAt(l); }
        @Override public void put(BlockPos l, List<VariantState> s) { sidecar.put(l, s); }
        @Override public boolean remove(BlockPos l) { return sidecar.remove(l); }
        @Override public void save() throws IOException { sidecar.save(kind, name); }
        @Override public int lockIdAt(BlockPos l) { return sidecar.lockIdAt(l); }
        @Override public void setLockId(BlockPos l, int id) { sidecar.setLockId(l, id); }
        @Override public java.util.Set<BlockPos> positionsWithLockId(int id) { return sidecar.positionsWithLockId(id); }
        @Override public Map<BlockPos, Integer> allLockIds() { return sidecar.allLockIds(); }
        @Override public int nextFreeLockId() { return sidecar.nextFreeLockId(); }
    }

    /** Wraps a {@link TrackVariantBlocks} sidecar. */
    final class TrackPlot implements BlockVariantPlot {
        private final TrackKind kind;
        private final String name;
        private final BlockPos origin;
        private final Vec3i footprint;
        private final TrackVariantBlocks sidecar;

        TrackPlot(TrackKind kind, String name, BlockPos origin, Vec3i footprint) {
            this.kind = kind;
            this.name = name;
            this.origin = origin;
            this.footprint = footprint;
            this.sidecar = TrackVariantBlocks.loadFor(kind, name, footprint);
        }

        @Override public String key() { return "track:" + kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + name; }
        @Override public BlockPos origin() { return origin; }
        @Override public Vec3i footprint() { return footprint; }
        @Override public List<VariantState> statesAt(BlockPos l) { return sidecar.statesAt(l); }
        @Override public void put(BlockPos l, List<VariantState> s) { sidecar.put(l, s); }
        @Override public boolean remove(BlockPos l) { return sidecar.remove(l); }
        @Override public void save() throws IOException { sidecar.save(kind, name); }
        @Override public int lockIdAt(BlockPos l) { return sidecar.lockIdAt(l); }
        @Override public void setLockId(BlockPos l, int id) { sidecar.setLockId(l, id); }
        @Override public java.util.Set<BlockPos> positionsWithLockId(int id) { return sidecar.positionsWithLockId(id); }
        @Override public Map<BlockPos, Integer> allLockIds() { return sidecar.allLockIds(); }
        @Override public int nextFreeLockId() { return sidecar.nextFreeLockId(); }
    }
}
