package games.brennan.dungeontrain.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageVariant;
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
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The blocks the Train Builder shows as ghosts instead of standing up for real.
 *
 * <p>Two things the builder wants you to <em>see</em> and does not want you to <em>touch</em>:</p>
 *
 * <ul>
 *   <li><b>The flatbed pads</b> that cap a carriage group. A one-carriage world never stamps them
 *       ({@link BuilderWorldLayout#usesPads} is false for one) and a full group stamps them solid —
 *       where, sitting outside the build volume by design, they were washed red as blocks that will
 *       never be saved. Neither reads as what a pad is: it frames the silhouette, and it is not
 *       yours to build on. That is what a ghost says.</li>
 *   <li><b>The shell</b>, when you are authoring a carriage room from inside. The room is what you
 *       are building; the carriage around it — walls, roof and floor — is the thing you are building
 *       it <em>into</em>. Leaving it solid means the only thing visible from where you work is the
 *       inside of a box, with the rest of the train, which is the point of standing in the middle of
 *       one, behind an opaque wall.</li>
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
 * <p><b>The floor goes too.</b> The lifted set is the exact complement of the box a carriage room is
 * saved from ({@code CarriageContentsPlacer.interiorOrigin}, one in from every face) — see
 * {@link #onSkin} — so the deck under your feet is shell like the walls are, and ghosting the walls
 * while leaving it solid would have drawn a line through the carriage that means nothing to anyone
 * building in it. A builder world is creative and flying, so there is nothing to fall into.</p>
 *
 * <p>The erase runs through {@link SilentBlockOps#setBlockSilentNoCascade}, which is the load-bearing
 * detail: a plain {@code UPDATE_ALL} removal fires the neighbour cascade, and every torch, button
 * and painting the author hung on the inside face of that wall would notice its support vanish and
 * drop as an item. The same hazard the portal-room variant pass hit.</p>
 */
public final class BuilderGhostCells {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TAG_SHELL = "shell";
    private static final String TAG_BACK_PAD = "backPad";
    private static final String TAG_FRONT_PAD = "frontPad";
    private static final String TAG_TRACK = "track";
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
                        Map<BlockPos, BlockState> frontPad,
                        Map<BlockPos, BlockState> track) {

        public static final Cells EMPTY = new Cells(Map.of(), Map.of(), Map.of(), Map.of());

        public boolean isEmpty() {
            return shell.isEmpty() && backPad.isEmpty() && frontPad.isEmpty() && track.isEmpty();
        }
    }

    /**
     * Where the lifted track is measured from — the low corner of the corridor it runs down.
     *
     * <p>Its own origin rather than the carriage's, because the track is the one lifted shape that
     * isn't part of a carriage: it runs the length of the platform and stays put while the train
     * around it changes length.</p>
     */
    public static BlockPos trackOrigin(CarriageDims dims) {
        TrackGeometry g = TrackGeometry.from(dims, BuilderWorldLayout.TRAIN_Y);
        return new BlockPos(BuilderWorldLayout.MIN_XZ, g.bedY(), g.trackZMin());
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
                             BuilderNewOptions.SubType subType, int carriages,
                             BuilderGhostMode ghostMode, Cells previous) {
        if (mode == null || carriages <= 0 || ghostMode == null) {
            return Cells.EMPTY;
        }
        if (!ghostMode.lifts()) {
            // SOLID stands the whole group up for real instead — see solidify. Nothing is recorded,
            // so a world in SOLID has no ghosts to send.
            solidify(level, dims, mode, carriages, previous);
            return Cells.EMPTY;
        }
        // Leaving SOLID: the carriages it stood in the empty slots are real blocks, and the ghost
        // that replaces them is drawn from the parked one. Left standing they would be drawn over.
        clearSolidSlots(level, dims, mode, carriages);
        // The pads and the track go whatever is parked; the shell only makes sense around the
        // single carriage a room is authored in. See each lift for why they answer differently.
        return new Cells(liftShell(level, dims, mode, subType, carriages),
                liftPad(level, dims, mode, CarriagePlacer.HalfPadSide.BACK),
                liftPad(level, dims, mode, CarriagePlacer.HalfPadSide.FRONT),
                liftTrack(level, dims, mode));
    }

    /**
     * Put back what {@link #lift} took — the way into {@link BuilderGhostMode#SOLID}.
     *
     * <p>Written from the recorded cells rather than re-stamped from the templates, so what comes
     * back is what was actually taken: this build's parts overlay on the shell, this world's track
     * variants down the corridor. Re-stamping would quietly replace all of it with whatever the
     * registry picks today.</p>
     *
     * <p>Skips any cell an author has since filled. Nothing can normally get into these spaces —
     * the track rows are protected and the shell ring is outside what a room saves — but "restore"
     * must never mean "overwrite something that is there".</p>
     */
    public static void restore(ServerLevel level, CarriageDims dims, int carriages, Cells cells) {
        if (cells == null || cells.isEmpty()) {
            // Worth a line rather than a silent return: "Solid gave me no walls back" and "there
            // was nothing recorded to give back" look identical in game and have entirely
            // different causes.
            LOGGER.info("[DungeonTrain] Builder scenery restore: nothing recorded to put back "
                    + "({} carriage(s))", carriages);
            return;
        }
        if (carriages == 1 && !cells.shell().isEmpty()) {
            BoundingBox parked = BuilderBounds.buildVolumes(carriages, dims).get(0);
            restoreAt(level, cells.shell(),
                    new BlockPos(parked.minX(), parked.minY(), parked.minZ()));
        }
        LOGGER.info("[DungeonTrain] Builder scenery restore: shell {}, pads {}+{}, track {}",
                cells.shell().size(), cells.backPad().size(), cells.frontPad().size(),
                cells.track().size());
        List<Integer> padXs = padPositions(dims);
        if (padXs.size() == 2) {
            restoreAt(level, cells.backPad(),
                    new BlockPos(padXs.get(0), BuilderWorldLayout.TRAIN_Y, 0));
            restoreAt(level, cells.frontPad(),
                    new BlockPos(padXs.get(1), BuilderWorldLayout.TRAIN_Y, 0));
        }
        restoreTrack(level, dims, cells);
    }

    /**
     * Stand the rest of the group up for real — what {@link BuilderGhostMode#SOLID} means.
     *
     * <p>Solid is not "draw the ghosts opaque". A carriage room is authored from <em>inside</em> a
     * carriage, and what you want when you ask for solid is walls you can stand on and a train
     * around you that is really there — the outside of the carriage as much as its inside. So the
     * empty slots either side get a real carriage and the group gets real pads, which is what the
     * builder always stood up for Train Outside and never did for the modes that park one.</p>
     *
     * <p>Stamped from this world's own source variant, the same one {@code stampTrain} uses, and
     * without contents: an empty neighbouring carriage is what a run of them looks like, and copying
     * the room you are authoring into the slots either side would put three of your build on the
     * track and make it very unclear which one you are editing.</p>
     *
     * <p>They land outside the build volume, so {@code OutOfBoundsWashRenderer} darkens them — which
     * is exactly the right thing for it to say, and why that wash stopped being red.</p>
     */
    private static void solidify(ServerLevel level, CarriageDims dims, BuilderMode mode,
                                 int carriages, Cells previous) {
        // The carriage you are standing in comes first, and it comes back from what was taken —
        // this build's parts overlay, not the base variant's.
        //
        // Done here rather than only on the GHOST -> SOLID transition, which is where it used to
        // live. That arm fires exactly once and only when the mode change is observed, so any path
        // that reached SOLID another way — a rejoin, a re-stamp, a mode already stored as SOLID with
        // the blocks still lifted — left the walls and the deck missing with no way to get them
        // back. Restoring on every pass into SOLID is idempotent (restoreAt only writes into air)
        // and cannot get stuck.
        restore(level, dims, carriages, previous);

        List<Integer> padXs = BuilderWorldLayout.ghostGroupCarriages(mode) <= 0
                ? List.of()
                : padPositions(dims);
        if (padXs.size() == 2) {
            CarriagePlacer.placeHalfFlatbedPad(level,
                    new BlockPos(padXs.get(0), BuilderWorldLayout.TRAIN_Y, 0),
                    CarriagePlacer.HalfPadSide.BACK, dims);
            CarriagePlacer.placeHalfFlatbedPad(level,
                    new BlockPos(padXs.get(1), BuilderWorldLayout.TRAIN_Y, 0),
                    CarriagePlacer.HalfPadSide.FRONT, dims);
        }
        Optional<CarriageVariant> variant = BuilderWorldSetup.currentSource(level);
        if (variant.isEmpty()) {
            return;   // no variant to stand up; the pads alone still frame the group
        }
        for (int slotX : emptySlots(dims, mode, carriages)) {
            CarriagePlacer.placeAt(level,
                    new BlockPos(slotX, BuilderWorldLayout.TRAIN_Y, 0), variant.get(), dims);
        }
    }

    /**
     * Take away what {@link #solidify} stood up in the empty slots.
     *
     * <p>Only the slots. The pads are erased by {@link #liftPad}, which re-stamps and re-reads them
     * anyway, and the carriage actually parked on the track is the build — touching that would erase
     * somebody's work.</p>
     */
    private static void clearSolidSlots(ServerLevel level, CarriageDims dims, BuilderMode mode,
                                        int carriages) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int slotX : emptySlots(dims, mode, carriages)) {
            for (int x = 0; x < dims.length(); x++) {
                for (int y = 0; y < dims.height(); y++) {
                    for (int z = 0; z < dims.width(); z++) {
                        pos.set(slotX + x, BuilderWorldLayout.TRAIN_Y + y, z);
                        if (!level.getBlockState(pos).isAir()) {
                            SilentBlockOps.setBlockSilentNoCascade(level, pos.immutable(),
                                    Blocks.AIR.defaultBlockState(), null);
                        }
                    }
                }
            }
        }
    }

    /** The carriage slots of the drawn group that have no real build in them. */
    private static List<Integer> emptySlots(CarriageDims dims, BuilderMode mode, int carriages) {
        if (carriages != 1) {
            return List.of();   // a full group has no empty slot; nothing to stand up or take away
        }
        BoundingBox parked = BuilderBounds.buildVolumes(carriages, dims).get(0);
        return BuilderGhostSlots.of(parked.minX(), 1, BuilderWorldLayout.ghostGroupCarriages(mode),
                dims.length(), CarriagePlacer.halfPadLen(dims)).carriageMinX();
    }

    /**
     * Put back just the line, before a fresh lift reads it.
     *
     * <p>The shell and the pads heal themselves on a re-stamp — {@code stampTrain} lays a new shell
     * and {@link #liftPad} stamps its own pad before capturing it — but nothing re-stamps the track
     * on a carriage-to-carriage mode switch, because {@code BuilderWorldSetup.restamp} only rebuilds
     * the scenery when crossing the has-scenery boundary. So the second lift would find the air the
     * first one left and record an empty line, and the track ghost would vanish on the first mode
     * switch.</p>
     *
     * <p>Safe to do unconditionally, and safe in a way the shell is not: {@link #trackOrigin} is
     * fixed by the platform rather than by the parked group, so it means the same place whatever the
     * carriage count has changed to.</p>
     */
    public static void restoreTrack(ServerLevel level, CarriageDims dims, Cells cells) {
        if (cells != null && !cells.track().isEmpty()) {
            restoreAt(level, cells.track(), trackOrigin(dims));
        }
    }

    private static void restoreAt(ServerLevel level, Map<BlockPos, BlockState> cells, BlockPos origin) {
        for (Map.Entry<BlockPos, BlockState> entry : cells.entrySet()) {
            BlockPos pos = origin.offset(entry.getKey());
            if (!level.getBlockState(pos).isAir()) {
                continue;
            }
            SilentBlockOps.setBlockSilentNoCascade(level, pos, entry.getValue(), null);
        }
    }

    /** Where this world's pads sit, for both the lift and the restore. */
    private static List<Integer> padPositions(CarriageDims dims) {
        return BuilderGhostSlots.padMinXFor(BuilderWorldLayout.OUTSIDE_CARRIAGES, dims.length(),
                CarriagePlacer.halfPadLen(dims));
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
                                                       int carriages) {
        if (mode != BuilderMode.INSIDE_CARRIAGE
                || subType != BuilderNewOptions.SubType.CARRIAGE_ROOM
                || carriages != 1) {
            return Map.of();
        }
        BoundingBox parked = BuilderBounds.buildVolumes(carriages, dims).get(0);
        Map<BlockPos, BlockState> lifted = new LinkedHashMap<>();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int x = 0; x < dims.length(); x++) {
            for (int y = 0; y < dims.height(); y++) {
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
     *
     * <p>Package-visible for {@code BuilderGhostCellsTest}, which asserts that disjointness against
     * the capture box itself. It is the claim the whole feature stands on: if the two ever overlap,
     * erasing a wall starts erasing somebody's saved room, and no amount of testing the renderer
     * would catch it.</p>
     */
    static boolean onSkin(int x, int y, int z, CarriageDims dims) {
        return x == 0 || x == dims.length() - 1
                || y == 0 || y == dims.height() - 1
                || z == 0 || z == dims.width() - 1;
    }

    /**
     * One flatbed pad: stamped where a real group's would go, read back, then taken away.
     *
     * <p><b>Whatever is parked.</b> A one-carriage world never stamps pads, and a full group stamps
     * them solid — and solid was the wrong answer in the other direction: the pads sit outside the
     * build volume by design ({@code BuilderBounds}), so {@code OutOfBoundsWashRenderer} painted
     * them red, leaving the ends of the train as blocks you cannot save and must not touch. Ghosting
     * both cases says the one true thing about a pad: it is there to show you the silhouette, and it
     * is not yours.</p>
     *
     * <p>Stamped first even where a group already put one there — the stamp is idempotent, and going
     * through {@link CarriagePlacer#placeHalfFlatbedPad} rather than reading whatever happens to be
     * in the world is what makes the one-carriage and full-group cases the same code.</p>
     */
    private static Map<BlockPos, BlockState> liftPad(ServerLevel level, CarriageDims dims,
                                                     BuilderMode mode,
                                                     CarriagePlacer.HalfPadSide side) {
        int padLength = CarriagePlacer.halfPadLen(dims);
        List<Integer> padXs = BuilderWorldLayout.ghostGroupCarriages(mode) <= 0
                ? List.of()
                : padPositions(dims);
        if (padXs.size() != 2 || padLength <= 0) {
            return Map.of();
        }
        // Listed low-X first, which is the BACK one — the same order stampTrain places them in.
        int padX = padXs.get(side == CarriagePlacer.HalfPadSide.BACK ? 0 : 1);
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

    /**
     * The bed and rails down the corridor, taken away and handed back.
     *
     * <p>The clearest case of the three. The track is not part of any template, it is not in any
     * build volume, and {@link BuilderWorldLayout#isProtected} already forbids touching it — it has
     * always been scenery that merely looked like blocks. Ghosting it says so.</p>
     *
     * <p>The whole corridor rather than the stretch under the train: a ghost that stopped where the
     * carriages end would put a hard line across the platform at a point that means nothing, and the
     * line would move every time the parked count changed.</p>
     *
     * <p>Only for a mode that has scenery — Train Dimensions builds in open void and has no track to
     * lift.</p>
     */
    private static Map<BlockPos, BlockState> liftTrack(ServerLevel level, CarriageDims dims,
                                                       BuilderMode mode) {
        if (!BuilderWorldSetup.hasScenery(mode)) {
            return Map.of();
        }
        TrackGeometry g = TrackGeometry.from(dims, BuilderWorldLayout.TRAIN_Y);
        BlockPos origin = trackOrigin(dims);
        Map<BlockPos, BlockState> lifted = new LinkedHashMap<>();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

        for (int x = BuilderWorldLayout.MIN_XZ; x <= BuilderWorldLayout.MAX_XZ; x++) {
            for (int y = g.bedY(); y <= g.railY(); y++) {
                for (int z = g.trackZMin(); z <= g.trackZMax(); z++) {
                    probe.set(x, y, z);
                    BlockState state = level.getBlockState(probe);
                    if (state.isAir()) {
                        continue;
                    }
                    lifted.put(new BlockPos(x - origin.getX(), y - origin.getY(),
                            z - origin.getZ()), state);
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
        tag.put(TAG_TRACK, writeCells(cells.track()));
        return tag;
    }

    /** Read back what {@link #toTag} wrote. An absent or malformed blob reads as no ghosts. */
    public static Cells fromTag(CompoundTag tag, HolderGetter<Block> blocks) {
        if (tag == null || tag.isEmpty()) {
            return Cells.EMPTY;
        }
        return new Cells(readCells(tag.getList(TAG_SHELL, Tag.TAG_COMPOUND), blocks),
                readCells(tag.getList(TAG_BACK_PAD, Tag.TAG_COMPOUND), blocks),
                readCells(tag.getList(TAG_FRONT_PAD, Tag.TAG_COMPOUND), blocks),
                readCells(tag.getList(TAG_TRACK, Tag.TAG_COMPOUND), blocks));
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
