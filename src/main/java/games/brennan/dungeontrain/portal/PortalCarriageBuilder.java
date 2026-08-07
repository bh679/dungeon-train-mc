package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.ContainerContentsPlacement;
import games.brennan.dungeontrain.editor.ContainerContentsStore;
import games.brennan.dungeontrain.editor.PortalRoomTemplateStore;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.TrainMembership;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Stamps the portal corridor — both the carriage copy and its static twin.
 *
 * <p><b>Identical by construction.</b> One pure function, {@link #stateAt}, answers "what block
 * belongs at this carriage-local cell". The carriage stamp and the twin stamp both drive it over
 * the same box, so the two copies cannot disagree: there is a single definition of the geometry, not
 * two that have to be kept in sync by review.</p>
 *
 * <p><b>Light saturation, not distance.</b> The crossing zone's floor is sea lanterns. Minecraft
 * block light is the <i>maximum</i> over sources, so with the local value pinned near 15, light
 * leaking in from the adjacent carriage through the near doorway cannot change what the player
 * sees — the two copies read identically however bright the rest of the train is. This is what lets
 * a 9-block carriage do a job that needs 32 blocks in the free-standing version, where a closed door
 * is no help ({@code BlockBehaviour.getLightBlock} returns 1 for anything not solid-render).</p>
 *
 * <p><b>Baffles, for sight rather than light.</b> Staggered walls just inside each door break every
 * straight line between the crossing zone and either doorway, so the player can never see the far
 * end — which is the one place the two copies genuinely differ (the next carriage on one side, the
 * pocket area on the other).</p>
 *
 * <p>Plan view of the interior at floor level, for a default 9×7×7 carriage — {@code D} door,
 * {@code ▓} baffle, {@code ░} lantern floor (the crossing zone), {@code ·} plain floor:</p>
 * <pre>
 *        x:  0  1  2  3  4  5  6  7  8
 *   z=5      ·  ·  ░  ░  ░  ░  ░  ·  ·
 *   z=4      ·  ·  ░  ░  ░  ░  ░  ·  ·
 *   z=3      D  ▓  ░  ░  ░  ░  ░  ▓  D
 *   z=2      ·  ▓  ░  ░  ░  ░  ░  ▓  ·
 *   z=1      ·  ▓  ░  ░  ░  ░  ░  ▓  ·
 * </pre>
 * <p>The walk is a dog-leg: in at {@code z=3}, around to {@code z=4/5} past the first baffle, across
 * the crossing zone, and back to {@code z=3} to leave. No straight line runs from either doorway to
 * the midpoint, because both baffles interrupt the walkway centre.</p>
 * <p>Both baffles block the same side, so the corridor happens to be mirror-symmetric — a look
 * preference rather than a requirement, since a pair's carriage and twin are stamped from the same
 * source whatever their {@link PortalCarriageRole} and nothing is mirrored between them.</p>
 *
 * <p>Follows {@code CarriagePlacer.legacyPlaceAt}'s contract for code-generated carriage geometry:
 * {@code null} means "leave this cell", and writes go through the section-local fast path on the
 * spawn side (Sable relights the sub-level afterwards) or the light engine in an editor plot.</p>
 */
public final class PortalCarriageBuilder {

    /** Corridor shell — walls, floor, ceiling, door planes and baffles. */
    private static final BlockState SHELL = Blocks.STONE_BRICKS.defaultBlockState();
    /** Crossing-zone floor. Light 15 at source, which is what makes external leakage irrelevant. */
    private static final BlockState CROSSING_LIGHT = Blocks.SEA_LANTERN.defaultBlockState();

    /** Pocket-area palette, beyond the twin's far door — deliberately shares nothing with the corridor. */
    static final BlockState POCKET_SHELL = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
    static final BlockState POCKET_FLOOR = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
    private static final BlockState POCKET_LIGHT = Blocks.SHROOMLIGHT.defaultBlockState();
    /** Solid fill behind the twin's dummy door. */
    private static final BlockState PLUG = Blocks.DEEPSLATE.defaultBlockState();
    /** {@link PortalRoomMode#BEDROCK_LOCK}'s skin, one block outside the room box. */
    private static final BlockState LOCK = Blocks.BEDROCK.defaultBlockState();
    /** What a liquid found against a room's outside wall is replaced with — the rock it is cut into. */
    private static final BlockState FLUID_PLUG = Blocks.DEEPSLATE.defaultBlockState();

    private static final int PLUG_DEPTH = 3;

    /** Ceiling lights sit this far in from the room's interior edges, repeating every {@link #LIGHT_SPACING}. */
    private static final int LIGHT_INSET = 2;
    private static final int LIGHT_SPACING = 6;

    /** The carriage variant a portal corridor is authored as: {@code user/templates/portal.nbt}. */
    private static final CarriageVariant PORTAL_VARIANT = CarriageVariant.custom("portal");

    /**
     * The carriage variant the cart between the two corridors is authored as:
     * {@code user/templates/portal_middle.nbt}.
     */
    private static final CarriageVariant MIDDLE_VARIANT = CarriageVariant.custom("portal_middle");

    /**
     * The contents authored for the inside of a corridor: {@code contents/portal.nbt}.
     *
     * <p>Interior-sized against the <b>corridor's</b> box rather than a carriage's, so 11×5×5 at the
     * default dims — see {@link CarriageContentsPlacer#contentsDims}. Weighted 0 in
     * {@code contents/weights.json} so it never enters an ordinary carriage's random pick: it is the
     * corridor's own furnishing, and its interior would not fit a carriage in any case.</p>
     */
    private static final CarriageContents PORTAL_CONTENTS = CarriageContents.custom("portal");

    /**
     * The seed and carriage index the corridor's contents are resolved with.
     *
     * <p><b>Fixed, not the carriage's own.</b> Those two values drive the contents sidecar's
     * variant-block picks, and a corridor's blocks have to match its twin exactly — so both copies
     * must resolve the same way. Feeding in a per-carriage index would let the carriage and its twin
     * roll different blocks and tear the crossing open.</p>
     */
    private static final long CONTENTS_SEED = 0L;
    private static final int CONTENTS_INDEX = 0;

    private PortalCarriageBuilder() {}

    /** The contents variant authored for the inside of a portal corridor. */
    public static CarriageContents portalContents() {
        return PORTAL_CONTENTS;
    }

    public static CarriageVariant portalVariant() {
        return PORTAL_VARIANT;
    }

    public static CarriageVariant middleVariant() {
        return MIDDLE_VARIANT;
    }

    /**
     * The volume this structure's two corridors own — see {@link PortalCorridorMask}. Built here
     * because {@code PLUG_DEPTH} is this class's business and nothing else should be guessing it.
     */
    public static PortalCorridorMask corridorMask(PortalStructure structure, CarriageDims dims) {
        return PortalCorridorMask.forStructure(structure, dims, layoutFor(dims), PLUG_DEPTH);
    }

    /**
     * The layout for a world's carriage dims.
     *
     * <p>Note the <b>length is the corridor's, not the carriage's</b> — see
     * {@link PortalCorridorSize}. Everything downstream of the layout (the midpoint the swap fires
     * on, the far door, the far baffle, the crossing zone, the containment bounds) is derived from
     * that number, so this one substitution is what makes the corridor longer than its slot
     * everywhere at once.</p>
     */
    public static PortalCarriageLayout layoutFor(CarriageDims dims) {
        return new PortalCarriageLayout(
            PortalCorridorSize.corridorLength(dims), dims.height(), dims.width());
    }

    /** The box a corridor's blocks occupy — {@code dims} with the corridor's own length. */
    public static CarriageDims corridorDims(CarriageDims dims) {
        return PortalCorridorSize.corridorDims(dims);
    }

    /**
     * Put a portal corridor at {@code origin}: the authored {@code portal} template when one exists,
     * the built-in geometry when it does not.
     *
     * <p><b>Every corridor in the system goes through here</b> — the carriage and both twins alike.
     * That is what keeps a carriage and its twin identical whichever source is live, and it is why
     * editing the template changes both halves of a crossing rather than one.</p>
     *
     * @param relight {@code true} for blocks nothing lifts into a Sable sub-level (a twin standing in
     *                the world, or an editor plot); {@code false} on the spawn path
     */
    public static void stampCorridorFrom(ServerLevel level, BlockPos origin, CarriageDims dims,
                                         boolean relight) {
        stampCorridorFrom(level, origin, dims, relight, /*withContents*/ false);
    }

    /**
     * As above, optionally laying the authored {@code portal} contents into the corridor's interior
     * on top of the shell.
     *
     * <p><b>{@code withContents} is about the editor, not about lighting.</b> The two live paths —
     * the carriage copy and its twin — pass {@code true}, so both halves of a crossing get the same
     * furnishing from the same call and stay identical by construction. The <i>carriage editor's</i>
     * plot passes {@code false}: that plot is captured back into {@code portal.nbt} on save, and
     * stamping the contents into it would bake them into the shell template, which then stamps them
     * again underneath the contents pass. The contents have their own plot to be authored in.</p>
     */
    /**
     * The {@code pairKey} for a corridor that belongs to no pair — the editor plot, which stamps the
     * shell with {@code withContents = false} and so never rolls anything.
     */
    public static final int NO_PAIR = Integer.MIN_VALUE;

    /** {@link #stampCorridorFrom} for a corridor with no pair; only valid without contents. */
    public static void stampCorridorFrom(ServerLevel level, BlockPos origin, CarriageDims dims,
                                         boolean relight, boolean withContents) {
        if (withContents) {
            throw new IllegalArgumentException("corridor contents need a pairKey to roll against");
        }
        stampCorridorFrom(level, origin, dims, relight, false, NO_PAIR);
    }

    public static void stampCorridorFrom(ServerLevel level, BlockPos origin, CarriageDims dims,
                                         boolean relight, boolean withContents, int pairKey) {
        // Looked up against the CORRIDOR's dims, not the world's carriage dims: a corridor template
        // is longer than every other carriage template (PortalCorridorSize), and CarriagePlacer's
        // size gate would reject it against the wrong figure and silently drop to the built-in.
        Optional<StructureTemplate> stored =
            CarriageTemplateStore.get(level, PORTAL_VARIANT, PortalCorridorSize.corridorDims(dims));
        if (stored.isPresent()) {
            CarriagePlacer.stampTemplateAt(level, origin, stored.get(), relight);
        } else {
            stampBuiltIn(level, origin, dims, relight);
        }
        if (withContents) stampCorridorContents(level, origin, dims, pairKey);
    }

    /**
     * Lay the {@code portal} contents into a corridor's interior.
     *
     * <p><b>Blocks only.</b> {@code CarriageContentsPlacer} can also spawn the template's entities and
     * roll its loot, and neither belongs here: a corridor has to read identically to its twin, and a
     * mob that wandered or a chest that was opened would differ between the copies the moment anyone
     * touched it.</p>
     *
     * <p>The world's carriage dims go in, not the corridor's — the placer resolves the corridor box
     * itself from the contents id ({@link CarriageContentsPlacer#contentsDims}), and handing it an
     * already-resolved box would grow it a second time.</p>
     */
    private static void stampCorridorContents(ServerLevel level, BlockPos origin, CarriageDims dims,
                                              int pairKey) {
        // The pair's rolled sub-variant, not the literal `portal` template: the contents carry a
        // group sidecar, and naming the parent here is what used to make every corridor in every
        // world identical. PortalCorridorContents holds the draw so this pair's carriage and its
        // twin cannot disagree. CONTENTS_SEED/CONTENTS_INDEX still govern the sidecar's per-cell
        // picks WITHIN the chosen template, which is a separate thing and still has to be fixed.
        CarriageContents contents = PortalCorridorContents.forPair(level, pairKey);
        CarriageContentsPlacer.placeBlocksOnly(
            level, origin, contents, dims, CONTENTS_SEED, CONTENTS_INDEX);
    }

    /**
     * What belongs at carriage-local {@code (dx, dy, dz)}, or {@code null} for "leave this cell"
     * — the same convention {@code CarriagePlacer.stateAt} uses for legacy carriage geometry.
     *
     * <p>This is the single definition of the corridor's shape. Both copies are stamped from it,
     * which is what makes them identical by construction rather than by inspection.</p>
     */
    public static BlockState stateAt(PortalCarriageLayout layout, int dx, int dy, int dz) {
        boolean shellCell = dy == layout.floorY() || dy == layout.ceilingY()
            || dz < layout.interiorMinZ() || dz > layout.interiorMaxZ();

        if (dy == layout.floorY() && layout.isCrossingZone(dx)
            && dz >= layout.interiorMinZ() && dz <= layout.interiorMaxZ()) {
            return CROSSING_LIGHT;
        }
        if (shellCell) return SHELL;

        // Door planes: the cross-section is walled off apart from the doorway column.
        if (dx == layout.nearDoorX() || dx == layout.farDoorX()) {
            boolean doorway = dz == layout.doorZ() && dy <= layout.floorY() + 2;
            return doorway ? doorState(dy == layout.floorY() + 1) : SHELL;
        }

        // Baffles: both on the same side, so no straight line survives from either doorway to the
        // crossing zone AND the corridor is mirror-symmetric — an entry and an exit carriage stamp
        // identical blocks, differing only in which half the swap rule treats as the train side.
        if ((dx == layout.nearBaffleX() || dx == layout.farBaffleX()) && dz <= layout.baffleZ()) {
            return SHELL;
        }

        return null;
    }

    private static BlockState doorState(boolean lower) {
        return Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.EAST)
            .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
            .setValue(DoorBlock.OPEN, false)
            .setValue(DoorBlock.POWERED, false)
            .setValue(DoorBlock.HALF, lower ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
    }

    /**
     * Stamp the corridor as a carriage at {@code origin} (minimum corner), returning the filled
     * positions for {@code ShipAssembler.assembleToShip} — same contract as
     * {@code CarriagePlacer.legacyPlaceAt}.
     *
     * @param relight {@code true} in an editor plot, which is never lifted into a sub-level;
     *                {@code false} on the spawn path, where Sable relights the plot afterwards
     */
    public static Set<BlockPos> stampCarriage(ServerLevel level, BlockPos origin, CarriageDims dims,
                                              boolean relight, int pairKey) {
        stampCorridorFrom(level, origin, dims, relight, /*withContents*/ true, pairKey);
        return Set.of();   // the caller re-reads the footprint via CarriagePlacer.finishPlace
    }

    /**
     * Put the cart that sits between a portal's two corridors at {@code origin}: the authored
     * {@code portal_middle} template when one exists, the built-in geometry when it does not.
     *
     * <p><b>Nobody can walk into this carriage.</b> Going forward, the entry corridor swaps you into
     * the twin before you reach its far door; coming back, the exit corridor's near half swaps you
     * out before you reach it. So it is sealed space by construction — which is exactly why it is a
     * template rather than a rolled variant. Under the old spacing the carriages in this gap were
     * ordinary ones, each rolling a variant and taking parts, contents, loot and mobs that no player
     * would ever see. One authored carriage says what it is.</p>
     *
     * <p><b>Almost all of it now belongs to the corridors.</b> Each corridor overruns
     * {@link PortalCorridorSize#overrun} blocks into this slot, leaving only
     * {@link PortalCorridorSize#centreWallWidth} columns at the exact centre of the group. Those are
     * stamped as a plain solid wall and the rest is left alone — deliberately <b>not</b> written,
     * rather than written and overwritten, because the two corridors are stamped either side of this
     * one (slots 0 and 2) and a full-slot stamp here would erase whichever of them went down first.
     * Skipping makes the result the same in any placement order.</p>
     *
     * <p>The authored {@code portal_middle} template is only honoured when the corridors have not
     * eaten into this slot at all ({@code overrun == 0}, which happens only for carriages long enough
     * to hit {@link games.brennan.dungeontrain.train.CarriageDims#MAX_LENGTH}). At every ordinary
     * carriage length there is no longer a cart-shaped space for it to describe.</p>
     */
    public static Set<BlockPos> stampMiddle(ServerLevel level, BlockPos origin, CarriageDims dims,
                                            boolean relight) {
        if (PortalCorridorSize.overrun(dims) > 0) {
            return stampCentreWall(level, origin, dims, relight);
        }

        Optional<StructureTemplate> stored = CarriageTemplateStore.get(level, MIDDLE_VARIANT, dims);
        if (stored.isPresent()) {
            CarriagePlacer.stampTemplateAt(level, origin, stored.get(), relight);
            return Set.of();
        }
        return stampMiddleBuiltIn(level, origin, dims, relight);
    }

    /**
     * The wall left standing between two corridors that have grown into this slot from both ends:
     * {@link PortalCorridorSize#centreWallWidth} solid columns at the centre of the group.
     *
     * <p>Solid rather than a hollow shell. A hollow one would be a sealed pocket of air nobody can
     * ever reach, which is the waste that lengthening the corridors exists to remove; and at the
     * default carriage length the wall is a single column, where "hollow" has no meaning anyway.</p>
     */
    private static Set<BlockPos> stampCentreWall(ServerLevel level, BlockPos origin,
                                                 CarriageDims dims, boolean relight) {
        Set<BlockPos> placed = new HashSet<>();
        int from = PortalCorridorSize.overrun(dims);
        int to = from + PortalCorridorSize.centreWallWidth(dims);

        for (int dx = from; dx < to; dx++) {
            for (int dz = 0; dz < dims.width(); dz++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (relight) {
                        level.setBlock(pos, SHELL, Block.UPDATE_ALL);
                    } else {
                        SilentBlockOps.setBlockSectionLocal(level, pos, SHELL);
                    }
                    placed.add(pos.immutable());
                }
            }
        }
        return placed;
    }

    /**
     * The built-in cart geometry: a sealed shell with a hollow interior.
     *
     * <p>Sealed because that is the truth about the space — there is no way in, and a doorway would
     * suggest otherwise. Hollow rather than solid because the editor opens this plot to author the
     * real one in, and there has to be somewhere to stand.</p>
     */
    private static Set<BlockPos> stampMiddleBuiltIn(ServerLevel level, BlockPos origin,
                                                    CarriageDims dims, boolean relight) {
        Set<BlockPos> placed = new HashSet<>();

        for (int dx = 0; dx < dims.length(); dx++) {
            for (int dz = 0; dz < dims.width(); dz++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    boolean shell = dx == 0 || dx == dims.length() - 1
                        || dy == 0 || dy == dims.height() - 1
                        || dz == 0 || dz == dims.width() - 1;
                    if (!shell) continue;

                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (relight) {
                        level.setBlock(pos, SHELL, Block.UPDATE_ALL);
                    } else {
                        SilentBlockOps.setBlockSectionLocal(level, pos, SHELL);
                    }
                    placed.add(pos.immutable());
                }
            }
        }
        return placed;
    }

    /**
     * Stamp the built-in geometry into the world so it can be captured as a template — deliberately
     * the built-in and not {@link #stampCorridorFrom}, so re-running the capture always writes out
     * the original corridor rather than a copy of whatever is already saved.
     */
    public static void stampBuiltInForCapture(ServerLevel level, BlockPos origin, CarriageDims dims) {
        clearBox(level, origin, dims);
        stampBuiltIn(level, origin, dims, /*relight*/ true);
    }

    /** Clear a corridor-sized box to air — {@link PortalCorridorSize#corridorLength} along X. */
    public static void clearBox(ServerLevel level, BlockPos origin, CarriageDims dims) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < PortalCorridorSize.corridorLength(dims); dx++) {
            for (int dz = 0; dz < dims.width(); dz++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    level.setBlock(pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz),
                        Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    /** The built-in corridor geometry, used when no {@code portal} template has been authored yet. */
    private static Set<BlockPos> stampBuiltIn(ServerLevel level, BlockPos origin, CarriageDims dims, boolean relight) {
        PortalCarriageLayout layout = layoutFor(dims);
        Set<BlockPos> placed = new HashSet<>();

        for (int dx = 0; dx < layout.length(); dx++) {
            for (int dz = 0; dz < dims.width(); dz++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    BlockState state = stateAt(layout, dx, dy, dz);
                    if (state == null) continue;

                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (relight) {
                        level.setBlock(pos, state, Block.UPDATE_ALL);
                    } else {
                        SilentBlockOps.setBlockSectionLocal(level, pos, state);
                    }
                    placed.add(pos.immutable());
                }
            }
        }
        return placed;
    }

    /**
     * Stamp the static twin at {@code origin}: the same corridor, plus the surroundings the carriage
     * gets from the train — a plug behind its dummy near door and the pocket area beyond its real
     * far door.
     *
     * <p>Unlike the carriage, this lands in existing terrain, so the box is cleared to air first and
     * written through the light engine — nothing lifts these blocks into a sub-level to relight
     * them.</p>
     */
    public static void stampTwin(ServerLevel level, BlockPos origin, CarriageDims dims, int pairKey) {
        // Clear first: unlike a carriage, a twin lands in open air rather than a pre-cleared volume,
        // and a template stamp only writes its own cells — anything already standing there would
        // show through and break the match with the carriage.
        clearBox(level, origin, dims);
        stampCorridorFrom(level, origin, dims, /*relight*/ true, /*withContents*/ true, pairKey);
    }

    /**
     * Decide what a pair's structure is before building it: which room variant it rolls, how big
     * that room turns out to be, and what it does at its walls.
     *
     * <p>The name is a pure function of the world seed and the pair's key, so a pair keeps the same
     * room for the life of the world — re-stamping it further down the track relocates it rather
     * than re-rolling it. The size is read off the authored template, or the built-in room's when
     * nothing has been authored.</p>
     *
     * <p>The {@link PortalRoomSettings settings} are read here and then carried on the record rather
     * than looked up per tick, so an author saving a different mode while somebody is standing in the
     * room cannot change the walls around them mid-visit.</p>
     */
    public static PortalStructure planStructure(ServerLevel level, CarriageDims dims,
                                                BlockPos entryOrigin, int pairKey) {
        String roomName = TrackVariantRegistry.pickName(
            TrackKind.PORTAL_ROOM, level.getSeed(), pairKey);
        return new PortalStructure(entryOrigin, roomName,
            PortalRoomTemplateStore.sizeOf(level, roomName, dims),
            PortalRoomSettings.of(roomName),
            PortalRoomTiling.base());
    }

    /**
     * Stamp a whole pair structure:
     *
     * <pre>
     *   [plug] [entry twin] [room] [exit twin] [plug]
     * </pre>
     *
     * <p>Each twin's dead side is plugged: the entry twin's near door has nothing behind it (its
     * near half maps to the entry carriage), and the exit twin's far door likewise (its far half
     * maps to the exit carriage). The room opens onto both corridors, so a player walks in from the
     * train through one and out to the train through the other without turning round.</p>
     *
     * <p><b>Rooms first, corridors last.</b> The room is stamped, the mode gets to act on it, and only
     * then do the two twins go down — so whatever the room did, a corridor's blocks are written over
     * the top of it and end up identical to the carriage they mirror. That ordering used to be the
     * other way round, on the reasoning that the base room's box stops one block short of each door
     * plane and so could never reach them. True for the base room; not true once the endless modes
     * started putting copies of it along the corridor row, which land exactly where a twin goes. Now
     * the room clears that space and the corridor is placed into it — once. Copies stamped later are
     * masked off that volume rather than overwriting and being repaired; see
     * {@link PortalCorridorMask}.</p>
     */
    public static void stampPairStructure(ServerLevel level, PortalStructure structure,
                                          CarriageDims dims, int pairKey) {
        PortalCarriageLayout layout = layoutFor(dims);
        BlockPos roomOrigin = structure.roomOrigin(dims, layout);
        Vec3i roomSize = structure.roomSize();

        stampRoomAt(level, roomOrigin, dims, structure.roomName(), roomSize, /*relight*/ true,
            PortalCorridorMask.NONE, PortalCorridorMask.NONE,
            structure.variantIndexFor(PortalRoomTiling.Tile.BASE), pairKey, PortalRoomTiling.Tile.BASE,
            PortalRoomMobs.liveCount(level, footprintOf(level, structure, dims), pairKey));

        // Before the corridors, so each mode acts on the room as it actually turned out rather than
        // as it was asked for. It does not follow that the corridors repair whatever a mode wrote at
        // a door plane — they are stamped over their own volume only, and never over the room's end
        // column one block inside it, which is why nothing may write there in the first place; see
        // PortalCorridorMask#facedBy. Bedrock Lock wraps the room; the endless modes settle its own
        // side walls, which for Endless Open means taking them away so there is somewhere to walk
        // out to.
        if (structure.mode() == PortalRoomMode.BEDROCK_LOCK) {
            bedrockSkin(level, roomOrigin, roomSize);
        } else if (structure.mode().tiles()) {
            PortalRoomTiler.refreshFacesAround(level, dims, structure, PortalRoomTiling.Tile.BASE);
        }

        stampCorridors(level, structure, dims, pairKey);
    }

    /**
     * Lay both twin corridors into whatever is currently standing: the corridors themselves, the seal
     * ring around each mouth, and the plug beyond each outer door.
     *
     * <p><b>Once per structure, and last.</b> A twin has to be block-identical to the carriage it
     * mirrors or the crossing shows a seam. Placing it after the room settles that against the base
     * room; keeping it placed is {@link PortalCorridorMask}'s job — every later write from the endless
     * tiling skips the volume the corridors own, so there is nothing to repair and this never runs
     * again for the life of the structure.</p>
     *
     * <p>Each twin's dead side is plugged: the entry twin's near door has nothing behind it (its near
     * half maps to the entry carriage), and the exit twin's far door likewise.</p>
     */
    public static void stampCorridors(ServerLevel level, PortalStructure structure,
                                      CarriageDims dims, int pairKey) {
        PortalCarriageLayout layout = layoutFor(dims);
        BlockPos entryOrigin = structure.origin();
        BlockPos exitOrigin = structure.exitOrigin(dims);
        BlockPos roomOrigin = structure.roomOrigin(dims, layout);
        Vec3i roomSize = structure.roomSize();

        // Both twins take the pair's key, so both match the carriages they mirror — and each other.
        stampTwin(level, entryOrigin, dims, pairKey);
        stampTwin(level, exitOrigin, dims, pairKey);

        // Seal the ring around each corridor mouth. The room's shell is wider and taller than a
        // corridor, so everything it does not already cover at the door plane has to be walled off,
        // leaving that plane — and the door hanging in it — untouched.
        sealCorridorMouth(level, entryOrigin.getX() + layout.length() - 1, entryOrigin, dims,
            roomOrigin, roomSize);
        sealCorridorMouth(level, exitOrigin.getX(), exitOrigin, dims, roomOrigin, roomSize);

        // Dead space behind the door that leads nowhere, at each outer end.
        plugBeyond(level, entryOrigin.offset(-PLUG_DEPTH, 0, 0), PLUG_DEPTH, dims);
        plugBeyond(level, exitOrigin.offset(layout.length(), 0, 0), PLUG_DEPTH, dims);
    }

    /**
     * Lowest Y a structure may write to: one row under its floor, but never the world's own bottom
     * layer.
     *
     * <p>Shared by the skin that writes there and the {@link #eraseTwin} sweep that clears it, so the
     * two cannot disagree — a structure must never leave behind a block its own erase will not
     * reach. The clamp matters because {@link PortalTwinLanes#FLOOR_MARGIN} puts the lowest lane's
     * floor one block above {@code getMinBuildHeight()}, and nothing can be placed below that: the
     * skin would silently drop its bottom row while the erase swept a row that never existed.</p>
     */
    static int lowestWritableY(int worldMinY, int structureFloorY) {
        return Math.max(worldMinY + 1, structureFloorY - 1);
    }

    /**
     * Wrap a room in one block of bedrock — {@link PortalRoomMode#BEDROCK_LOCK}.
     *
     * <p><b>Outside the box, not instead of it.</b> The skin sits one block beyond each face, so an
     * authored room still looks like whatever its author built; the bedrock is only ever met by
     * somebody digging through that. A room whose own shell was replaced with bedrock would read as
     * a vault regardless of what was authored, which is a different feature.</p>
     *
     * <p><b>Four faces, not six.</b> The room's ±X ends are the two corridors' door planes — the way
     * back to the train, and the one part of a twin that is block-identical to its carriage. Skinning
     * those would either wall the player in or break that identity, so the lock covers the sides, the
     * ceiling and the floor and leaves the doors alone. It follows that a determined player can still
     * dig out sideways through a corridor's own wall and its plug; sealing that would mean changing
     * corridor geometry, which is shared with the carriage and cannot move.</p>
     */
    private static void bedrockSkin(ServerLevel level, BlockPos roomOrigin, Vec3i size) {
        int x0 = roomOrigin.getX();
        int x1 = x0 + size.getX() - 1;
        int z0 = roomOrigin.getZ();
        int z1 = z0 + size.getZ() - 1;
        int floorY = roomOrigin.getY();
        int ceilingY = floorY + size.getY() - 1;
        int belowY = lowestWritableY(level.getMinBuildHeight(), floorY);
        int aboveY = ceilingY + 1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Sides, running the full height of the skin so its corners meet the top and bottom planes.
        for (int x = x0; x <= x1; x++) {
            for (int y = belowY; y <= aboveY; y++) {
                level.setBlock(pos.set(x, y, z0 - 1), LOCK, Block.UPDATE_ALL);
                level.setBlock(pos.set(x, y, z1 + 1), LOCK, Block.UPDATE_ALL);
            }
        }

        // Ceiling and floor, out to the sides so nothing can be tunnelled around a corner.
        for (int x = x0; x <= x1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                level.setBlock(pos.set(x, aboveY, z), LOCK, Block.UPDATE_ALL);
                // Only when there is genuinely a row below the floor to write — in the lowest lane
                // there is not, and the world's own bedrock is already doing the job.
                if (belowY < floorY) level.setBlock(pos.set(x, belowY, z), LOCK, Block.UPDATE_ALL);
            }
        }
    }

    /**
     * Clear a twin back to air — corridor, plug, pocket and any standing room copies alike.
     *
     * <p>Called when a twin is superseded because the train has rolled away from it. Without it the
     * train would trail a line of abandoned corridors hanging in the sky, one for every time a
     * portal carriage drifted out of range.</p>
     *
     * <p><b>The tiled terms are a backstop, not the usual path.</b> A structure is drained back to
     * its base tile before it is allowed to be re-stamped ({@code PortalCarriageEvents} refuses while
     * copies are still standing, retiring them a few per tick instead), so in the ordinary case
     * {@code tiledMinX..tiledMaxZ} are just the base room's own bounds and this sweeps what it always
     * did. They are read anyway so that correctness does not depend on the drain having finished —
     * only the cost does.</p>
     */
    public static void eraseTwin(ServerLevel level, PortalStructure structure, CarriageDims dims) {
        PortalClear.clearBox(level, footprintOf(level, structure, dims), PortalCorridorMask.NONE);
    }

    /**
     * Every block a structure currently occupies: both corridors, both plugs, the room between them,
     * every standing copy of that room, and the block of margin their closed faces and Bedrock Lock's
     * skin sit in.
     *
     * <p><b>One definition, read by both sides.</b> {@link #eraseTwin} sweeps exactly this, and
     * {@code PortalRoomTiler} tests candidate copies against it so no two pairs stamp into each
     * other. A structure that wrote outside its own footprint would leave blocks its erase never
     * reaches; one that claimed more than it wrote would refuse copies for no reason. Deriving both
     * from here is what stops the two drifting apart.</p>
     *
     * <p>Measured off the structure record rather than constants: the pair may have rolled a longer
     * room than the built-in one, and erasing the built-in span would leave its tail hanging at the
     * world floor.</p>
     */
    public static BoundingBox footprintOf(ServerLevel level, PortalStructure structure,
                                          CarriageDims dims) {
        PortalCarriageLayout layout = layoutFor(dims);
        BlockPos origin = structure.origin();
        BlockPos roomOrigin = structure.roomOrigin(dims, layout);
        Vec3i roomSize = structure.roomSize();

        int minX = Math.min(origin.getX() - PLUG_DEPTH, structure.tiledMinX(dims, layout) - 1);
        // Both corridors, the room between them, and the plug past the far end.
        int maxX = Math.max(origin.getX() + structure.spanX(dims) + PLUG_DEPTH,
            structure.tiledMaxX(dims, layout) + 1);
        int minZ = Math.min(Math.min(origin.getZ() - 1, roomOrigin.getZ() - 1),
            structure.tiledMinZ(dims, layout) - 1);
        int maxZ = Math.max(Math.max(origin.getZ() + dims.width(), roomOrigin.getZ() + roomSize.getZ()),
            structure.tiledMaxZ(dims, layout) + 1);
        // One row below the floor as well as one past the top: Bedrock Lock skins both.
        int minY = lowestWritableY(level.getMinBuildHeight(), origin.getY());
        int maxY = origin.getY() + Math.max(dims.height(), roomSize.getY());

        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Solid rock filling {@code depth} blocks from {@code from} along +X, across the corridor's
     * cross-section — the dead space behind a twin's door that leads nowhere, so nothing is reachable
     * or visible through it if it is ever forced open.
     */
    private static void plugBeyond(ServerLevel level, BlockPos from, int depth, CarriageDims dims) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < depth; dx++) {
            for (int dz = -1; dz <= dims.width(); dz++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    pos.set(from.getX() + dx, from.getY() + dy, from.getZ() + dz);
                    level.setBlock(pos, PLUG, Block.UPDATE_ALL);
                }
            }
        }
    }

    /**
     * The pocket room between the two twins — a space in a palette nothing in the corridors shares,
     * so stepping out of either one reads unmistakably as arriving somewhere else.
     *
     * <p>Open at <b>both</b> ends: the entry twin's far door feeds it and the exit twin's near door
     * leads out of it, which is what lets a player cross the room and rejoin the train facing the
     * same way they set off.</p>
     *
     * <p>The authored {@code portal_room} template when one exists at exactly {@code size}, the
     * built-in geometry when it does not — the same arrangement {@link #stampCorridorFrom} has, and
     * what gives the editor a non-empty plot to author the first real room in. {@code size} is
     * passed rather than re-derived so the caller's seal ring and erase box cannot disagree with
     * what was actually stamped.</p>
     *
     * <p>Takes an explicit origin, so putting a second room alongside this one is another call at
     * {@code roomOrigin.offset(0, 0, ±size.getZ())} rather than a rewrite.</p>
     */
    public static void stampRoomAt(ServerLevel level, BlockPos roomOrigin, CarriageDims dims,
                                   String roomName, Vec3i size, boolean relight) {
        stampRoomAt(level, roomOrigin, dims, roomName, size, relight, PortalCorridorMask.NONE);
    }

    /**
     * {@link #stampRoomAt} plus the room's authored block-variant sidecar, rolled at
     * {@code variantIndex}.
     *
     * <p><b>Portal rooms could always author variants and never actually got them.</b> The editor has
     * been saving a {@code .variants.json} beside every room, and nothing on the world side ever read
     * it — the template was stamped raw, so per-cell variant picks, container-contents pools and
     * linked loot prefabs all sat dead on disk. This is the read, done the same way
     * {@code TunnelPlacer} does it for tunnels.</p>
     *
     * <p>{@code variantIndex} is what makes one copy of a room differ from another under
     * {@link PortalRoomCopies#DYNAMIC}, and what makes them identical under
     * {@link PortalRoomCopies#EXACT} — see {@code PortalStructure.variantIndexFor}.</p>
     */
    /**
     * {@link #stampRoomAt} with the two jobs a mask does held apart.
     *
     * <p>{@code clearMask} is what must be left <b>standing</b>; {@code writeMask} is what must be
     * left <b>empty</b>. They are the same mask everywhere but an
     * {@link PortalRoomMode#ENDLESS_OPEN} tile, which adds its own interior to the write mask so the
     * stamp lays a floor and a roof and nothing between them.</p>
     *
     * <p>They cannot be one mask. A room lands in solid rock at the world floor, so the interior has
     * to be cleared to air even when nothing is stamped into it — widening the clear mask instead
     * would leave deepslate standing where the open space belongs. The clear stays corridor-only and
     * only the writes are suppressed.</p>
     *
     * <p>This replaces stamping the whole room and then stripping the interior back out. That order
     * placed every chest and rolled its loot pool before breaking it a moment later, and the break
     * ran through a plain {@code setBlock} over a live block entity — so each copy sprayed its
     * contents across the floor. See {@link PortalClear} for the same hazard, found earlier.</p>
     */
    public static void stampRoomAt(ServerLevel level, BlockPos roomOrigin, CarriageDims dims,
                                   String roomName, Vec3i size, boolean relight,
                                   PortalCorridorMask clearMask, PortalCorridorMask writeMask,
                                   int variantIndex, int pairKey, PortalRoomTiling.Tile tile,
                                   int liveMobCount) {
        stampRoomAt(level, roomOrigin, dims, roomName, size, relight, clearMask, writeMask);
        applyRoomVariants(level, roomOrigin, roomName, size, writeMask, variantIndex, pairKey, tile,
            liveMobCount);
    }

    /**
     * Roll and place the room's per-cell variant picks over a stamp that has already landed.
     *
     * <p>Mirrors {@code TunnelPlacer}'s sidecar pass: resolve each authored cell, drop the ones the
     * corridor mask owns, blank the explicit "empty" placeholder, and put everything else down
     * through {@code ContainerContentsPlacement} so chests roll their pool and signs keep their
     * authored NBT.</p>
     *
     * <p>Mob entries go through {@link PortalRoomMobs}, which spawns them and — just as importantly —
     * takes them away when the copy they are standing in retires. They used to be dropped with a
     * warning, on the grounds that a portal room repeats and a mob entry spawning per copy would be a
     * spawner with a hundred outlets. That was true of spawning alone; it is the paired reap that
     * makes it safe, not the spawn being clever.</p>
     */
    private static void applyRoomVariants(ServerLevel level, BlockPos roomOrigin, String roomName,
                                          Vec3i size, PortalCorridorMask mask, int variantIndex,
                                          int pairKey, PortalRoomTiling.Tile tile,
                                          int liveMobCount) {
        TrackVariantBlocks sidecar = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, roomName, size);
        if (sidecar.isEmpty()) return;

        long worldSeed = level.getSeed();
        // Must be the key the EDITOR saved the pool under, or the authored contents are looked up in
        // a file that does not exist and every chest in the room rolls nothing. ContainerContentsStore
        // takes "track:<kind>:<name>" and sanitises the colons into a filename, so a portal room's
        // pool lives at containers/track_portal_room_<name>.contents.json.
        String plotKey = ContainerContentsStore.trackPlotKey(TrackKind.PORTAL_ROOM, roomName);
        // Counted once for the whole stamp rather than per cell: it is an entity query over the
        // structure, and the cap only has to be approximately right — it is a backstop against a
        // badly-weighted room, not an exact quota.
        int live = liveMobCount;
        for (CarriageVariantBlocks.Entry entry : sidecar.entries()) {
            BlockPos local = entry.localPos();
            BlockPos world = roomOrigin.offset(local);
            if (mask.covers(world)) continue;

            VariantState picked = sidecar.resolve(local, worldSeed, variantIndex);
            if (picked == null) continue;
            if (picked.isMob()) {
                // The cell itself still has to go: a mob entry carries a COMMAND_BLOCK sentinel as
                // its state so every block applier blanks it without a special case.
                level.setBlock(world, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                if (PortalRoomMobs.spawn(level, world, picked, pairKey, tile, worldSeed, live)) {
                    live++;
                }
                continue;
            }
            if (CarriageVariantBlocks.isEmptyPlaceholder(picked.state())) {
                level.setBlock(world, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                continue;
            }
            ContainerContentsPlacement.place(level, world, picked.state(), picked.blockEntityNbt(),
                plotKey, local, worldSeed, variantIndex, picked.linkedLootPrefabId());
        }
    }

    /**
     * {@link #stampRoomAt} that leaves every cell {@code mask} covers alone.
     *
     * <p>How a copy of the room on the corridor row is stamped around the twins instead of over them
     * — which is what lets a twin be placed once and never touched again. See
     * {@link PortalCorridorMask}.</p>
     */
    public static void stampRoomAt(ServerLevel level, BlockPos roomOrigin, CarriageDims dims,
                                   String roomName, Vec3i size, boolean relight,
                                   PortalCorridorMask mask) {
        stampRoomAt(level, roomOrigin, dims, roomName, size, relight, mask, mask);
    }

    /** {@link #stampRoomAt} with the clear mask and the write mask held apart. */
    public static void stampRoomAt(ServerLevel level, BlockPos roomOrigin, CarriageDims dims,
                                   String roomName, Vec3i size, boolean relight,
                                   PortalCorridorMask clearMask, PortalCorridorMask writeMask) {
        // Clear first, for the same reason a twin does: the room lands in solid rock at the world
        // floor, and a template stamp only writes its own cells — anything the author left as
        // STRUCTURE_VOID would otherwise show deepslate through the wall. This is the CLEAR mask
        // deliberately: an ENDLESS_OPEN tile still needs its interior emptied, it just does not
        // want anything put back into it.
        clearRoomBox(level, roomOrigin, size, clearMask, relight);
        clearIntruders(level, roomOrigin, size);
        plugFluidsAround(level, roomOrigin, size);

        Optional<StructureTemplate> stored = PortalRoomTemplateStore.get(level, roomName, dims);
        if (stored.isEmpty()) {
            stampRoomBuiltIn(level, roomOrigin, size, relight, writeMask);
            return;
        }

        if (stored.get().getSize().equals(size)) {
            CarriagePlacer.stampTemplateAt(level, roomOrigin, stored.get(),
                writeMask.isEmpty() ? null : writeMask.asProcessor(), relight);
            return;
        }

        // The saved room is a different size from the box being stamped — which is what a resize
        // looks like, before the author has saved again at the new size. Keep what they built.
        //
        // The built-in room goes down first so the new box has a complete shell whatever it grew
        // into, and the saved room is then laid over it, clipped to the box so shrinking cannot
        // spill blocks into the plot next door. What the author made survives wherever it still
        // fits, and only genuinely new space comes back as the built-in room. Replacing the whole
        // thing with the built-in room — which is what used to happen — threw the work away on
        // every stepper click.
        stampRoomBuiltIn(level, roomOrigin, size, relight, writeMask);
        CarriagePlacer.stampTemplateAt(level, roomOrigin, stored.get(),
            clipTo(roomOrigin, size, writeMask), relight);
    }

    /**
     * A processor that drops any cell outside {@code roomOrigin + size}, and any cell {@code mask}
     * covers.
     *
     * <p>Only the resize path needs it: a template saved at one size being stamped into another has
     * to be cut off at the new box's edge. Same {@code null}-returning contract
     * {@link PortalCorridorMask} uses, so a dropped cell is left alone rather than written as air.</p>
     */
    private static StructureProcessor clipTo(BlockPos roomOrigin, Vec3i size,
                                             PortalCorridorMask mask) {
        BoundingBox box = new BoundingBox(
            roomOrigin.getX(), roomOrigin.getY(), roomOrigin.getZ(),
            roomOrigin.getX() + size.getX() - 1,
            roomOrigin.getY() + size.getY() - 1,
            roomOrigin.getZ() + size.getZ() - 1);
        return new StructureProcessor() {
            @Override
            public StructureTemplate.StructureBlockInfo processBlock(
                LevelReader level, BlockPos origin, BlockPos pivot,
                StructureTemplate.StructureBlockInfo source,
                StructureTemplate.StructureBlockInfo target,
                StructurePlaceSettings settings
            ) {
                if (!box.isInside(target.pos())) return null;
                return mask.covers(target.pos()) ? null : target;
            }

            @Override
            protected StructureProcessorType<?> getType() {
                return CLIP_TYPE;
            }
        };
    }

    /** Runtime-only, never serialised — same sentinel shape the parts filter uses. */
    private static final StructureProcessorType<StructureProcessor> CLIP_TYPE =
        () -> com.mojang.serialization.MapCodec.unit(
            clipTo(BlockPos.ZERO, Vec3i.ZERO, PortalCorridorMask.NONE));

    /**
     * Discard whatever was standing in the volume a room is about to occupy.
     *
     * <p>A room is carved out of the rock at the world floor, so anything found in it arrived by
     * spawning there — a mob in a cave the box happens to cross, an item that fell down a ravine —
     * and none of it is what the author built. Players are never discarded.</p>
     *
     * <p>Safe against the mobs a player deliberately leads in, because this only ever runs on a
     * volume that is about to be cleared to air anyway: a fresh structure, or a copy being stamped
     * into solid rock. A copy that already has somebody's villager standing in it is never
     * re-stamped — {@code PortalRoomTiler} refuses to retire a copy anybody is in, and a structure
     * with a player inside is pinned against re-stamping altogether.</p>
     *
     * <p><b>What DT itself placed is spared</b>, by the same contents tag the train's runway sweep
     * reads. Without that this would delete the room's own authored mobs on the next stamp of the
     * copy they stand in, which — since a copy is re-stamped every time the window slides back over
     * it — is most of them, most of the time. "Whatever was standing here" has to mean whatever
     * arrived on its own.</p>
     */
    private static void clearIntruders(ServerLevel level, BlockPos origin, Vec3i size) {
        AABB box = new AABB(
            origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + size.getX(), origin.getY() + size.getY(), origin.getZ() + size.getZ());
        for (Entity entity : level.getEntities((Entity) null, box,
                e -> !(e instanceof Player) && !TrainMembership.isOnTrain(e))) {
            entity.discard();
        }
    }

    /**
     * Replace any liquid in the one-block skin around a room with the rock it is cut into.
     *
     * <p>Clearing the box to air is not enough on its own: the world floor has aquifers and lava
     * down there, and a room carved beside one has its wall become the dam holding it back. The
     * moment anything opens that wall — an Endless Open face, a seam carved between copies, a player
     * with a pickaxe — it floods. Turning the fluid immediately outside the box into stone plugs it
     * at the source instead, which is bounded work and holds however the room is opened up later.</p>
     */
    private static void plugFluidsAround(ServerLevel level, BlockPos origin, Vec3i size) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= size.getX(); dx++) {
            for (int dy = -1; dy <= size.getY(); dy++) {
                for (int dz = -1; dz <= size.getZ(); dz++) {
                    boolean skin = dx == -1 || dx == size.getX()
                        || dy == -1 || dy == size.getY()
                        || dz == -1 || dz == size.getZ();
                    if (!skin) continue;
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (level.getFluidState(pos).isEmpty()) continue;
                    level.setBlock(pos, FLUID_PLUG, Block.UPDATE_ALL);
                }
            }
        }
    }

    /** Clear a room-sized box to air, leaving whatever {@code mask} covers untouched. */
    /**
     * Empty the room's box before it is stamped.
     *
     * <p>{@code relight} follows the stamp's own flag rather than being decided here: a twin in the
     * basement is under the bedrock where nothing sees the light, but an editor plot stands under
     * open sky, and a cell cleared there has to stop occluding skylight. See {@link PortalClear}.</p>
     */
    private static void clearRoomBox(ServerLevel level, BlockPos origin, Vec3i size,
                                     PortalCorridorMask mask, boolean relight) {
        BoundingBox box = new BoundingBox(
            origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + size.getX() - 1,
            origin.getY() + size.getY() - 1,
            origin.getZ() + size.getZ() - 1);
        if (relight) {
            PortalClear.clearBoxRelit(level, box, mask);
        } else {
            PortalClear.clearBox(level, box, mask);
        }
    }

    /**
     * The built-in room: a shell of floor, ceiling and two side walls with ceiling lights, in a
     * palette nothing in the corridors shares.
     *
     * <p>Neither end plane is written — both are the corridors' own door planes, and writing over
     * them would delete the doors. The lights repeat rather than sitting at fixed fractions of the
     * length, so a room authored at any length is lit the same way as the default one.</p>
     */
    public static void stampRoomBuiltIn(ServerLevel level, BlockPos roomOrigin, Vec3i size,
                                        boolean relight) {
        stampRoomBuiltIn(level, roomOrigin, size, relight, PortalCorridorMask.NONE);
    }

    public static void stampRoomBuiltIn(ServerLevel level, BlockPos roomOrigin, Vec3i size,
                                        boolean relight, PortalCorridorMask mask) {
        int x0 = roomOrigin.getX();
        int x1 = x0 + size.getX() - 1;
        int zWall0 = roomOrigin.getZ();
        int zWall1 = zWall0 + size.getZ() - 1;
        int z0 = zWall0 + 1;
        int z1 = zWall1 - 1;
        int floorY = roomOrigin.getY();
        int ceilingY = floorY + size.getY() - 1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = x0; x <= x1; x++) {
            for (int z = zWall0; z <= zWall1; z++) {
                for (int y = floorY; y <= ceilingY; y++) {
                    if (mask.covers(x, y, z)) continue;
                    boolean shell = z < z0 || z > z1 || y == floorY || y == ceilingY;
                    BlockState state = !shell ? Blocks.AIR.defaultBlockState()
                        : (y == floorY ? POCKET_FLOOR : POCKET_SHELL);
                    setRoomBlock(level, pos.set(x, y, z), state, relight);
                }
            }
        }

        for (int x = x0 + LIGHT_INSET; x <= x1 - LIGHT_INSET; x += LIGHT_SPACING) {
            for (int z = z0 + LIGHT_INSET; z <= z1 - LIGHT_INSET; z += LIGHT_SPACING) {
                if (mask.covers(x, ceilingY, z)) continue;
                setRoomBlock(level, pos.set(x, ceilingY, z), POCKET_LIGHT, relight);
            }
        }
    }

    private static void setRoomBlock(ServerLevel level, BlockPos pos, BlockState state,
                                     boolean relight) {
        if (relight) {
            level.setBlock(pos, state, Block.UPDATE_ALL);
        } else {
            SilentBlockOps.setBlockSectionLocal(level, pos, state);
        }
    }

    /**
     * Wall off everything in a corridor's door plane that the room's own shell does not cover.
     *
     * <p>Only cells <b>outside</b> the corridor's cross-section are written — the corridor's own
     * blocks, doorway included, are never touched. That is what keeps a twin block-identical to its
     * carriage regardless of what the room is authored as.</p>
     */
    private static void sealCorridorMouth(ServerLevel level, int planeX, BlockPos corridorOrigin,
                                          CarriageDims dims, BlockPos roomOrigin, Vec3i roomSize) {
        int floorY = roomOrigin.getY();
        int ceilingY = floorY + roomSize.getY() - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = roomOrigin.getZ(); z < roomOrigin.getZ() + roomSize.getZ(); z++) {
            for (int y = floorY; y <= ceilingY; y++) {
                boolean coveredByCorridor = z >= corridorOrigin.getZ()
                    && z < corridorOrigin.getZ() + dims.width()
                    && y < floorY + dims.height();
                if (coveredByCorridor) continue;
                level.setBlock(pos.set(planeX, y, z), y == floorY ? POCKET_FLOOR : POCKET_SHELL,
                    Block.UPDATE_ALL);
            }
        }
    }
}
