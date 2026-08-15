package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The blocks the Train Builder shows as ghosts instead of standing up for real.
 *
 * <p>Two things the builder wants you to <em>see</em> and does not want you to <em>touch</em>:</p>
 *
 * <ul>
 *   <li><b>The flatbed pads</b> that cap a carriage group. A one-carriage world never stamps them
 *       ({@link BuilderWorldLayout#usesPads} is false for one), but a group's silhouette is the
 *       pads' whole reason for existing, so they are drawn back in.</li>
 *   <li><b>The shell above the floor</b>, when you are authoring a carriage room from inside. The
 *       room is what you are building; the carriage around it is the thing you are building it
 *       <em>into</em>. Leaving it solid means the only thing visible from where you work is the
 *       inside of a box — the rest of the train, which is the point of standing in the middle of
 *       one, is behind an opaque wall.</li>
 * </ul>
 *
 * <p><b>Stamped, captured, erased.</b> Both are lifted out of the world rather than modelled from
 * a palette or re-derived from a template. The pads are stamped by
 * {@link CarriagePlacer#placeHalfFlatbedPad} exactly as a real group's are and then taken away
 * again, so the ghost is the pad — including the FRONT one's mirroring — rather than somebody's
 * idea of one. The shell is read back off the carriage that was just stamped, so it carries
 * whatever the parts overlay put on it: this build's doors and this build's windows, not the base
 * variant's.</p>
 *
 * <p><b>The floor course stays.</b> Only cells above {@code y == 0} are lifted. It is what you
 * stand on and what the contents rest on, and a room whose floor is a ghost is a room you cannot
 * build in.</p>
 *
 * <p>The erase runs through {@link SilentBlockOps#setBlockSilentNoCascade}, which is the load-bearing
 * detail: a plain {@code UPDATE_ALL} removal fires the neighbour cascade, and every torch, button
 * and painting the author hung on the inside face of that wall would notice its support vanish and
 * drop as an item. The same hazard the portal-room variant pass hit.</p>
 */
public final class BuilderGhostCells {

    private static final String TAG_SHELL = "shell";
    private static final String TAG_BACK_PAD = "backPad";
    private static final String TAG_FRONT_PAD = "frontPad";
    private static final String TAG_POS = "p";
    private static final String TAG_STATE = "s";

    private BuilderGhostCells() {}

    /**
     * One build's ghost geometry, each map local to its own origin so the client can repeat it at
     * every slot without being told the same shape twice.
     *
     * @param shell    the carriage skin above the floor, local to the carriage box's min corner
     * @param backPad  the pad at the low-X end, local to its own min corner
     * @param frontPad the pad at the high-X end — a separate map rather than a mirror flag, because
     *                 {@code Mirror.FRONT_BACK} is applied at stamp time and the honest way to
     *                 capture its result is to read it back
     */
    public record Cells(Map<BlockPos, BlockState> shell,
                        Map<BlockPos, BlockState> backPad,
                        Map<BlockPos, BlockState> frontPad) {

        public static final Cells EMPTY = new Cells(Map.of(), Map.of(), Map.of());

        public boolean isEmpty() {
            return shell.isEmpty() && backPad.isEmpty() && frontPad.isEmpty();
        }
    }

    /**
     * Lift this build's ghost geometry out of the world.
     *
     * <p>Call after everything has been stamped — the shell capture has to see the parts overlay,
     * and the dirty-check baseline has to be captured after the erase or a freshly opened build
     * reads as edited.</p>
     *
     * @param carriages how many carriages are actually parked; the ghosts only mean anything for a
     *                  one-carriage world, since a full group is missing nothing
     */
    public static Cells lift(ServerLevel level, CarriageDims dims, BuilderMode mode,
                             BuilderNewOptions.SubType subType, int carriages) {
        if (carriages != 1 || mode == null) {
            return Cells.EMPTY;
        }
        BoundingBox parked = BuilderBounds.buildVolumes(carriages, dims).get(0);
        return new Cells(liftShell(level, dims, mode, subType, parked),
                liftPad(level, dims, mode, parked, CarriagePlacer.HalfPadSide.BACK),
                liftPad(level, dims, mode, parked, CarriagePlacer.HalfPadSide.FRONT));
    }

    /**
     * The carriage skin above the floor, taken away and handed back.
     *
     * <p>Only for a carriage room authored from inside. <b>Parts keeps its shell</b> — a part
     * <em>is</em> a piece of the shell and {@code BuilderSave.savePart} captures it out of the
     * world, so ghosting it would save a part made of air. And from outside there is nothing to
     * gain: you are already looking at the carriage from where you can see it.</p>
     */
    private static Map<BlockPos, BlockState> liftShell(ServerLevel level, CarriageDims dims,
                                                       BuilderMode mode,
                                                       BuilderNewOptions.SubType subType,
                                                       BoundingBox parked) {
        if (mode != BuilderMode.INSIDE_CARRIAGE
                || subType != BuilderNewOptions.SubType.CARRIAGE_ROOM) {
            return Map.of();
        }
        Map<BlockPos, BlockState> lifted = new LinkedHashMap<>();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int x = 0; x < dims.length(); x++) {
            for (int y = 1; y < dims.height(); y++) {   // y == 0 is the floor: kept, see class note
                for (int z = 0; z < dims.width(); z++) {
                    if (!onSkin(x, y, z, dims)) {
                        continue;   // the interior, which is the build itself
                    }
                    probe.set(parked.minX() + x, parked.minY() + y, parked.minZ() + z);
                    BlockState state = level.getBlockState(probe);
                    if (state.isAir()) {
                        continue;
                    }
                    lifted.put(new BlockPos(x, y, z), state);
                    SilentBlockOps.setBlockSilentNoCascade(level, probe.immutable(),
                            Blocks.AIR.defaultBlockState(), null);
                }
            }
        }
        return lifted;
    }

    /**
     * Whether a cell is shell rather than contents.
     *
     * <p>The complement of {@code CarriageContentsPlacer}'s interior box — one in from each
     * perimeter wall — so what this lifts and what a carriage room saves can never overlap.</p>
     */
    private static boolean onSkin(int x, int y, int z, CarriageDims dims) {
        return x == 0 || x == dims.length() - 1
                || y == dims.height() - 1
                || z == 0 || z == dims.width() - 1;
    }

    /**
     * One flatbed pad: stamped where a real group's would go, read back, then taken away.
     *
     * <p>Nothing is left behind — the pads sit outside the build volume, so a leftover would be
     * scaffolding the author can walk on, break, and not understand.</p>
     */
    private static Map<BlockPos, BlockState> liftPad(ServerLevel level, CarriageDims dims,
                                                     BuilderMode mode, BoundingBox parked,
                                                     CarriagePlacer.HalfPadSide side) {
        int full = BuilderWorldLayout.ghostGroupCarriages(mode);
        if (!BuilderWorldLayout.usesPads(full)) {
            return Map.of();
        }
        int padLength = CarriagePlacer.halfPadLen(dims);
        BuilderGhostSlots.Ghosts slots = BuilderGhostSlots.of(parked.minX(), 1, full,
                dims.length(), padLength);
        if (slots.padMinX().size() != 2 || padLength <= 0) {
            return Map.of();
        }
        // BuilderGhostSlots lists the pads low-X first, which is the BACK one — the same order
        // stampTrain places them in.
        int padX = slots.padMinX().get(side == CarriagePlacer.HalfPadSide.BACK ? 0 : 1);
        BlockPos origin = new BlockPos(padX, BuilderWorldLayout.TRAIN_Y, 0);

        CarriagePlacer.placeHalfFlatbedPad(level, origin, side, dims);

        Map<BlockPos, BlockState> lifted = new LinkedHashMap<>();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int x = 0; x < padLength; x++) {
            for (int y = 0; y < dims.height(); y++) {
                for (int z = 0; z < dims.width(); z++) {
                    probe.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = level.getBlockState(probe);
                    if (state.isAir()) {
                        continue;
                    }
                    lifted.put(new BlockPos(x, y, z), state);
                    SilentBlockOps.setBlockSilentNoCascade(level, probe.immutable(),
                            Blocks.AIR.defaultBlockState(), null);
                }
            }
        }
        return lifted;
    }

    // ---- Persistence ----

    /**
     * Write the cells for {@code DungeonTrainWorldData}.
     *
     * <p>Persisted rather than re-derived, because the derivation is destructive: the shell has
     * been erased by the time anyone would want to ask for it again, and re-stamping the variant to
     * read it back would write over whatever the author has done to the floor since.</p>
     *
     * <p>Block states go through {@link NbtUtils#writeBlockState}, so the blob survives a palette
     * renumbering the way a raw id would not.</p>
     */
    public static CompoundTag toTag(Cells cells) {
        CompoundTag tag = new CompoundTag();
        tag.put(TAG_SHELL, writeCells(cells.shell()));
        tag.put(TAG_BACK_PAD, writeCells(cells.backPad()));
        tag.put(TAG_FRONT_PAD, writeCells(cells.frontPad()));
        return tag;
    }

    /** Read back what {@link #toTag} wrote. An absent or malformed blob reads as no ghosts. */
    public static Cells fromTag(CompoundTag tag, HolderGetter<Block> blocks) {
        if (tag == null || tag.isEmpty()) {
            return Cells.EMPTY;
        }
        return new Cells(readCells(tag.getList(TAG_SHELL, Tag.TAG_COMPOUND), blocks),
                readCells(tag.getList(TAG_BACK_PAD, Tag.TAG_COMPOUND), blocks),
                readCells(tag.getList(TAG_FRONT_PAD, Tag.TAG_COMPOUND), blocks));
    }

    private static ListTag writeCells(Map<BlockPos, BlockState> cells) {
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, BlockState> entry : cells.entrySet()) {
            CompoundTag cell = new CompoundTag();
            BlockPos pos = entry.getKey();
            cell.putIntArray(TAG_POS, new int[] {pos.getX(), pos.getY(), pos.getZ()});
            cell.put(TAG_STATE, NbtUtils.writeBlockState(entry.getValue()));
            list.add(cell);
        }
        return list;
    }

    private static Map<BlockPos, BlockState> readCells(ListTag list, HolderGetter<Block> blocks) {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag cell = list.getCompound(i);
            int[] pos = cell.getIntArray(TAG_POS);
            if (pos.length != 3) {
                continue;   // a hand-edited or truncated blob: skip the cell, keep the rest
            }
            BlockState state = NbtUtils.readBlockState(blocks, cell.getCompound(TAG_STATE));
            if (state.isAir()) {
                continue;   // an unknown block reads back as air, and an air ghost draws nothing
            }
            cells.put(new BlockPos(pos[0], pos[1], pos[2]), state);
        }
        return cells;
    }
}
