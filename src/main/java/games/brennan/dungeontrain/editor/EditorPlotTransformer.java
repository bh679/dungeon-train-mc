package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Applies an {@link EditorPlotTransform} to the editor plot a player is standing
 * in — the engine behind {@code /dt editor offset|rotate|flip}.
 *
 * <p><b>Three things are keyed by position, and all three have to move.</b>
 * The block cells are the visible part, but a build whose variant candidates
 * and container loot pools stayed behind is a broken build that looks fine
 * until it spawns. So each pass moves the cells, the variant sidecar (candidate
 * lists and lock-group ids) and the {@link ContainerContentsStore} pools and
 * prefab links together.</p>
 *
 * <p><b>Entities are deliberately left where they are.</b> The undo history
 * records block cells, sidecars and config files — it has no entity records, so
 * moving an item frame here would leave a Ctrl+Z that puts the blocks back and
 * strands the frame. {@link Result#entities} reports how many sat in the region
 * so the command can say so.</p>
 *
 * <p>Writes go through {@link SilentBlockOps#setBlockSilent}: no block events,
 * so a transform re-enters neither {@link EditorEditRecorder} nor
 * {@link EditorMirrorLiveHandler}, and block-entity contents round-trip.
 * {@link EditorRegionDiff} picks the writes up by diffing the plot around the
 * call, which is what makes the whole thing one Ctrl+Z.</p>
 */
public final class EditorPlotTransformer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private EditorPlotTransformer() {}

    /** How many of each kind of thing moved, for the command to phrase back. */
    public record Result(int cells, int variantEntries, int pools, int entities) {}

    /**
     * The box a transform rearranges, plus the two position-keyed sidecars that
     * have to travel with it.
     *
     * <p>{@code mirrored} is carried so the command can warn: a plot with a live
     * mirror axis is authored as one half reflected into the other, and a
     * transform along that axis leaves the two halves no longer agreeing.</p>
     */
    public record Region(String name, BlockPos origin, Vec3i size,
                         @Nullable Sidecar sidecar, @Nullable String containerKey,
                         boolean mirrored) {

        public int volume() { return size.getX() * size.getY() * size.getZ(); }
    }

    /**
     * The slice of a variant sidecar a transform needs, over the two storage
     * shapes the editor has: {@link BlockVariantPlot} for carriages, contents,
     * parts and track-side plots, and {@link TrackVariantBlocks} directly for
     * portal rooms, which {@link BlockVariantPlot#resolveAt} has no arm for.
     */
    public interface Sidecar {
        Set<BlockPos> positions();
        List<VariantState> statesAt(BlockPos localPos);
        int lockIdAt(BlockPos localPos);
        void remove(BlockPos localPos);
        void put(BlockPos localPos, List<VariantState> states);
        void setLockId(BlockPos localPos, int lockId);
        void save() throws IOException;
    }

    // ─── Resolution ────────────────────────────────────────────────────────

    /**
     * The plot {@code player} is standing in, or empty when they are outside
     * every editor plot.
     *
     * <p>{@link BlockVariantPlot#resolveAt} is tried first because it hands back
     * the build region and the sidecar in one object — and, for a contents plot,
     * the <b>interior</b> rather than the carriage shell stamped around it. That
     * distinction matters here in a way it does not for undo bounds: rotating
     * the shell walls along with the build would be plainly wrong.</p>
     *
     * <p>Portal rooms are the one category that cascade does not cover, so they
     * fall through to {@link EditorCategory#locate} and get their sidecar wired
     * up directly.</p>
     */
    public static Optional<Region> resolve(ServerPlayer player, ServerLevel level) {
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();

        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot != null) {
            return Optional.of(new Region(plot.key(), plot.origin(), plot.footprint(),
                new PlotSidecar(plot), plot.key(),
                plot.mirrorX() || plot.mirrorY() || plot.mirrorZ()));
        }

        Optional<EditorCategory.Located> located = EditorCategory.locate(player, dims);
        if (located.isEmpty()) return Optional.empty();
        Template model = located.get().model();
        BlockPos origin = model.editorPlotOrigin(level, dims);
        Vec3i size = model.plotSize(dims);
        if (origin == null || size == null
            || size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            return Optional.empty();
        }

        if (model instanceof Template.PortalRoom room) {
            String containerKey = ContainerContentsStore.trackPlotKey(TrackKind.PORTAL_ROOM, room.name());
            return Optional.of(new Region(model.displayName(), origin, size,
                new TrackSidecar(TrackKind.PORTAL_ROOM, room.name(), size), containerKey,
                /*mirrored*/ false));
        }
        // A category with no variant sidecar of its own — the blocks still move.
        return Optional.of(new Region(model.displayName(), origin, size, null, null, false));
    }

    // ─── Application ───────────────────────────────────────────────────────

    /**
     * Rearrange {@code region} by {@code transform}. Callers must have checked
     * {@link EditorPlotTransform#rejection} and {@link EditorPlotTransform#isIdentity}
     * first — this method assumes the transform applies.
     *
     * <p>Every pass reads its whole source into a buffer before writing a single
     * cell. Source and destination overlap on all three transforms, so a
     * streaming write would read cells it had already overwritten.</p>
     */
    public static Result apply(ServerLevel level, Region region, EditorPlotTransform transform)
            throws IOException {
        int cells = moveBlocks(level, region, transform);
        int variantEntries = moveVariants(region, transform);
        int pools = movePools(region, transform);
        return new Result(cells, variantEntries, pools, countEntities(level, region));
    }

    /** A cell's contents in flight: its state and its block entity's full NBT. */
    private record Content(BlockState state, @Nullable CompoundTag nbt) {}

    private static int moveBlocks(ServerLevel level, Region region, EditorPlotTransform transform) {
        Vec3i size = region.size();
        BlockPos origin = region.origin();
        Map<BlockPos, Content> destinations = new HashMap<>(region.volume());

        for (int x = 0; x < size.getX(); x++) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    BlockPos from = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(from);
                    destinations.put(origin.offset(transform.destination(x, y, z, size)),
                        new Content(transform.state(state), readNbt(level, from)));
                }
            }
        }

        int changed = 0;
        for (Map.Entry<BlockPos, Content> entry : destinations.entrySet()) {
            BlockPos pos = entry.getKey();
            Content content = entry.getValue();
            // A cell whose state is already right and holds no block entity has
            // nothing to write. Cells with a block entity are always rewritten:
            // an identical state can still be a different chest.
            if (level.getBlockState(pos) == content.state() && !content.state().hasBlockEntity()) {
                continue;
            }
            SilentBlockOps.setBlockSilent(level, pos, content.state(), content.nbt());
            changed++;
        }
        return changed;
    }

    private static int moveVariants(Region region, EditorPlotTransform transform) throws IOException {
        Sidecar sidecar = region.sidecar();
        if (sidecar == null) return 0;
        Vec3i size = region.size();

        record Moved(BlockPos to, List<VariantState> states, int lockId) {}
        List<Moved> moved = new ArrayList<>();
        List<BlockPos> sources = List.copyOf(sidecar.positions());
        for (BlockPos from : sources) {
            List<VariantState> states = sidecar.statesAt(from);
            if (states == null || states.isEmpty()) continue;
            // An entry outside the box has nowhere to go under the transform and
            // is not the author's build — leave it exactly where it is rather
            // than folding it into the plot at a wrapped index.
            if (outside(from, size)) continue;
            moved.add(new Moved(
                transform.destination(from.getX(), from.getY(), from.getZ(), size),
                transform.variants(states), sidecar.lockIdAt(from)));
        }
        if (moved.isEmpty()) return 0;

        // Clear before writing: source and destination sets overlap, so a
        // remove interleaved with the puts would delete an entry just written.
        for (BlockPos from : sources) {
            if (!outside(from, size)) sidecar.remove(from);
        }
        for (Moved m : moved) {
            sidecar.put(m.to(), m.states());
            // Lock ids are group membership, not geometry — they ride along
            // unchanged, so a locked pair stays a locked pair after the move.
            if (m.lockId() > 0) sidecar.setLockId(m.to(), m.lockId());
        }
        sidecar.save();
        return moved.size();
    }

    private static int movePools(Region region, EditorPlotTransform transform) throws IOException {
        String key = region.containerKey();
        if (key == null) return 0;
        ContainerContentsStore store = ContainerContentsStore.loadFor(key);
        Vec3i size = region.size();

        record Moved(BlockPos to, @Nullable ContainerContentsPool pool, @Nullable String link) {}
        List<Moved> moved = new ArrayList<>();
        List<BlockPos> sources = List.copyOf(store.allPositions());
        for (BlockPos from : sources) {
            if (outside(from, size)) continue;
            String link = store.linkAt(from);
            // poolAt reads through a link to the loot prefab; the local pool is
            // only meaningful when there is no link, and re-putting a
            // read-through pool would silently convert the link into a copy.
            ContainerContentsPool pool = link == null ? store.poolAt(from) : null;
            moved.add(new Moved(
                transform.destination(from.getX(), from.getY(), from.getZ(), size), pool, link));
        }
        if (moved.isEmpty()) return 0;

        for (BlockPos from : sources) {
            if (outside(from, size)) continue;
            store.removePool(from);
        }
        for (Moved m : moved) {
            if (m.link() != null) store.setLink(m.to(), m.link());
            else if (m.pool() != null) store.putPool(m.to(), m.pool());
        }
        store.save();
        ContainerContentsStore.invalidate(key);
        return moved.size();
    }

    /** Non-player entities standing in the region — reported, never moved. */
    private static int countEntities(ServerLevel level, Region region) {
        BlockPos origin = region.origin();
        Vec3i size = region.size();
        AABB box = new AABB(origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + size.getX(), origin.getY() + size.getY(), origin.getZ() + size.getZ());
        List<Entity> found = level.getEntities((Entity) null, box, e -> !(e instanceof Player));
        return found.size();
    }

    private static boolean outside(BlockPos local, Vec3i size) {
        return local.getX() < 0 || local.getX() >= size.getX()
            || local.getY() < 0 || local.getY() >= size.getY()
            || local.getZ() < 0 || local.getZ() >= size.getZ();
    }

    private static @Nullable CompoundTag readNbt(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be == null ? null : be.saveWithFullMetadata(level.registryAccess());
    }

    // ─── Sidecar adapters ──────────────────────────────────────────────────

    /** Carriages, contents interiors, parts and track-side plots. */
    private record PlotSidecar(BlockVariantPlot plot) implements Sidecar {
        @Override public Set<BlockPos> positions() { return plot.allFlaggedPositions(); }
        @Override public List<VariantState> statesAt(BlockPos l) { return plot.statesAt(l); }
        @Override public int lockIdAt(BlockPos l) { return plot.lockIdAt(l); }
        @Override public void remove(BlockPos l) { plot.remove(l); }
        @Override public void put(BlockPos l, List<VariantState> s) { plot.put(l, s); }
        @Override public void setLockId(BlockPos l, int id) { plot.setLockId(l, id); }
        @Override public void save() throws IOException { plot.save(); }
    }

    /**
     * Portal rooms. Their sidecar is a {@link TrackVariantBlocks} keyed by kind
     * and name, reached directly because the {@link BlockVariantPlot} cascade
     * has no portal-room arm.
     */
    private static final class TrackSidecar implements Sidecar {
        private final TrackKind kind;
        private final String name;
        private final TrackVariantBlocks blocks;

        TrackSidecar(TrackKind kind, String name, Vec3i size) {
            this.kind = kind;
            this.name = name;
            this.blocks = TrackVariantBlocks.loadFor(kind, name, size);
        }

        @Override public Set<BlockPos> positions() {
            return EditorMirror.markersOf(blocks.entries());
        }
        @Override public List<VariantState> statesAt(BlockPos l) { return blocks.statesAt(l); }
        @Override public int lockIdAt(BlockPos l) { return blocks.lockIdAt(l); }
        @Override public void remove(BlockPos l) { blocks.remove(l); }
        @Override public void put(BlockPos l, List<VariantState> s) { blocks.put(l, s); }
        @Override public void setLockId(BlockPos l, int id) { blocks.setLockId(l, id); }
        @Override public void save() throws IOException {
            blocks.save(kind, name);
            if (EditorDevMode.isEnabled()) {
                try {
                    blocks.saveToSource(kind, name);
                } catch (IOException e) {
                    LOGGER.warn("[DungeonTrain] EditorPlotTransformer: source write failed for {}/{}: {}",
                        kind.id(), name, e.toString());
                }
            }
        }
    }
}
