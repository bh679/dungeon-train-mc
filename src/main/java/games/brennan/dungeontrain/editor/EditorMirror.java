package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared mirror maths + block-state reflection for the in-game template
 * editors. Generalises the original tunnel-only {@code mirrorAuthoredHalf}
 * (PR #580) into a reusable, three-axis tool used by:
 *
 * <ul>
 *   <li>{@link EditorMirrorLiveHandler} — live, per-block mirroring as the
 *       author places / breaks blocks ({@link #mirrorEditLive}); and</li>
 *   <li>each editor's {@code save()} — a full-region rebuild backstop so the
 *       captured template is symmetric regardless of editing history
 *       ({@link #rebuildFromMaster}).</li>
 * </ul>
 *
 * <p>The model is "low octant is master": the cell whose per-axis
 * {@link #source} maps to itself on every enabled axis is authored, and every
 * other cell is its reflection. The horizontal axes use vanilla
 * {@link BlockState#mirror} ({@link Mirror#FRONT_BACK} for X, {@link
 * Mirror#LEFT_RIGHT} for Z); the vertical axis uses {@link #verticalFlip}, a
 * best-effort flip (Minecraft has no built-in vertical block mirror).</p>
 *
 * <p>All world writes go through {@link SilentBlockOps} (no particles, sounds,
 * drops, or events — so the live handler never recurses into its own place /
 * break subscribers).</p>
 */
public final class EditorMirror {

    private EditorMirror() {}

    // ─── Per-axis maths (pure — unit-tested in EditorMirrorTest) ───────────

    /** Last local column in the master (low) half on an axis of length {@code size}. 10 → 4, 13 → 6. */
    public static int lastMaster(int size) {
        return (size - 1) / 2;
    }

    /** Reflect a local column across the axis centre. Its own inverse. */
    public static int target(int c, int size) {
        return (size - 1) - c;
    }

    /**
     * Master source column for target column {@code c} on one axis: a far-half
     * column reflects back into the master half when {@code enabled}; a master
     * column (or the axis disabled) maps to itself. The centre column of an
     * odd-length axis maps to itself either way.
     */
    public static int source(int c, int size, boolean enabled) {
        return (enabled && c > lastMaster(size)) ? target(c, size) : c;
    }

    /** One reflected target cell of an edit, with the axes that moved (for state reflection). */
    public record Image(BlockPos local, boolean flipX, boolean flipY, boolean flipZ) {}

    /**
     * Every distinct reflected cell of {@code local} across the enabled axes,
     * excluding {@code local} itself. On-plane collisions (centre column of an
     * odd axis) are de-duplicated, so a 1/2/3-axis edit yields at most 1/3/7
     * images.
     */
    public static List<Image> imagesOf(BlockPos local, Vec3i f, boolean mx, boolean my, boolean mz) {
        int x = local.getX(), y = local.getY(), z = local.getZ();
        int tx = target(x, f.getX()), ty = target(y, f.getY()), tz = target(z, f.getZ());
        List<Image> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (int xi = 0; xi <= (mx ? 1 : 0); xi++) {
            boolean fx = xi == 1;
            int cx = fx ? tx : x;
            for (int yi = 0; yi <= (my ? 1 : 0); yi++) {
                boolean fy = yi == 1;
                int cy = fy ? ty : y;
                for (int zi = 0; zi <= (mz ? 1 : 0); zi++) {
                    boolean fz = zi == 1;
                    int cz = fz ? tz : z;
                    if (cx == x && cy == y && cz == z) continue; // the edited cell itself
                    BlockPos p = new BlockPos(cx, cy, cz);
                    if (seen.add(p)) out.add(new Image(p, fx, fy, fz));
                }
            }
        }
        return out;
    }

    // ─── Block-state reflection ────────────────────────────────────────────

    /**
     * Reflect a block state across the moved axes — horizontal via vanilla
     * {@link BlockState#mirror}, vertical via {@link #verticalFlip}.
     */
    public static BlockState reflect(BlockState s, boolean flipX, boolean flipY, boolean flipZ) {
        if (flipX) s = s.mirror(Mirror.FRONT_BACK); // X: EAST ↔ WEST
        if (flipZ) s = s.mirror(Mirror.LEFT_RIGHT);  // Z: NORTH ↔ SOUTH
        if (flipY) s = verticalFlip(s);
        return s;
    }

    /**
     * Best-effort vertical flip of a single block's state. Minecraft has no
     * built-in vertical mirror, so this toggles the common orientation
     * properties:
     * <ul>
     *   <li>{@code SLAB_TYPE} bottom ↔ top (double unchanged);</li>
     *   <li>{@code HALF} top ↔ bottom (stairs, trapdoors);</li>
     *   <li>{@code ATTACH_FACE} floor ↔ ceiling (buttons, levers, grindstones; wall unchanged);</li>
     *   <li>{@code FACING} up ↔ down (pistons, observers, droppers, …).</li>
     * </ul>
     * Two-block structures (doors, tall plants — {@code DOUBLE_BLOCK_HALF}) and
     * blocks with no vertical property are returned unchanged; their halves are
     * handled positionally. Exotic / modded vertical orientations may not flip
     * perfectly — this is documented editor-tooling behaviour, not generation.
     */
    public static BlockState verticalFlip(BlockState s) {
        if (s.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType t = s.getValue(BlockStateProperties.SLAB_TYPE);
            if (t == SlabType.BOTTOM) {
                s = s.setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
            } else if (t == SlabType.TOP) {
                s = s.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
            }
        }
        if (s.hasProperty(BlockStateProperties.HALF)) {
            Half h = s.getValue(BlockStateProperties.HALF);
            s = s.setValue(BlockStateProperties.HALF, h == Half.TOP ? Half.BOTTOM : Half.TOP);
        }
        if (s.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            AttachFace af = s.getValue(BlockStateProperties.ATTACH_FACE);
            if (af == AttachFace.FLOOR) {
                s = s.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.CEILING);
            } else if (af == AttachFace.CEILING) {
                s = s.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR);
            }
        }
        if (s.hasProperty(BlockStateProperties.FACING)) {
            Direction d = s.getValue(BlockStateProperties.FACING);
            if (d == Direction.UP) {
                s = s.setValue(BlockStateProperties.FACING, Direction.DOWN);
            } else if (d == Direction.DOWN) {
                s = s.setValue(BlockStateProperties.FACING, Direction.UP);
            }
        }
        return s;
    }

    /**
     * Local marker cells for a rebuild / live pass = the sidecar's flagged
     * positions (variant entries such as the tunnel section chest). All four
     * editor sidecars return {@link CarriageVariantBlocks.Entry} lists.
     */
    public static Set<BlockPos> markersOf(List<CarriageVariantBlocks.Entry> entries) {
        Set<BlockPos> out = new HashSet<>(entries.size());
        for (CarriageVariantBlocks.Entry e : entries) out.add(e.localPos().immutable());
        return out;
    }

    // ─── World application ─────────────────────────────────────────────────

    /**
     * Propagate one editor edit at {@code localEdited} to its mirror-image
     * cells. {@code state} is the placed block (image cells receive the
     * axis-reflected state); pass {@code null} for a break (image cells cleared
     * to air). Cells in {@code markers} are never written (preserves the single
     * sidecar marker, e.g. the tunnel section chest). The edited cell itself is
     * left untouched — vanilla already placed / broke it.
     */
    public static void mirrorEditLive(ServerLevel level, BlockPos origin, Vec3i f,
                                      BlockPos localEdited, @Nullable BlockState state,
                                      boolean mx, boolean my, boolean mz, Set<BlockPos> markers) {
        if (!mx && !my && !mz) return;
        for (Image img : imagesOf(localEdited, f, mx, my, mz)) {
            if (markers.contains(img.local())) continue;
            BlockPos tgt = origin.offset(img.local().getX(), img.local().getY(), img.local().getZ());
            if (state == null) {
                SilentBlockOps.clearBlockSilent(level, tgt);
            } else {
                SilentBlockOps.setBlockSilent(level, tgt, reflect(state, img.flipX(), img.flipY(), img.flipZ()));
            }
        }
    }

    /**
     * Rebuild the non-master region of a plot from the authored low-octant
     * master, reflecting across the enabled axes. Runs in-world immediately
     * before a {@code save()} captures the region, so the stored template — and
     * therefore every generated structure — is unchanged; only the authoring
     * workflow differs. No-op when all axes are disabled.
     *
     * <p>Marker cells (sidecar entries such as the tunnel section chest) are the
     * intentional asymmetry: a marker on the source side becomes air at the
     * target (no duplicate), and a marker already sitting on a target cell is
     * preserved. The single marker block is re-placed once at generation.</p>
     */
    public static void rebuildFromMaster(ServerLevel level, BlockPos origin, Vec3i f,
                                         boolean mx, boolean my, boolean mz, Set<BlockPos> markers) {
        if (!mx && !my && !mz) return;
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = 0; dx < f.getX(); dx++) {
            int sx = source(dx, f.getX(), mx);
            for (int dy = 0; dy < f.getY(); dy++) {
                int sy = source(dy, f.getY(), my);
                for (int dz = 0; dz < f.getZ(); dz++) {
                    int sz = source(dz, f.getZ(), mz);
                    if (sx == dx && sy == dy && sz == dz) continue; // master cell — author's work
                    if (markers.contains(new BlockPos(dx, dy, dz))) continue; // preserve a target marker
                    BlockPos tgt = origin.offset(dx, dy, dz);
                    if (markers.contains(new BlockPos(sx, sy, sz))) {
                        SilentBlockOps.setBlockSilent(level, tgt, air); // don't duplicate the marker
                        continue;
                    }
                    BlockState st = level.getBlockState(origin.offset(sx, sy, sz));
                    st = reflect(st, sx != dx, sy != dy, sz != dz);
                    SilentBlockOps.setBlockSilent(level, tgt, st);
                }
            }
        }
    }
}
