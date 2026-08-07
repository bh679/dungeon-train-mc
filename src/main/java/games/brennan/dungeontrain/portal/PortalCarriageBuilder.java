package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.PortalRoomTemplateStore;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

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
    private static final BlockState POCKET_SHELL = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
    private static final BlockState POCKET_FLOOR = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
    private static final BlockState POCKET_LIGHT = Blocks.SHROOMLIGHT.defaultBlockState();
    /** Solid fill behind the twin's dummy door. */
    private static final BlockState PLUG = Blocks.DEEPSLATE.defaultBlockState();
    /** {@link PortalRoomMode#BEDROCK_LOCK}'s skin, one block outside the room box. */
    private static final BlockState LOCK = Blocks.BEDROCK.defaultBlockState();

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

    private PortalCarriageBuilder() {}

    public static CarriageVariant portalVariant() {
        return PORTAL_VARIANT;
    }

    public static CarriageVariant middleVariant() {
        return MIDDLE_VARIANT;
    }

    /** The layout for a world's carriage dims. */
    public static PortalCarriageLayout layoutFor(CarriageDims dims) {
        return new PortalCarriageLayout(dims.length(), dims.height(), dims.width());
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
        Optional<StructureTemplate> stored = CarriageTemplateStore.get(level, PORTAL_VARIANT, dims);
        if (stored.isPresent()) {
            CarriagePlacer.stampTemplateAt(level, origin, stored.get(), relight);
            return;
        }
        stampBuiltIn(level, origin, dims, relight);
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
    public static Set<BlockPos> stampCarriage(ServerLevel level, BlockPos origin, CarriageDims dims, boolean relight) {
        stampCorridorFrom(level, origin, dims, relight);
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
     */
    public static Set<BlockPos> stampMiddle(ServerLevel level, BlockPos origin, CarriageDims dims,
                                            boolean relight) {
        Optional<StructureTemplate> stored = CarriageTemplateStore.get(level, MIDDLE_VARIANT, dims);
        if (stored.isPresent()) {
            CarriagePlacer.stampTemplateAt(level, origin, stored.get(), relight);
            return Set.of();
        }
        return stampMiddleBuiltIn(level, origin, dims, relight);
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

    /** Clear a carriage-sized box to air. */
    public static void clearBox(ServerLevel level, BlockPos origin, CarriageDims dims) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < dims.length(); dx++) {
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

        for (int dx = 0; dx < dims.length(); dx++) {
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
    public static void stampTwin(ServerLevel level, BlockPos origin, CarriageDims dims) {
        // Clear first: unlike a carriage, a twin lands in open air rather than a pre-cleared volume,
        // and a template stamp only writes its own cells — anything already standing there would
        // show through and break the match with the carriage.
        clearBox(level, origin, dims);
        stampCorridorFrom(level, origin, dims, /*relight*/ true);
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
     * <p>The {@link PortalRoomMode mode} is read here and then carried on the record rather than
     * looked up per tick, so an author saving a different mode while somebody is standing in the
     * room cannot change the walls around them mid-visit.</p>
     */
    public static PortalStructure planStructure(ServerLevel level, CarriageDims dims,
                                                BlockPos entryOrigin, int pairKey) {
        String roomName = TrackVariantRegistry.pickName(
            TrackKind.PORTAL_ROOM, level.getSeed(), pairKey);
        return new PortalStructure(entryOrigin, roomName,
            PortalRoomTemplateStore.sizeOf(level, roomName, dims),
            PortalRoomMode.parse(TrackVariantWeights.modeFor(TrackKind.PORTAL_ROOM, roomName)),
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
     * <p>Order matters: the twins go down first and the room's own box stops one block short of
     * each door plane, so a corridor's blocks are never written over by the room. That is what keeps
     * a twin identical to its carriage whatever the room is authored as.</p>
     */
    public static void stampPairStructure(ServerLevel level, PortalStructure structure,
                                          CarriageDims dims) {
        PortalCarriageLayout layout = layoutFor(dims);
        BlockPos entryOrigin = structure.origin();
        BlockPos exitOrigin = structure.exitOrigin(dims);
        BlockPos roomOrigin = structure.roomOrigin(dims, layout);
        Vec3i roomSize = structure.roomSize();

        stampTwin(level, entryOrigin, dims);
        stampTwin(level, exitOrigin, dims);
        stampRoomAt(level, roomOrigin, dims, structure.roomName(), roomSize, /*relight*/ true);

        // Seal the ring around each corridor mouth. The room's shell is wider and taller than a
        // corridor, so everything it does not already cover at the door plane has to be walled off,
        // leaving that plane — and the door hanging in it — untouched.
        sealCorridorMouth(level, entryOrigin.getX() + dims.length() - 1, entryOrigin, dims,
            roomOrigin, roomSize);
        sealCorridorMouth(level, exitOrigin.getX(), exitOrigin, dims, roomOrigin, roomSize);

        // Dead space behind the door that leads nowhere, at each outer end.
        plugBeyond(level, entryOrigin.offset(-PLUG_DEPTH, 0, 0), PLUG_DEPTH, dims);
        plugBeyond(level, exitOrigin.offset(dims.length(), 0, 0), PLUG_DEPTH, dims);

        // Last, so it wraps whatever the room turned out to be rather than what it was asked for.
        if (structure.mode() == PortalRoomMode.BEDROCK_LOCK) {
            bedrockSkin(level, roomOrigin, roomSize);
        }
    }

    /**
     * Lowest Y a structure may write to: one row under its floor, but never the world's own bottom
     * layer.
     *
     * <p>Shared by the skin that writes there and the {@link #eraseTwin} sweep that clears it, so the
     * two cannot disagree — a structure must never leave behind a block its own erase will not
     * reach. The clamp matters because {@code TWIN_FLOOR_MARGIN} puts the lowest lane's floor one
     * block above {@code getMinBuildHeight()}, and that row is the world's vanilla bedrock: writing
     * to it is pointless and erasing it would open a hole into the void.</p>
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
        PortalCarriageLayout layout = layoutFor(dims);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();

        // Measured off the structure record rather than a constant: the pair may have rolled a
        // longer room than the built-in one, and erasing the built-in span would leave its tail
        // hanging at the world floor.
        BlockPos origin = structure.origin();
        BlockPos roomOrigin = structure.roomOrigin(dims, layout);
        Vec3i roomSize = structure.roomSize();

        // One block of margin around the tiled rectangle, because both the outer-face closures and
        // Bedrock Lock's skin sit just outside a room box rather than inside it.
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

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    level.setBlock(pos.set(x, y, z), air, Block.UPDATE_ALL);
                }
            }
        }
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
        // Clear first, for the same reason a twin does: the room lands in solid rock at the world
        // floor, and a template stamp only writes its own cells — anything the author left as
        // STRUCTURE_VOID would otherwise show deepslate through the wall.
        clearRoomBox(level, roomOrigin, size);

        Optional<StructureTemplate> stored = PortalRoomTemplateStore.get(level, roomName, dims);
        if (stored.isPresent() && stored.get().getSize().equals(size)) {
            CarriagePlacer.stampTemplateAt(level, roomOrigin, stored.get(), relight);
            return;
        }
        stampRoomBuiltIn(level, roomOrigin, size, relight);
    }

    /** Clear a room-sized box to air. */
    private static void clearRoomBox(ServerLevel level, BlockPos origin, Vec3i size) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = 0; dx < size.getX(); dx++) {
            for (int dz = 0; dz < size.getZ(); dz++) {
                for (int dy = 0; dy < size.getY(); dy++) {
                    level.setBlock(pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz),
                        air, Block.UPDATE_ALL);
                }
            }
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
                    boolean shell = z < z0 || z > z1 || y == floorY || y == ceilingY;
                    BlockState state = !shell ? Blocks.AIR.defaultBlockState()
                        : (y == floorY ? POCKET_FLOOR : POCKET_SHELL);
                    setRoomBlock(level, pos.set(x, y, z), state, relight);
                }
            }
        }

        for (int x = x0 + LIGHT_INSET; x <= x1 - LIGHT_INSET; x += LIGHT_SPACING) {
            for (int z = z0 + LIGHT_INSET; z <= z1 - LIGHT_INSET; z += LIGHT_SPACING) {
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
