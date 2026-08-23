package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A whole-plot rearrangement, as pure maths: where each cell of the plot box
 * ends up, and how its contents turn on the way.
 *
 * <p>Three shapes share this one interface because they are the same operation
 * with a different permutation — {@link #offset offset} slides and wraps,
 * {@link #rotation rotate} turns about the vertical axis, {@link #flip flip}
 * reflects across one axis. All three are <b>bijections over the whole box</b>,
 * which is what lets {@code EditorPlotTransformer} write every destination cell
 * exactly once with no cell left vacated and nothing clipped at the edges.</p>
 *
 * <p>Deliberately free of world and sidecar access so the index maths can be
 * unit-tested without a server — see {@code EditorPlotTransformTest}. The
 * reflection halves reuse {@link EditorMirror}, which already solved mirroring
 * a block state (including the vertical case Minecraft has no built-in for)
 * for the editor's mirror axes.</p>
 */
public interface EditorPlotTransform {

    /** Names the action in chat and in the undo history ("Undid: Rotate 90°"). */
    String label();

    /** Where the cell at local {@code (x, y, z)} in a box of {@code size} ends up. */
    BlockPos destination(int x, int y, int z, Vec3i size);

    /** The block state as it arrives at its destination. */
    BlockState state(BlockState in);

    /** A variant candidate pool as it arrives at its destination. */
    List<VariantState> variants(List<VariantState> in);

    /**
     * Why this transform cannot run on a box of {@code size}, or null when it
     * can. Only a quarter turn has an answer here: it needs a square footprint.
     */
    @Nullable default String rejection(Vec3i size) {
        return null;
    }

    /** True when the transform would leave the plot exactly as it found it. */
    default boolean isIdentity() {
        return false;
    }

    /** Index wrap for a sliding offset — {@code floorMod}, so negatives wrap too. */
    static int wrap(int v, int size) {
        return Math.floorMod(v, size);
    }

    static EditorPlotTransform offset(int dx, int dy, int dz) {
        return new Offset(dx, dy, dz);
    }

    /** {@code degrees} must be 90, 180 or 270 — clockwise seen from above. */
    static EditorPlotTransform rotation(int degrees) {
        return new Rotate(Math.floorMod(degrees, 360));
    }

    static EditorPlotTransform flip(Direction.Axis axis) {
        return new Flip(axis);
    }

    // ─── Offset ────────────────────────────────────────────────────────────

    /**
     * Slide every cell by a whole-block delta, wrapping at the plot faces:
     * content leaving one face re-enters from the opposite one. Nothing is
     * clipped and no cell is left vacated, so the offset is lossless.
     * Orientation is untouched — a slide turns nothing.
     */
    record Offset(int dx, int dy, int dz) implements EditorPlotTransform {

        @Override public String label() {
            return "Offset " + dx + " " + dy + " " + dz;
        }

        @Override public BlockPos destination(int x, int y, int z, Vec3i size) {
            return new BlockPos(wrap(x + dx, size.getX()),
                                wrap(y + dy, size.getY()),
                                wrap(z + dz, size.getZ()));
        }

        @Override public BlockState state(BlockState in) { return in; }

        @Override public List<VariantState> variants(List<VariantState> in) { return in; }

        @Override public boolean isIdentity() { return dx == 0 && dy == 0 && dz == 0; }
    }

    // ─── Rotate ────────────────────────────────────────────────────────────

    /**
     * Turn the plot about its vertical axis, clockwise seen from above.
     *
     * <p>A quarter turn swaps the X and Z extents, so it only fits back inside
     * the same plot when the footprint is square — which carriages, being
     * longer than they are wide, never are. {@link #rejection} says so rather
     * than letting the build come out truncated. A half turn always fits.</p>
     */
    record Rotate(int degrees) implements EditorPlotTransform {

        @Override public String label() { return "Rotate " + degrees + "°"; }

        @Override public @Nullable String rejection(Vec3i size) {
            if (degrees == 0 || degrees == 180) return null;
            if (size.getX() == size.getZ()) return null;
            return "a quarter turn needs a square plot, but this one is "
                + size.getX() + " long by " + size.getZ() + " wide — only 180 fits";
        }

        @Override public BlockPos destination(int x, int y, int z, Vec3i size) {
            return switch (degrees) {
                case 90 -> new BlockPos(size.getZ() - 1 - z, y, x);
                case 180 -> new BlockPos(size.getX() - 1 - x, y, size.getZ() - 1 - z);
                case 270 -> new BlockPos(z, y, size.getX() - 1 - x);
                default -> new BlockPos(x, y, z);
            };
        }

        @Override public BlockState state(BlockState in) { return in.rotate(vanilla()); }

        @Override public List<VariantState> variants(List<VariantState> in) {
            List<VariantState> out = new ArrayList<>(in.size());
            for (VariantState v : in) out.add(rotateVariant(v));
            return out;
        }

        @Override public boolean isIdentity() { return degrees == 0; }

        /** The vanilla rotation this quarter/half turn corresponds to. */
        public Rotation vanilla() {
            return switch (degrees) {
                case 90 -> Rotation.CLOCKWISE_90;
                case 180 -> Rotation.CLOCKWISE_180;
                case 270 -> Rotation.COUNTERCLOCKWISE_90;
                default -> Rotation.NONE;
            };
        }

        /**
         * Turn one variant candidate. Mob entries are returned unchanged — their
         * rotation field is a spawn yaw rather than a block facing, the same
         * carve-out {@link EditorMirror#reflectVariant} makes.
         */
        private VariantState rotateVariant(VariantState v) {
            if (v.isMob()) return v;
            return new VariantState(
                v.state().rotate(vanilla()), v.blockEntityNbt(), v.weight(),
                rotateRotation(v.rotation()), v.linkedLootPrefabId(), v.entityId(),
                v.half(), v.difficulty(), v.groupRef());
        }

        /**
         * Turn a candidate's authored facing set. A vertical face is its own
         * image under a yaw rotation, which {@link Rotation#rotate(Direction)}
         * already handles; {@code RANDOM} carries no facing at all.
         */
        private VariantRotation rotateRotation(VariantRotation r) {
            if (r.dirMask() == 0) return r;
            int mask = 0;
            for (Direction d : r.directions()) {
                mask |= VariantRotation.maskOf(vanilla().rotate(d));
            }
            return new VariantRotation(r.mode(), mask);
        }
    }

    // ─── Flip ──────────────────────────────────────────────────────────────

    /**
     * Reflect the plot across one axis. The state maths is
     * {@link EditorMirror#reflect} / {@link EditorMirror#reflectStates} — the
     * same pair the editor's mirror axes use, vertical flip included.
     */
    record Flip(Direction.Axis axis) implements EditorPlotTransform {

        @Override public String label() {
            return "Flip " + axis.getName().toUpperCase(Locale.ROOT);
        }

        @Override public BlockPos destination(int x, int y, int z, Vec3i size) {
            return switch (axis) {
                case X -> new BlockPos(size.getX() - 1 - x, y, z);
                case Y -> new BlockPos(x, size.getY() - 1 - y, z);
                case Z -> new BlockPos(x, y, size.getZ() - 1 - z);
            };
        }

        @Override public BlockState state(BlockState in) {
            return EditorMirror.reflect(in, axis == Direction.Axis.X,
                axis == Direction.Axis.Y, axis == Direction.Axis.Z);
        }

        @Override public List<VariantState> variants(List<VariantState> in) {
            return EditorMirror.reflectStates(in, axis == Direction.Axis.X,
                axis == Direction.Axis.Y, axis == Direction.Axis.Z);
        }
    }
}
