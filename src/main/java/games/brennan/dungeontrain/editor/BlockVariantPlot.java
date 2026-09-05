package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Common API over the four block-variant sidecar types so the
 * world-space block-variant menu (and the variant clipboard item) can
 * read / write entries without caring whether the player is standing in
 * a carriage variant, contents, part, or track-side editor plot.
 *
 * <p>Resolution at a world position cascades through the same four cases as
 * {@link VariantOverlayRenderer#onLevelTick} so the menu's view always matches
 * the overlay HUD the player sees. {@link #resolveAt} asks it of the player's
 * own position; {@link #resolveAtPos} asks it of any position, which is what
 * lets live mirroring key off the edited block instead of the author's feet:
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

    Logger LOGGER = LogUtils.getLogger();

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

    /** Editor mirror X (length) axis for this plot's sidecar. */
    boolean mirrorX();

    /** Editor mirror Y (height) axis for this plot's sidecar. */
    boolean mirrorY();

    /** Editor mirror Z (width) axis for this plot's sidecar. */
    boolean mirrorZ();

    /** Mirror-variants ("V") opt-in for this plot's sidecar — reflect variant pools, not just structural blocks. */
    boolean mirrorVariants();

    /** Set all three editor mirror axes on this plot's sidecar. Caller must {@link #save} to persist. */
    void setMirrorAxes(boolean x, boolean y, boolean z);

    /** Set the mirror-variants ("V") opt-in on this plot's sidecar. Caller must {@link #save} to persist. */
    void setMirrorVariants(boolean v);

    /** Lock-id at {@code localPos}; 0 if unlocked or no cell. */
    int lockIdAt(BlockPos localPos);

    /** Set the lock-id for an existing cell. Pass 0 to unlock. */
    void setLockId(BlockPos localPos, int lockId);

    /** Positions in this plot sharing the given lock-id. Empty for {@code lockId == 0}. */
    java.util.Set<BlockPos> positionsWithLockId(int lockId);

    /**
     * This plot's v9 lock-group reference resolver. The menu uses it to tell
     * live references from dead ones when composing a sync, to reject an Add
     * that would close a cycle, and to preview what a reference row actually
     * resolves to.
     */
    VariantGroupResolver groupRefs();

    /**
     * Snapshot of every {@code (localPos, lockId)} pair in this plot with
     * {@code lockId > 0}. Defensive copy — callers may iterate freely. Used
     * by the lock-id all-faces overlay to enumerate which cells need labels.
     */
    Map<BlockPos, Integer> allLockIds();

    /**
     * Snapshot of every flagged {@code localPos} in this plot — the same set
     * the per-cell candidate menu reads from. Used by the wireframe overlay
     * to draw a 1×1×1 outline around every variant-flagged block.
     */
    java.util.Set<BlockPos> allFlaggedPositions();

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

    /**
     * This plot's variant sidecar, serialised exactly as {@link #save} would
     * write it. The editor undo history snapshots this before and after every
     * sidecar-mutating operation, which is cheaper and far more robust than
     * teaching each op to invert itself.
     *
     * <p>Read from the in-memory sidecar rather than the file on disk: several
     * edit paths defer their write to {@code /dt save}, so the file can lag the
     * state the author is actually looking at.</p>
     */
    String snapshotJson();

    /**
     * Overwrite this plot's sidecar file with {@code json} and drop the cached
     * instance, so the next {@code loadFor} reads the restored document.
     *
     * <p>Callers should re-resolve the plot afterwards and {@link #save} it:
     * that lets each sidecar apply its own "empty means delete the file" rule
     * and perform the dev-mode source write-through, instead of this method
     * duplicating either.</p>
     */
    void restoreJson(String json) throws IOException;

    // ---------- Keys ----------

    /**
     * The four key formats, stated once. {@link #resolveByKey} parses them and every implementation
     * below emits one; the Train Builder names a template it is saving to with them as well, which
     * is what made a second, drifting copy of the string concatenation worth avoiding — see
     * {@link ContainerContentsStore#trackPlotKey} for the bug the last one caused.
     */
    static String carriageKey(String variantId) {
        return "carriage:" + variantId;
    }

    static String contentsKey(String contentsId) {
        return "contents:" + contentsId;
    }

    static String partKey(CarriagePartKind kind, String name) {
        return "part:" + kind.id() + ":" + name;
    }

    static String trackKey(TrackKind kind, String name) {
        return "track:" + kind.id() + ":" + name;
    }

    // ---------- Resolution ----------

    /**
     * Resolve a plot directly by its stable {@link #key()} string. Used by
     * background propagators (e.g.
     * {@link ContainerContentsLinkPropagator}) that need plot origins
     * without a player context.
     *
     * <p>Returns {@code null} if the key doesn't parse, the registered
     * template no longer exists, or the plot's origin can't be resolved.</p>
     */
    /**
     * Resolve by key in a world that may be a Train Builder one.
     *
     * <p>The key-only form below asks the template registries where a plot is, and the builder's
     * build is not in them — its key names a build, not a template. So that arm is answered here,
     * from the level, and everything else falls through unchanged.</p>
     *
     * <p>This is the form the menus use once they are open: a menu is anchored to a plot, so what it
     * re-syncs and edits should follow that anchor rather than the author's feet — which is what
     * lets you stand off a plot, or outside the carriage you are building, and keep working on it.</p>
     */
    static @Nullable BlockVariantPlot resolveByKey(@Nullable net.minecraft.server.level.ServerLevel level,
                                                   String key, CarriageDims dims) {
        if (level != null && games.brennan.dungeontrain.builder.BuilderCarriagePlot.KEY.equals(key)) {
            return games.brennan.dungeontrain.builder.BuilderCarriagePlot.of(level, null, dims);
        }
        return resolveByKey(key, dims);
    }

    static @Nullable BlockVariantPlot resolveByKey(String key, CarriageDims dims) {
        if (key == null) return null;
        if (key.startsWith("carriage:")) {
            String id = key.substring("carriage:".length());
            return games.brennan.dungeontrain.train.CarriageVariantRegistry.find(id)
                .map(v -> {
                    BlockPos origin = CarriageEditor.plotOrigin(v, dims);
                    if (origin == null) return (BlockVariantPlot) null;
                    // The variant's own box — longer than a carriage for the portal corridor.
                    CarriageDims box = CarriageEditor.plotDims(v, dims);
                    return new CarriagePlot(v, origin,
                        new net.minecraft.core.Vec3i(box.length(), box.height(), box.width()), dims);
                })
                .orElse(null);
        }
        if (key.startsWith("contents:")) {
            String id = key.substring("contents:".length());
            return games.brennan.dungeontrain.train.CarriageContentsRegistry.find(id)
                .map(c -> {
                    BlockPos carriageOrigin = CarriageContentsEditor.plotOrigin(c, dims);
                    if (carriageOrigin == null) return (BlockVariantPlot) null;
                    BlockPos interiorOrigin = carriageOrigin.offset(1, 1, 1);
                    net.minecraft.core.Vec3i interiorSize =
                        games.brennan.dungeontrain.train.CarriageContentsPlacer.interiorSizeFor(c, dims);
                    return new ContentsPlot(c, interiorOrigin, interiorSize);
                })
                .orElse(null);
        }
        if (key.startsWith("part:")) {
            String rest = key.substring("part:".length());
            int sep = rest.indexOf(':');
            if (sep < 0) return null;
            String kindId = rest.substring(0, sep);
            String name = rest.substring(sep + 1);
            games.brennan.dungeontrain.train.CarriagePartKind kind =
                games.brennan.dungeontrain.train.CarriagePartKind.fromId(kindId);
            if (kind == null) return null;
            BlockPos origin = CarriagePartEditor.plotOrigin(kind, name, dims);
            if (origin == null) return null;
            net.minecraft.core.Vec3i partSize = kind.dims(dims);
            return new PartPlot(kind, name, origin, partSize);
        }
        if (key.startsWith("track:")) {
            String rest = key.substring("track:".length());
            int sep = rest.indexOf(':');
            if (sep < 0) return null;
            String kindId = rest.substring(0, sep);
            String name = rest.substring(sep + 1);
            games.brennan.dungeontrain.track.variant.TrackKind kind =
                games.brennan.dungeontrain.track.variant.TrackKind.fromId(kindId);
            if (kind == null) return null;
            BlockPos origin = TrackSidePlots.plotOrigin(kind, name, dims);
            if (origin == null) return null;
            net.minecraft.core.Vec3i footprint = kind.dims(dims);
            return new TrackPlot(kind, name, origin, footprint);
        }
        return null;
    }

    /**
     * Resolve the plot the player is currently standing in. Cascade
     * matches {@link VariantOverlayRenderer#onLevelTick} — carriage,
     * then contents, then part, then track-side. Returns {@code null} if
     * the player isn't in any plot.
     *
     * <p>For a plot that should be decided by an edited block rather than by
     * the author's feet — live mirroring, which has to work when you build
     * into a template from outside it — use {@link #resolveAtPos}.</p>
     */
    static @Nullable BlockVariantPlot resolveAt(ServerPlayer player, CarriageDims dims) {
        net.minecraft.server.level.ServerLevel level =
            player.level() instanceof net.minecraft.server.level.ServerLevel sl ? sl : null;
        return resolveAtPos(level, player.blockPosition(), dims);
    }

    /**
     * Resolve the plot containing an arbitrary world position — the same
     * cascade as {@link #resolveAt}, decided by {@code pos} instead of by a
     * player. {@code level} may be {@code null}, which only skips the builder
     * arm (the plot grid itself is purely positional).
     */
    static @Nullable BlockVariantPlot resolveAtPos(@Nullable net.minecraft.server.level.ServerLevel level,
                                                   BlockPos pos, CarriageDims dims) {
        // A builder world holds one build and has no plot grid, so it answers from world data
        // instead of from the position handed in — that's what makes mirror work out on the
        // platform. Checked first, and it costs ordinary worlds one dimension comparison.
        if (level != null) {
            games.brennan.dungeontrain.builder.BuilderCarriagePlot builderPlot =
                games.brennan.dungeontrain.builder.BuilderCarriagePlot.of(level, pos, dims);
            if (builderPlot != null) return builderPlot;
        }
        CarriageVariant carriage = CarriageEditor.plotContaining(pos, dims);
        if (carriage != null) {
            BlockPos origin = CarriageEditor.plotOrigin(carriage, dims);
            if (origin == null) return null;
            // The variant's own box, which is what the live mirror reflects around and what
            // inside() tests against — a carriage-sized footprint on the longer portal corridor
            // would mirror around x=4 instead of x=6 and reject every edit past x=8.
            CarriageDims box = CarriageEditor.plotDims(carriage, dims);
            return new CarriagePlot(carriage, origin, new Vec3i(box.length(), box.height(), box.width()), dims);
        }
        CarriageContents contents = CarriageContentsEditor.plotContaining(pos, dims);
        if (contents != null) {
            BlockPos carriageOrigin = CarriageContentsEditor.plotOrigin(contents, dims);
            if (carriageOrigin == null) return null;
            BlockPos interiorOrigin = carriageOrigin.offset(1, 1, 1);
            Vec3i interiorSize = CarriageContentsPlacer.interiorSizeFor(contents, dims);
            return new ContentsPlot(contents, interiorOrigin, interiorSize);
        }
        CarriagePartEditor.PlotLocation partLoc = CarriagePartEditor.plotContaining(pos, dims);
        if (partLoc != null) {
            BlockPos origin = CarriagePartEditor.plotOrigin(
                new games.brennan.dungeontrain.template.CarriagePartTemplateId(partLoc.kind(), partLoc.name()), dims);
            if (origin == null) return null;
            Vec3i partSize = partLoc.kind().dims(dims);
            return new PartPlot(partLoc.kind(), partLoc.name(), origin, partSize);
        }
        TrackPlotLocator.PlotInfo trackLoc = TrackSidePlots.locate(pos, dims);
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
            // Bounds-checked against this variant's own box, not the world's carriage dims:
            // CarriageVariantBlocks drops any entry outside the dims it is handed, which on the
            // longer portal corridor would silently discard everything authored past x=8.
            this.sidecar = CarriageVariantBlocks.loadFor(variant, CarriageEditor.plotDims(variant, dims));
        }

        @Override public String key() { return carriageKey(variant.id()); }
        @Override public BlockPos origin() { return origin; }
        @Override public Vec3i footprint() { return footprint; }
        @Override public List<VariantState> statesAt(BlockPos l) { return sidecar.statesAt(l); }
        @Override public void put(BlockPos l, List<VariantState> s) { sidecar.put(l, s); }
        @Override public boolean remove(BlockPos l) { return sidecar.remove(l); }
        @Override public void save() throws IOException {
            sidecar.save(variant);
            // Dev-mode write-through: shift-right-click edits should ship in
            // the next build, not stay trapped in run/config. Mirrors PR #75
            // for part-assignments. Built-in only — custom variants live in
            // config alone. Source-tree errors are best-effort.
            if (EditorDevMode.isEnabled() && variant instanceof CarriageVariant.Builtin builtin) {
                try {
                    sidecar.saveToSource(builtin.type());
                } catch (IOException e) {
                    LOGGER.warn("[DungeonTrain] BlockVariantPlot: source write failed for carriage {}: {}",
                        variant.id(), e.toString());
                }
            }
        }
        @Override public String snapshotJson() { return sidecar.toJson(); }
        @Override public void restoreJson(String json) throws IOException {
            Path file = CarriageVariantBlocks.configPathFor(variant);
            Files.createDirectories(file.getParent());
            Files.writeString(file, json, StandardCharsets.UTF_8);
            CarriageVariantBlocks.invalidate(variant.id());
        }
        @Override public int lockIdAt(BlockPos l) { return sidecar.lockIdAt(l); }
        @Override public void setLockId(BlockPos l, int id) { sidecar.setLockId(l, id); }
        @Override public java.util.Set<BlockPos> positionsWithLockId(int id) { return sidecar.positionsWithLockId(id); }
        @Override public VariantGroupResolver groupRefs() { return sidecar.groupRefs(); }
        @Override public Map<BlockPos, Integer> allLockIds() { return sidecar.allLockIds(); }
        @Override public int nextFreeLockId() { return sidecar.nextFreeLockId(); }
        @Override public java.util.Set<BlockPos> allFlaggedPositions() { return collectPositions(sidecar.entries()); }
        @Override public boolean mirrorX() { return sidecar.mirrorX(); }
        @Override public boolean mirrorY() { return sidecar.mirrorY(); }
        @Override public boolean mirrorZ() { return sidecar.mirrorZ(); }
        @Override public boolean mirrorVariants() { return sidecar.mirrorVariants(); }
        @Override public void setMirrorAxes(boolean x, boolean y, boolean z) { sidecar.setMirrorAxes(x, y, z); }
        @Override public void setMirrorVariants(boolean v) { sidecar.setMirrorVariants(v); }
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

        @Override public String key() { return contentsKey(contents.id()); }
        @Override public BlockPos origin() { return origin; }
        @Override public Vec3i footprint() { return footprint; }
        @Override public List<VariantState> statesAt(BlockPos l) { return sidecar.statesAt(l); }
        @Override public void put(BlockPos l, List<VariantState> s) { sidecar.put(l, s); }
        @Override public boolean remove(BlockPos l) { return sidecar.remove(l); }
        @Override public void save() throws IOException {
            sidecar.save(contents);
            if (EditorDevMode.isEnabled()) {
                try {
                    sidecar.saveToSource(contents);
                } catch (IOException e) {
                    LOGGER.warn("[DungeonTrain] BlockVariantPlot: source write failed for contents {}: {}",
                        contents.id(), e.toString());
                }
            }
        }
        @Override public String snapshotJson() { return sidecar.toJsonText(); }
        @Override public void restoreJson(String json) throws IOException {
            Path file = CarriageContentsVariantBlocks.configPathFor(contents);
            Files.createDirectories(file.getParent());
            Files.writeString(file, json, StandardCharsets.UTF_8);
            CarriageContentsVariantBlocks.invalidate(contents.id());
        }
        @Override public int lockIdAt(BlockPos l) { return sidecar.lockIdAt(l); }
        @Override public void setLockId(BlockPos l, int id) { sidecar.setLockId(l, id); }
        @Override public java.util.Set<BlockPos> positionsWithLockId(int id) { return sidecar.positionsWithLockId(id); }
        @Override public VariantGroupResolver groupRefs() { return sidecar.groupRefs(); }
        @Override public Map<BlockPos, Integer> allLockIds() { return sidecar.allLockIds(); }
        @Override public int nextFreeLockId() { return sidecar.nextFreeLockId(); }
        @Override public java.util.Set<BlockPos> allFlaggedPositions() { return collectPositions(sidecar.entries()); }
        @Override public boolean mirrorX() { return sidecar.mirrorX(); }
        @Override public boolean mirrorY() { return sidecar.mirrorY(); }
        @Override public boolean mirrorZ() { return sidecar.mirrorZ(); }
        @Override public boolean mirrorVariants() { return sidecar.mirrorVariants(); }
        @Override public void setMirrorAxes(boolean x, boolean y, boolean z) { sidecar.setMirrorAxes(x, y, z); }
        @Override public void setMirrorVariants(boolean v) { sidecar.setMirrorVariants(v); }
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

        @Override public String key() { return partKey(kind, name); }
        @Override public BlockPos origin() { return origin; }
        @Override public Vec3i footprint() { return footprint; }
        @Override public List<VariantState> statesAt(BlockPos l) { return sidecar.statesAt(l); }
        @Override public void put(BlockPos l, List<VariantState> s) { sidecar.put(l, s); }
        @Override public boolean remove(BlockPos l) { return sidecar.remove(l); }
        @Override public void save() throws IOException {
            sidecar.save(kind, name);
            if (EditorDevMode.isEnabled()) {
                try {
                    sidecar.saveToSource(kind, name);
                } catch (IOException e) {
                    LOGGER.warn("[DungeonTrain] BlockVariantPlot: source write failed for part {}:{}: {}",
                        kind.id(), name, e.toString());
                }
            }
        }
        @Override public String snapshotJson() { return sidecar.toJsonText(); }
        @Override public void restoreJson(String json) throws IOException {
            Path file = CarriagePartVariantBlocks.configPathFor(kind, name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, json, StandardCharsets.UTF_8);
            CarriagePartVariantBlocks.invalidate(kind, name);
        }
        @Override public int lockIdAt(BlockPos l) { return sidecar.lockIdAt(l); }
        @Override public void setLockId(BlockPos l, int id) { sidecar.setLockId(l, id); }
        @Override public java.util.Set<BlockPos> positionsWithLockId(int id) { return sidecar.positionsWithLockId(id); }
        @Override public VariantGroupResolver groupRefs() { return sidecar.groupRefs(); }
        @Override public Map<BlockPos, Integer> allLockIds() { return sidecar.allLockIds(); }
        @Override public int nextFreeLockId() { return sidecar.nextFreeLockId(); }
        @Override public java.util.Set<BlockPos> allFlaggedPositions() { return collectPositions(sidecar.entries()); }
        @Override public boolean mirrorX() { return sidecar.mirrorX(); }
        @Override public boolean mirrorY() { return sidecar.mirrorY(); }
        @Override public boolean mirrorZ() { return sidecar.mirrorZ(); }
        @Override public boolean mirrorVariants() { return sidecar.mirrorVariants(); }
        @Override public void setMirrorAxes(boolean x, boolean y, boolean z) { sidecar.setMirrorAxes(x, y, z); }
        @Override public void setMirrorVariants(boolean v) { sidecar.setMirrorVariants(v); }
    }

    /** Wraps a {@link TrackVariantBlocks} sidecar. */
    final class TrackPlot implements BlockVariantPlot {
        private final TrackKind kind;
        private final String name;
        private final BlockPos origin;
        private final Vec3i footprint;
        private final TrackVariantBlocks sidecar;

        /**
         * Public because the origin is a parameter, and the Train Builder authors these track
         * templates somewhere else entirely — on a plot at ground level rather than in the editor's
         * Y=250 grid. {@link #forKey} can't serve it: that resolver asks {@link TrackSidePlots} where
         * the plot is, which is the one thing a builder plot answers differently.
         */
        public TrackPlot(TrackKind kind, String name, BlockPos origin, Vec3i footprint) {
            this.kind = kind;
            this.name = name;
            this.origin = origin;
            this.footprint = footprint;
            this.sidecar = TrackVariantBlocks.loadFor(kind, name, footprint);
        }

        @Override public String key() { return trackKey(kind, name); }
        @Override public BlockPos origin() { return origin; }
        @Override public Vec3i footprint() { return footprint; }
        @Override public List<VariantState> statesAt(BlockPos l) { return sidecar.statesAt(l); }
        @Override public void put(BlockPos l, List<VariantState> s) { sidecar.put(l, s); }
        @Override public boolean remove(BlockPos l) { return sidecar.remove(l); }
        @Override public void save() throws IOException {
            sidecar.save(kind, name);
            if (EditorDevMode.isEnabled()) {
                try {
                    sidecar.saveToSource(kind, name);
                } catch (IOException e) {
                    LOGGER.warn("[DungeonTrain] BlockVariantPlot: source write failed for track {}:{}: {}",
                        kind.id(), name, e.toString());
                }
            }
        }
        @Override public String snapshotJson() { return sidecar.toJsonText(); }
        @Override public void restoreJson(String json) throws IOException {
            Path file = TrackVariantBlocks.configPathFor(kind, name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, json, StandardCharsets.UTF_8);
            TrackVariantBlocks.invalidate(kind, name);
        }
        @Override public int lockIdAt(BlockPos l) { return sidecar.lockIdAt(l); }
        @Override public void setLockId(BlockPos l, int id) { sidecar.setLockId(l, id); }
        @Override public java.util.Set<BlockPos> positionsWithLockId(int id) { return sidecar.positionsWithLockId(id); }
        @Override public VariantGroupResolver groupRefs() { return sidecar.groupRefs(); }
        @Override public Map<BlockPos, Integer> allLockIds() { return sidecar.allLockIds(); }
        @Override public int nextFreeLockId() { return sidecar.nextFreeLockId(); }
        @Override public java.util.Set<BlockPos> allFlaggedPositions() { return collectPositions(sidecar.entries()); }
        @Override public boolean mirrorX() { return sidecar.mirrorX(); }
        @Override public boolean mirrorY() { return sidecar.mirrorY(); }
        @Override public boolean mirrorZ() { return sidecar.mirrorZ(); }
        @Override public boolean mirrorVariants() { return sidecar.mirrorVariants(); }
        @Override public void setMirrorAxes(boolean x, boolean y, boolean z) { sidecar.setMirrorAxes(x, y, z); }
        @Override public void setMirrorVariants(boolean v) { sidecar.setMirrorVariants(v); }
    }

    /**
     * Helper used by all four wrapper classes — flatten a sidecar's
     * {@code List<Entry>} into a set of localPos. Defensive copy; callers
     * may iterate freely.
     */
    private static java.util.Set<BlockPos> collectPositions(List<CarriageVariantBlocks.Entry> entries) {
        java.util.LinkedHashSet<BlockPos> out = new java.util.LinkedHashSet<>(entries.size());
        for (CarriageVariantBlocks.Entry e : entries) {
            out.add(e.localPos());
        }
        return out;
    }
}
