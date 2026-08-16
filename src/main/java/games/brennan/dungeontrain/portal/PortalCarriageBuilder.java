package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.ContainerContentsPlacement;
import games.brennan.dungeontrain.editor.ContainerContentsStore;
import games.brennan.dungeontrain.editor.PortalRoomContentsAllowStore;
import games.brennan.dungeontrain.editor.PortalRoomTemplateStore;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsAllowList;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Corridor shell — walls, floor, ceiling, door planes and baffles. */
    private static final BlockState SHELL = Blocks.STONE_BRICKS.defaultBlockState();
    /** Crossing-zone floor. Light 15 at source, which is what makes external leakage irrelevant. */
    private static final BlockState CROSSING_LIGHT = Blocks.SEA_LANTERN.defaultBlockState();

    /**
     * The two blocks between a pair's two dummy doors, while the portal still works — see
     * {@link PortalCentreWall}.
     *
     * <p>Deliberately not the corridor's own {@link #SHELL}. This is the one cell of the wall a player
     * can meet head-on, and a plain plate rather than more masonry is what makes it legible when a
     * severed pair opens it into a walk-through.</p>
     */
    static final BlockState CENTRE_WALL_DOORWAY = Blocks.BLACK_CONCRETE.defaultBlockState();

    /** Pocket-area palette, beyond the twin's far door — deliberately shares nothing with the corridor. */
    static final BlockState POCKET_SHELL = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
    static final BlockState POCKET_FLOOR = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
    private static final BlockState POCKET_LIGHT = Blocks.SHROOMLIGHT.defaultBlockState();
    /** Solid fill behind the twin's dummy door. */
    private static final BlockState PLUG = Blocks.DEEPSLATE.defaultBlockState();
    /**
     * {@link PortalRoomMode#BEDROCK_LOCK}'s unbreakable rock: the skin one block outside the room
     * box and outside each corridor, and the plug behind each twin's outer door — see
     * {@link PortalRoomMode#sealsCorridors}.
     */
    private static final BlockState LOCK = Blocks.BEDROCK.defaultBlockState();
    /** What a liquid found against a room's outside wall is replaced with — the rock it is cut into. */
    private static final BlockState FLUID_PLUG = Blocks.DEEPSLATE.defaultBlockState();

    private static final int PLUG_DEPTH = 3;

    /** Ceiling lights sit this far in from the room's interior edges, repeating every {@link #LIGHT_SPACING}. */
    private static final int LIGHT_INSET = 2;
    private static final int LIGHT_SPACING = 6;

    /**
     * The carriage variant a {@link PortalCorridorKind#LONG} corridor is authored as:
     * {@code user/templates/portal.nbt}.
     */
    private static final CarriageVariant PORTAL_VARIANT = CarriageVariant.custom("portal");

    /**
     * The carriage variant a {@link PortalCorridorKind#SHORT} corridor is authored as:
     * {@code user/templates/portal_short.nbt}.
     *
     * <p><b>A second variant rather than a second size of the first.</b> A template is stored and
     * validated against one box ({@code CarriagePlacer.sizeMatches}), and the two kinds are different
     * boxes — 13×7×7 against 9×7×7 at the default dims. One id could only ever describe one of
     * them, and the other would be silently rejected on size and fall back to the built-in
     * geometry.</p>
     */
    private static final CarriageVariant PORTAL_SHORT_VARIANT = CarriageVariant.custom("portal_short");

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
     * The contents authored for the inside of a {@link PortalCorridorKind#SHORT} corridor:
     * {@code contents/portal_short.nbt}. Interior-sized against a carriage rather than the longer
     * corridor box, and weighted 0 in {@code contents/weights.json} for the same reason
     * {@link #PORTAL_CONTENTS} is.
     */
    private static final CarriageContents PORTAL_SHORT_CONTENTS = CarriageContents.custom("portal_short");

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

    /** The contents variant authored for the inside of a corridor of this kind. */
    public static CarriageContents portalContents(PortalCorridorKind kind) {
        return kind == PortalCorridorKind.SHORT ? PORTAL_SHORT_CONTENTS : PORTAL_CONTENTS;
    }

    /** The carriage variant a corridor of this kind is authored as. */
    public static CarriageVariant portalVariant(PortalCorridorKind kind) {
        return kind == PortalCorridorKind.SHORT ? PORTAL_SHORT_VARIANT : PORTAL_VARIANT;
    }

    /** Both corridor variants, in {@link PortalCorridorKind} order — the pool the kind is drawn from. */
    public static List<CarriageVariant> corridorVariants() {
        return List.of(PORTAL_VARIANT, PORTAL_SHORT_VARIANT);
    }

    /**
     * True if {@code variant} is any part of a portal — either corridor, or the cart between.
     *
     * <p>What keeps the portal templates out of the ordinary carriage pool
     * ({@code CarriagePlacer.variantForIndex}). It used to be a weight of 0 in
     * {@code templates/weights.json}, which had two holes: {@code LOOPING} mode ignores weights
     * outright and cycles every registered variant, and the weights are now what the two corridor
     * kinds are drawn <i>against</i> ({@link PortalCarriageSelection#corridorKindFor}), so they can
     * no longer be 0. A portal carriage is decided by
     * {@link PortalCarriageSelection#isPortalCarriage} and by nothing else.</p>
     */
    public static boolean isPortalVariant(CarriageVariant variant) {
        return variant != null
            && (variant.equals(PORTAL_VARIANT)
             || variant.equals(PORTAL_SHORT_VARIANT)
             || variant.equals(MIDDLE_VARIANT));
    }

    public static CarriageVariant middleVariant() {
        return MIDDLE_VARIANT;
    }

    /**
     * The volume this structure's two corridors own — see {@link PortalCorridorMask}. Built here
     * because {@code PLUG_DEPTH} is this class's business and nothing else should be guessing it.
     *
     * <p>The pair's own corridors only. An endless room's <b>extra</b> corridors are
     * {@link #exitCopyMask}'s business, and the two are unioned at the call site rather than here so
     * that a caller which genuinely means "the base pair" — the erase of a structure that has already
     * drained, say — is not quietly handed a mask that spares copies which are no longer there.</p>
     */
    public static PortalCorridorMask corridorMask(PortalStructure structure, CarriageDims dims) {
        return PortalCorridorMask.forStructure(
            structure, dims, layoutFor(dims, structure.kind()), PLUG_DEPTH);
    }

    /**
     * How far a plug reaches past its corridor.
     *
     * <p>Exposed rather than duplicated: {@link PortalExitCopyTiler} has to size a copy's erase to
     * exactly what {@link #stampCorridorHalf} wrote, and a second copy of this number is a second
     * chance for the two to disagree — which shows up as a ring of plug blocks left standing where a
     * corridor used to be.</p>
     */
    public static int plugDepth() {
        return PLUG_DEPTH;
    }

    /**
     * The volume this structure's <b>extra</b> corridors own — one
     * {@link PortalCorridorMask#forCorridor} per standing {@link PortalExitSites.Site}, unioned.
     *
     * <p>Every write the endless tiling makes has to skip these for exactly the reason it skips the
     * base pair's ({@link PortalCorridorMask}'s javadoc): a copy is laid <b>once</b>, and every room
     * copy stamped afterwards is built around it rather than over it and repaired. The difference is
     * only that this set changes over a visit — a copy appears with its anchor tile and retires long
     * after it — so it is read fresh from the structure each time rather than fixed at build.</p>
     */
    public static PortalCorridorMask exitCopyMask(PortalStructure structure, CarriageDims dims) {
        PortalExitCopies copies = structure.exitCopies();
        if (copies.isEmpty()) return PortalCorridorMask.NONE;
        PortalCarriageLayout layout = layoutFor(dims, structure.kind());
        PortalCorridorMask mask = PortalCorridorMask.NONE;
        for (PortalExitSites.Site site : copies.sites()) {
            mask = mask.plus(PortalCorridorMask.forCorridor(
                structure.shadowAt(site.tile()), dims, layout, PLUG_DEPTH, site.role()));
        }
        return mask;
    }

    /** Everything any corridor of this structure owns: the base pair and every standing copy. */
    public static PortalCorridorMask allCorridorMask(PortalStructure structure, CarriageDims dims) {
        return corridorMask(structure, dims).plus(exitCopyMask(structure, dims));
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
    public static PortalCarriageLayout layoutFor(CarriageDims dims, PortalCorridorKind kind) {
        return new PortalCarriageLayout(
            PortalCorridorSize.corridorLength(dims, kind), dims.height(), dims.width());
    }

    /** The box a corridor's blocks occupy — {@code dims} with the corridor's own length. */
    public static CarriageDims corridorDims(CarriageDims dims, PortalCorridorKind kind) {
        return PortalCorridorSize.corridorDims(dims, kind);
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
                                         PortalCorridorKind kind, boolean relight) {
        stampCorridorFrom(level, origin, dims, kind, relight, /*withContents*/ false);
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
                                         PortalCorridorKind kind, boolean relight,
                                         boolean withContents) {
        if (withContents) {
            throw new IllegalArgumentException("corridor contents need a pairKey to roll against");
        }
        stampCorridorFrom(level, origin, dims, kind, relight, false, NO_PAIR);
    }

    public static void stampCorridorFrom(ServerLevel level, BlockPos origin, CarriageDims dims,
                                         PortalCorridorKind kind, boolean relight,
                                         boolean withContents, int pairKey) {
        // Looked up against the CORRIDOR's dims, not the world's carriage dims: a LONG corridor's
        // template is longer than every other carriage template (PortalCorridorSize), and
        // CarriagePlacer's size gate would reject it against the wrong figure and silently drop to
        // the built-in. A SHORT corridor's box happens to equal a carriage's, which is exactly why
        // the two kinds cannot share one variant id.
        Optional<StructureTemplate> stored = CarriageTemplateStore.get(
            level, portalVariant(kind), PortalCorridorSize.corridorDims(dims, kind));
        if (stored.isPresent()) {
            CarriagePlacer.stampTemplateAt(level, origin, stored.get(), relight);
        } else {
            stampBuiltIn(level, origin, dims, kind, relight);
        }
        applyCorridorVariants(level, origin, dims, kind, pairKey);
        if (withContents) stampCorridorContents(level, origin, dims, kind, pairKey);
    }

    /**
     * Roll the corridor shell's own authored block variants over the stamp that just landed.
     *
     * <p><b>Rolled against the pair's key, not the carriage's index.</b> A crossing is two
     * carriages at different indices that never see each other, and the variant picker keys on
     * {@code (worldSeed, index, lockId)} — so feeding it a per-carriage index lets the two halves
     * of one corridor land on different blocks and tears the crossing open, which is the same trap
     * {@link #CONTENTS_SEED} documents for the contents pass. {@code pairKey} is a pure function of
     * the carriage index ({@code PortalCarriageRole.entryIndexOf}), so both stamp sites derive it
     * identically without either knowing about the other, while still letting different portals
     * differ from one another.</p>
     *
     * <p>Skipped for {@link #NO_PAIR} — the editor plot has no pair to roll against, and showing
     * the author a resolved roll instead of the master blocks would misrepresent what they are
     * editing (the plot is captured back into {@code portal.nbt} on save).</p>
     *
     * <p>Handed the WORLD's carriage dims, not the corridor's. The sidecar does have to be looked
     * up at the corridor box — it is longer than a carriage and the size gate would drop every
     * entry past the carriage's own length — but {@code CarriagePlacer.variantDims} already grows
     * the portal variant's dims on the way in, and {@link PortalCorridorSize#corridorDims} is not
     * idempotent (it adds the overrun each time). Passing an already-grown box would stretch it a
     * second time and miss the template.</p>
     */
    private static void applyCorridorVariants(ServerLevel level, BlockPos origin, CarriageDims dims,
                                              PortalCorridorKind kind, int pairKey) {
        if (pairKey == NO_PAIR) return;
        CarriagePlacer.applyVariantBlocks(level, origin, portalVariant(kind), dims,
            level.getSeed(), pairKey);
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
                                              PortalCorridorKind kind, int pairKey) {
        // The pair's rolled sub-variant, not the literal `portal` template: the contents carry a
        // group sidecar, and naming the parent here is what used to make every corridor in every
        // world identical. PortalCorridorContents holds the draw so this pair's carriage and its
        // twin cannot disagree. CONTENTS_SEED/CONTENTS_INDEX still govern the sidecar's per-cell
        // picks WITHIN the chosen template, which is a separate thing and still has to be fixed.
        CarriageContents contents = PortalCorridorContents.forPair(level, kind, pairKey);
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
        if (dy == layout.floorY() && layout.isCrossingZone(dx)
            && dz >= layout.interiorMinZ() && dz <= layout.interiorMaxZ()) {
            return CROSSING_LIGHT;
        }

        // The exterior surface — floor, ceiling, side walls, and the solid part of each end plane.
        // Defined on the layout because PortalSever has to ask the same question of a broken block,
        // and two copies of this test would be free to disagree about what "outside" means.
        if (layout.isShellCell(dx, dy, dz)) return SHELL;

        // What survives of an end plane once the shell has taken it is the doorway column itself.
        if (dx == layout.nearDoorX() || dx == layout.farDoorX()) {
            return doorState(dy == layout.floorY() + 1);
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
                                              PortalCorridorKind kind, boolean relight, int pairKey) {
        stampCorridorFrom(level, origin, dims, kind, relight, /*withContents*/ true, pairKey);
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
     * <p><b>Under {@link PortalCorridorKind#LONG}, almost all of it belongs to the corridors.</b>
     * Each corridor overruns {@link PortalCorridorSize#overrun} blocks into this slot, leaving only
     * {@link PortalCorridorSize#centreWallWidth} columns at the exact centre of the group. Those are
     * stamped as a plain solid wall and the rest is left alone — deliberately <b>not</b> written,
     * rather than written and overwritten, because the two corridors are stamped either side of this
     * one (slots 0 and 2) and a full-slot stamp here would erase whichever of them went down first.
     * Skipping makes the result the same in any placement order.</p>
     *
     * <p><b>Under {@link PortalCorridorKind#SHORT} the cart survives whole</b>, so the authored
     * {@code portal_middle} template is what stands here — a real carriage-shaped space to describe,
     * which is what that template was for. (The same branch is taken under {@code LONG} at carriage
     * lengths long enough to hit
     * {@link games.brennan.dungeontrain.train.CarriageDims#MAX_LENGTH}, where the overrun is likewise
     * clamped to zero.)</p>
     *
     * <p><b>A severed pair is stamped open, whichever branch runs.</b> The doorway column is left as
     * air when this group's portal has been broken, which turns three carriages of dead end into an
     * ordinary walk-through — see {@link PortalCentreWall}. Read from {@link PortalRegistry} at stamp
     * time rather than remembered, because a corridor's blocks are re-stamped every time the rolling
     * window brings the group round again, and the severing outlives all of them.</p>
     *
     * @param carriageIndex the cart's own index along the track, which resolves the pair whose
     *                      severed state decides whether the wall is opened
     */
    public static Set<BlockPos> stampMiddle(ServerLevel level, BlockPos origin, CarriageDims dims,
                                            PortalCorridorKind kind, boolean relight,
                                            int carriageIndex) {
        boolean severed = isPairSevered(level, carriageIndex);
        if (PortalCorridorSize.overrun(dims, kind) > 0) {
            return stampCentreWall(level, origin, dims, kind, relight, severed);
        }

        Set<BlockPos> placed;
        Optional<StructureTemplate> stored = CarriageTemplateStore.get(level, MIDDLE_VARIANT, dims);
        if (stored.isPresent()) {
            CarriagePlacer.stampTemplateAt(level, origin, stored.get(), relight);
            placed = Set.of();
        } else {
            placed = stampMiddleBuiltIn(level, origin, dims, relight);
        }
        if (!severed) return placed;
        // The whole-cart branch writes a SEALED carriage, so unlike stampCentreWall it has no
        // doorway column of its own to leave open — the opening has to be cut back out afterwards.
        // Without this a severed SHORT pair is three carriages of dead end, which is precisely the
        // outcome PortalCentreWall exists to prevent; the branch was previously unreachable outside
        // MAX_LENGTH carriages, so the gap never showed.
        return openSeveredColumn(level, origin, dims, kind, relight, placed);
    }

    /**
     * Cut {@link PortalCentreWall}'s doorway column out of an already-stamped cart, for a pair that
     * has been severed.
     *
     * <p>Returns a new set rather than adding to the one it was handed: the caller's may be
     * immutable ({@code Set.of()} on the template branch), and the placed-position set is a value
     * the footprint sweep reads, not a buffer to accumulate into.</p>
     */
    private static Set<BlockPos> openSeveredColumn(ServerLevel level, BlockPos origin,
                                                   CarriageDims dims, PortalCorridorKind kind,
                                                   boolean relight, Set<BlockPos> placed) {
        Set<BlockPos> out = new HashSet<>(placed);
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int[] cell : PortalCentreWall.doorwayCells(dims, kind)) {
            BlockPos pos = origin.offset(cell[0], cell[1], cell[2]);
            if (relight) {
                level.setBlock(pos, air, Block.UPDATE_ALL);
            } else {
                SilentBlockOps.clearBlockSilent(level, pos);
            }
            out.add(pos.immutable());
        }
        return out;
    }

    /**
     * True if the pair this carriage belongs to has had its portal severed.
     *
     * <p>Asked of the group's anchor, which is a pair-level question however the break was made:
     * {@link PortalSever} records the broken corridor <i>and</i> its partner, and one of those two is
     * always the anchor.</p>
     */
    private static boolean isPairSevered(ServerLevel level, int carriageIndex) {
        int pairKey = PortalCarriageRole.entryIndexOf(carriageIndex, DungeonTrainConfig.getGroupSize());
        return PortalRegistry.get(level).isSevered(pairKey);
    }

    /**
     * The wall left standing between two corridors that have grown into this slot from both ends:
     * {@link PortalCorridorSize#centreWallWidth} solid columns at the centre of the group.
     *
     * <p>Solid rather than a hollow shell. A hollow one would be a sealed pocket of air nobody can
     * ever reach, which is the waste that lengthening the corridors exists to remove; and at the
     * default carriage length the wall is a single column, where "hollow" has no meaning anyway.</p>
     *
     * <p>Solid <i>except</i> the doorway column between the two dummy doors, which is black concrete
     * while the portal works and air once it has been severed — {@link PortalCentreWall} has the whole
     * of that rule and the reasons for it.</p>
     */
    private static Set<BlockPos> stampCentreWall(ServerLevel level, BlockPos origin,
                                                 CarriageDims dims, PortalCorridorKind kind,
                                                 boolean relight, boolean severed) {
        Set<BlockPos> placed = new HashSet<>();
        int from = PortalCentreWall.minX(dims, kind);
        int to = PortalCentreWall.maxXExclusive(dims, kind);

        for (int dx = from; dx < to; dx++) {
            for (int dz = 0; dz < dims.width(); dz++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    BlockState state = SHELL;
                    if (PortalCentreWall.isDoorwayColumn(dims, kind, dx, dy, dz)) {
                        state = severed ? Blocks.AIR.defaultBlockState() : CENTRE_WALL_DOORWAY;
                    }

                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (relight) {
                        level.setBlock(pos, state, Block.UPDATE_ALL);
                    } else {
                        SilentBlockOps.setBlockSectionLocal(level, pos, state);
                    }
                    // Air included: the cell is part of this cart's footprint either way, and a
                    // position left out of the set is one ShipAssembler does not lift into the
                    // group's sub-level. See CarriagePlacer.finishPlace.
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
    public static void stampBuiltInForCapture(ServerLevel level, BlockPos origin, CarriageDims dims,
                                              PortalCorridorKind kind) {
        clearBox(level, origin, dims, kind);
        stampBuiltIn(level, origin, dims, kind, /*relight*/ true);
    }

    /** Clear a corridor-sized box to air — {@link PortalCorridorSize#corridorLength} along X. */
    public static void clearBox(ServerLevel level, BlockPos origin, CarriageDims dims,
                                PortalCorridorKind kind) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < PortalCorridorSize.corridorLength(dims, kind); dx++) {
            for (int dz = 0; dz < dims.width(); dz++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    level.setBlock(pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz),
                        Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    /** The built-in corridor geometry, used when no {@code portal} template has been authored yet. */
    private static Set<BlockPos> stampBuiltIn(ServerLevel level, BlockPos origin, CarriageDims dims,
                                              PortalCorridorKind kind, boolean relight) {
        PortalCarriageLayout layout = layoutFor(dims, kind);
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
    public static void stampTwin(ServerLevel level, BlockPos origin, CarriageDims dims,
                                 PortalCorridorKind kind, int pairKey) {
        // Clear first: unlike a carriage, a twin lands in open air rather than a pre-cleared volume,
        // and a template stamp only writes its own cells — anything already standing there would
        // show through and break the match with the carriage.
        clearBox(level, origin, dims, kind);
        stampCorridorFrom(level, origin, dims, kind, /*relight*/ true, /*withContents*/ true, pairKey);
    }

    /**
     * Decide what a pair's structure is before building it: which room variant it rolls, how big
     * that room turns out to be, and what it does at its walls.
     *
     * <p>The name is a pure function of the world seed, the pair's key and {@code gateCtx}, so a pair
     * keeps the same room for as long as it stands where it was planned. It is <b>not</b> pure in the
     * seed and key alone any more: {@code gateCtx} carries the Diff-Level and dimension of the entry
     * carriage, which move with the train. Callers that re-stamp an existing pair further down the
     * track must therefore <b>relocate</b> it ({@link PortalStructure#movedTo}) rather than call this
     * again — re-planning at the new position could roll a different room out from under a player.
     * A {@code null} {@code gateCtx} skips gating entirely (editor previews / tests). The size is read
     * off the authored template, or the built-in room's when nothing has been authored.</p>
     *
     * <p>The {@link PortalRoomSettings settings} are read here and then carried on the record rather
     * than looked up per tick, so an author saving a different mode while somebody is standing in the
     * room cannot change the walls around them mid-visit. The pair's
     * {@link PortalCorridorKind corridor kind} is drawn here for exactly the same reason, and it is
     * the stronger case of the two: the kind decides how far apart the twins stand, so a pair that
     * re-drew it mid-visit would move the exit frame out from under a player walking back.</p>
     */
    public static PortalStructure planStructure(ServerLevel level, CarriageDims dims,
                                                BlockPos entryOrigin, int pairKey,
                                                games.brennan.dungeontrain.template.GateContext gateCtx) {
        String roomName = TrackVariantRegistry.pickName(
            TrackKind.PORTAL_ROOM, level.getSeed(), pairKey, gateCtx);
        PortalRoomSettings settings = PortalRoomSettings.of(roomName);
        // Where this pair stands its exit, decided here with everything else about the pair and then
        // carried on the record — the same promise the mode and the room name make. Re-deciding it
        // per tick, or per re-stamp, would move a player's way out from under them.
        PortalRoomTiling.Tile exitTile = PortalExitSites.relocatedExitTile(
            settings.effectiveExits(),
            PortalExitSites.seedFor(level.getSeed(), pairKey, roomName),
            PortalRoomTiling.MAX_RADIUS);
        return new PortalStructure(entryOrigin, roomName,
            PortalRoomTemplateStore.sizeOf(level, roomName, dims),
            settings,
            PortalRoomTiling.base(), PortalExitCopies.NONE, exitTile,
            PortalCarriageSelection.corridorKindFor(level, pairKey));
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
        PortalCarriageLayout layout = layoutFor(dims, structure.kind());
        BlockPos roomOrigin = structure.roomOrigin(dims, layout);
        Vec3i roomSize = structure.roomSize();

        stampRoomAt(level, roomOrigin, dims, structure.roomName(), roomSize, /*relight*/ true,
            PortalCorridorMask.NONE, PortalCorridorMask.NONE,
            structure.variantIndexFor(PortalRoomTiling.Tile.BASE, pairKey), pairKey,
            PortalRoomTiling.Tile.BASE,
            PortalRoomMobs.liveCount(level, footprintOf(level, structure, dims), pairKey),
            structure.settings().contents());

        // Before the corridors, so each mode acts on the room as it actually turned out rather than
        // as it was asked for. It does not follow that the corridors repair whatever a mode wrote at
        // a door plane — they are stamped over their own volume only, and never over the room's end
        // column one block inside it, which is why nothing may write there in the first place; see
        // PortalCorridorMask#facedBy. Bedrock Lock wraps the room here and its two corridors once
        // they are down, which is the other half of the same shell; the endless modes settle its own
        // side walls, which for Endless Open means taking them away so there is somewhere to walk
        // out to. Bedrockless writes nothing around the room at all and sweeps the space instead.
        if (structure.mode() == PortalRoomMode.BEDROCK_LOCK) {
            bedrockSkin(level, roomOrigin, roomSize);
        } else if (structure.mode().clearsSurroundings()) {
            clearVoidAround(level, structure, dims);
        } else if (structure.mode().tiles()) {
            PortalRoomTiler.refreshFacesAround(level, dims, structure, PortalRoomTiling.Tile.BASE);
        }

        stampCorridors(level, structure, dims, pairKey);

        // After the corridors, unlike the room's own skin, and necessarily so: this shell sits one
        // block outside a corridor rather than around the room, and stampCorridors clears and
        // re-stamps that corridor's box on its way past. Writing it first would be writing it into
        // a volume that is about to be swept.
        if (structure.mode().sealsCorridors()) {
            bedrockSkinCorridor(level, structure.origin(), dims, layout, PortalCarriageRole.ENTRY,
                roomOrigin, roomSize);
            bedrockSkinCorridor(level, structure.exitOrigin(dims), dims, layout,
                PortalCarriageRole.EXIT, roomOrigin, roomSize);
        }
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
        // Each half off its own coordinate frame — see PortalCorridorMask#forStructure. The entry
        // always stands beside the base room; the exit stands beside whichever tile this pair put it
        // at, and needs that tile's room for its seal ring.
        stampCorridorHalf(level, structure, structure, dims, pairKey, PortalCarriageRole.ENTRY);
        stampCorridorHalf(level, structure.exitShadow(), structure, dims, pairKey,
            PortalCarriageRole.EXIT);
    }

    /**
     * Lay one end of a pair: the corridor, the seal ring around its mouth, and the plug beyond its
     * dead outer door.
     *
     * <p>Split out of {@link #stampCorridors} because an endless room's <b>extra</b> corridors are
     * single corridors rather than pairs — an {@link PortalExitSites.Site} carries one role — and a
     * copy is nothing but this same call on the structure translated onto its anchor tile
     * ({@link PortalStructure#shadowAt}). Everything a copy needs is therefore written by the code
     * that writes the original, which is what keeps a copy block-identical to the carriage it will
     * hand a player back to. See {@link #stampExitCopy}.</p>
     *
     * <p>Both halves take the <b>pair's</b> key rather than a carriage index, so every corridor in a
     * pair — copies included — rolls the same block variants and the same corridor contents, and the
     * crossing shows no seam whichever one a player walks through.</p>
     *
     * <p>{@code base} is the pair's own structure, of which {@code structure} may be a
     * {@link PortalStructure#shadowAt shadow} — the same object for an entry, a translated one for a
     * moved exit or a copy. It is only ever read from: the seal ring copies the room's own wall out
     * of the base room, which is the one copy of the room guaranteed to be standing when a corridor
     * is laid. See {@link #sealFillSource}.</p>
     */
    public static void stampCorridorHalf(ServerLevel level, PortalStructure structure,
                                         PortalStructure base, CarriageDims dims, int pairKey,
                                         PortalCarriageRole role) {
        PortalCarriageLayout layout = layoutFor(dims, structure.kind());
        BlockPos roomOrigin = structure.roomOrigin(dims, layout);
        Vec3i roomSize = structure.roomSize();
        boolean entry = role == PortalCarriageRole.ENTRY;
        BlockPos corridorOrigin = entry ? structure.origin() : structure.exitOrigin(dims);

        stampTwin(level, corridorOrigin, dims, structure.kind(), pairKey);

        // Seal the ring around the corridor mouth. The room's shell is wider and taller than a
        // corridor, so everything it does not already cover at the door plane has to be walled off,
        // leaving that plane — and the door hanging in it — untouched. The mouth is the far end of an
        // entry corridor and the near end of an exit one.
        int sealX = entry ? corridorOrigin.getX() + layout.length() - 1 : corridorOrigin.getX();
        sealCorridorMouth(level, sealX, corridorOrigin, dims, roomOrigin, roomSize,
            base.roomOrigin(dims, layout), role);

        // Dead space behind the door that leads nowhere, at the other end. Unbreakable under Bedrock
        // Lock: the room's own skin stops at its ±X ends, so the plugs are what closes off the two
        // corridors standing off them. Read off `base` rather than `structure` — the mode is the
        // same on a shadow, but the pair's own structure is the thing that has one.
        BlockPos plugFrom = entry
            ? corridorOrigin.offset(-PLUG_DEPTH, 0, 0)
            : corridorOrigin.offset(layout.length(), 0, 0);
        plugBeyond(level, plugFrom, PLUG_DEPTH, dims, base.mode().sealsCorridors() ? LOCK : PLUG);
    }

    /**
     * Lay one of an endless room's extra corridors — the way back to the train that
     * {@link PortalRoomExits} scatters through the tiling.
     *
     * <p>Nothing here is new geometry. The site's anchor tile names a copy of the room, the shadow
     * structure puts the pair's own layout onto that copy, and one half of it is stamped: an
     * {@link PortalCarriageRole#ENTRY} site lays the corridor on the low-X side of the tile, an
     * {@link PortalCarriageRole#EXIT} site the one on the high-X side, each with the seal ring and
     * plug the original has. That is what makes a copy indistinguishable from the original both to
     * look at and to walk through — and it is why {@link PortalExitTransit} can hand a player back to
     * the train from one with nothing but a change of origin.</p>
     */
    public static void stampExitCopy(ServerLevel level, PortalStructure structure, CarriageDims dims,
                                     int pairKey, PortalExitSites.Site site) {
        PortalStructure shadow = structure.shadowAt(site.tile());
        PortalCarriageLayout layout = layoutFor(dims, structure.kind());

        // Sweep the volume through PortalClear FIRST, block entity by block entity.
        //
        // A copy is laid into a room that is already standing and may be furnished — the shipped
        // `book` and `library_dimension` rooms are shelves and lecterns and chests — and the writes
        // below are ordinary setBlocks. Breaking a container that way DROPS ITS CONTENTS, so laying
        // one copy through a library left a heap of books on the floor. PortalClear evicts the block
        // entity instead of breaking it, which is the same reason the seam carve goes through
        // clearCell rather than setBlock.
        //
        // Each of the mask's three boxes, never their bounding box: that would take the room's floor
        // out of the aisles either side of the corridor, which is exactly the bug PortalCorridorMask
        // was narrowed to fix.
        for (BoundingBox box : PortalCorridorMask
                .forCorridor(shadow, dims, layout, PLUG_DEPTH, site.role()).boxes()) {
            PortalClear.clearBox(level, box, PortalCorridorMask.NONE);
        }

        stampCorridorHalf(level, shadow, structure, dims, pairKey, site.role());
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
     * <p><b>Four faces here; the other two are further out.</b> The room's ±X ends are the two
     * corridors' door planes — the way back to the train, and the one part of a twin that is
     * block-identical to its carriage. Skinning them at the box would wall the player in, and the
     * column just outside is the seal plane, which is the room's own wall carried on
     * ({@link #sealFillFor}) and in view from inside a room with open sides. So this covers the
     * sides, the ceiling and the floor, and the ends are capped one column further out still by
     * {@link #roomEndCapBoxes}, where only the basement can see them. The two shells meet at the
     * corner to enclose the whole structure.
     *
     * <p><b>One column further out in ±X than the room, and that is what makes them meet.</b> The
     * column just outside each room end is the seal plane {@link #sealCorridorMouth} writes: solid
     * from the corridor's own walls out to the room's outer face and up to its ceiling, and made of
     * ordinary room wall. A skin that stopped at the room box would leave it as a ring of mineable
     * rock with the basement on the other side — the one gap between the room's shell and a
     * corridor's. Every cell the extra column adds lies <i>outside</i> that seal fill's
     * cross-section, so nothing authored is overwritten and none of it is in view from inside the
     * room. The corridor's own ring stops one column short of the same plane for the mirror-image
     * reason: bedrock there would frame the doorway.</p>
     */
    private static void bedrockSkin(ServerLevel level, BlockPos roomOrigin, Vec3i size) {
        int x0 = roomOrigin.getX() - 1;
        int x1 = roomOrigin.getX() + size.getX();
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
     * Wrap one corridor and its plug in bedrock, and cap the room end it stands against — the half of
     * {@link PortalRoomMode#BEDROCK_LOCK}'s shell that is not the room box, laid once the corridor is
     * standing.
     *
     * <p>The room's skin covers the room's four long faces; this covers what hangs off the other two.
     * Without the corridor ring a player walks out of a sealed room into a corridor of ordinary stone
     * brick and mines through the wall sideways; without the end cap the room's ±X faces are left
     * showing their seal plane to the basement, which is ordinary rock too.</p>
     *
     * <p><b>Outside the corridor, and so outside the carriage it mirrors.</b> The corridor's own
     * geometry is shared with the carriage on the train and cannot move by a block; every cell
     * written here is one block beyond it, so the two copies stay identical and a player inside sees
     * exactly what they saw before.</p>
     */
    private static void bedrockSkinCorridor(ServerLevel level, BlockPos corridorOrigin,
                                            CarriageDims dims, PortalCarriageLayout layout,
                                            PortalCarriageRole role, BlockPos roomOrigin,
                                            Vec3i roomSize) {
        int worldMinY = level.getMinBuildHeight();
        fill(level, corridorLockBoxes(corridorOrigin, dims, layout.length(), role, worldMinY));
        fill(level, roomEndCapBoxes(corridorOrigin, dims, role, roomOrigin, roomSize, worldMinY));
    }

    /** Write {@link #LOCK} into every cell of every box. */
    private static void fill(ServerLevel level, List<BoundingBox> boxes) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (BoundingBox box : boxes) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        level.setBlock(pos.set(x, y, z), LOCK, Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    /**
     * The room's own ±X face, skinned: a bedrock plane across the room's cross-section with the
     * corridor passing through it, standing one column beyond the seal plane.
     *
     * <p><b>Why this is not the seal plane itself.</b> {@link #sealCorridorMouth} fills the column
     * immediately outside the room in the <i>room's own blocks</i>, precisely so the boundary stops
     * being visible as one — for a room with open sides it is the end wall the player sees. Bedrock
     * written there would put a bedrock wall inside somebody's authored room. So the cap goes one
     * column further out, where nothing can see it but the basement, and the seal plane keeps its
     * authored look with unbreakable rock immediately behind it.</p>
     *
     * <p><b>A frame, not a plane.</b> The corridor runs through this column, and its blocks are the
     * ones shared with the carriage — so the cap is the cross-section <i>minus</i> the corridor box,
     * as up to four disjoint slabs. Carving it geometrically rather than testing each cell is the
     * same choice {@link #voidSlabs} makes, and for the same reason: the corridor cannot be reached
     * however its layout changes.</p>
     *
     * <p>Sized to the room grown by one block on each axis, so its edges land exactly on
     * {@link #bedrockSkin}'s side walls and its floor and ceiling planes — the two shells meet at the
     * corner rather than leaving a slot along it. The column it stands in is the last one
     * {@link #corridorLockBoxes} rings, so the corridor's own shell meets it too.</p>
     */
    static List<BoundingBox> roomEndCapBoxes(BlockPos corridorOrigin, CarriageDims dims,
                                             PortalCarriageRole role, BlockPos roomOrigin,
                                             Vec3i roomSize, int worldMinY) {
        boolean entry = role == PortalCarriageRole.ENTRY;
        // One column beyond the seal plane, which itself is one column beyond the room box.
        int planeX = entry ? roomOrigin.getX() - 2 : roomOrigin.getX() + roomSize.getX() + 1;

        int zLo = roomOrigin.getZ() - 1;
        int zHi = roomOrigin.getZ() + roomSize.getZ();
        int floorY = corridorOrigin.getY();
        int belowY = lowestWritableY(worldMinY, floorY);
        int aboveY = roomOrigin.getY() + roomSize.getY();

        // The hole: the corridor's own box in this column.
        int holeZLo = corridorOrigin.getZ();
        int holeZHi = corridorOrigin.getZ() + dims.width() - 1;
        int holeYHi = floorY + dims.height() - 1;

        List<BoundingBox> boxes = new ArrayList<>(4);
        // Under and over the corridor, full width, so the corners belong to them.
        if (belowY < floorY) boxes.add(new BoundingBox(planeX, belowY, zLo, planeX, floorY - 1, zHi));
        boxes.add(new BoundingBox(planeX, holeYHi + 1, zLo, planeX, aboveY, zHi));
        // Either side of it, only as tall as the corridor.
        boxes.add(new BoundingBox(planeX, floorY, zLo, planeX, holeYHi, holeZLo - 1));
        boxes.add(new BoundingBox(planeX, floorY, holeZHi + 1, planeX, holeYHi, zHi));
        return boxes;
    }

    /**
     * The slabs {@link #bedrockSkinCorridor} fills: the two side walls, the roof and — where there
     * is a row to write — the floor, each one block outside the corridor box, run the length of the
     * corridor and its plug.
     *
     * <p><b>Never the seal plane.</b> The X span stops one column short of the corridor's mouth,
     * which is the column {@link #sealCorridorMouth} fills across the room's cross-section in the
     * room's own blocks. Bedrock there would show inside the room. The column the span does end on is
     * the one {@link #roomEndCapBoxes} caps the room's face in, so the ring meets the cap and the
     * seal plane keeps unbreakable rock immediately behind it either way.</p>
     *
     * <p><b>Nothing beyond the plug, either.</b> The dead end is already three blocks of {@link #LOCK}
     * under this mode ({@link PortalRoomMode#sealsCorridors}), so a cap there would be bedrock
     * against bedrock — and it would fall outside {@link #footprintOf}, which reaches exactly one
     * plug depth past the corridor. Everything here is inside that box, so the erase sweep reaches
     * every block written.</p>
     *
     * <p>Pure integer geometry with no level, so the covering can be unit-tested: no slab touches the
     * corridor's own box or the seal plane, and together they close all four lateral sides.</p>
     */
    static List<BoundingBox> corridorLockBoxes(BlockPos corridorOrigin, CarriageDims dims,
                                               int length, PortalCarriageRole role, int worldMinY) {
        int oz = corridorOrigin.getZ();
        int floorY = corridorOrigin.getY();
        int belowY = lowestWritableY(worldMinY, floorY);
        int aboveY = floorY + dims.height();
        int zLo = oz - 1;
        int zHi = oz + dims.width();

        // The mouth is the far end of an entry corridor and the near end of an exit one — the same
        // rule stampCorridorHalf plugs the other end by. The span runs from the plug's outer face to
        // the column before the mouth.
        boolean entry = role == PortalCarriageRole.ENTRY;
        int xLo = entry ? corridorOrigin.getX() - PLUG_DEPTH : corridorOrigin.getX() + 1;
        int xHi = entry
            ? corridorOrigin.getX() + length - 2
            : corridorOrigin.getX() + length - 1 + PLUG_DEPTH;

        List<BoundingBox> boxes = new ArrayList<>(4);
        // Sides, the full height of the shell so their corners meet the roof and floor planes.
        boxes.add(new BoundingBox(xLo, belowY, zLo, xHi, aboveY, zLo));
        boxes.add(new BoundingBox(xLo, belowY, zHi, xHi, aboveY, zHi));
        // Roof, and the floor only where there is genuinely a row below to write — in the lowest
        // lane there is not, and the world's own bedrock is already doing the job.
        boxes.add(new BoundingBox(xLo, aboveY, zLo, xHi, aboveY, zHi));
        if (belowY < floorY) boxes.add(new BoundingBox(xLo, belowY, zLo, xHi, belowY, zHi));
        return boxes;
    }

    /**
     * The box a {@link PortalRoomMode#BEDROCKLESS} room's emptiness fills: the room grown by
     * {@link PortalRoomLayout#VOID_CLEARANCE} on both horizontal axes, never smaller than the
     * structure standing in it.
     *
     * <p><b>The fog is derived from the same clearance, not from this box.</b>
     * {@code PortalCarriageEvents} pads the room's own bounds by {@link PortalStructure#fogPad},
     * which is {@link PortalRoomLayout#VOID_CLEARANCE} — the one figure both sides read, so the space
     * swept and the space fogged cannot drift apart. Where they differ at all it is because this box
     * is additionally held open to the structure's footprint, which only makes the swept space the
     * larger of the two: the fog never claims ground that was not cleared.</p>
     *
     * <p>The union with the footprint is not defensive tidiness. A world with long carriages has
     * corridors and plugs reaching further from the room than the clearance does, and a halo that
     * stopped short of them would leave the structure poking out of its own void — and, because the
     * fog reads this box too, would un-fog somebody standing in a corridor.</p>
     *
     * <p>See {@link PortalRoomLayout#VOID_CLEARANCE} for why the clearance has no vertical term at
     * all. What it does have is a <b>floor</b>: the sweep starts at the structure's own floor row and
     * leaves everything below it, one row shallower than {@link #footprintOf}. Two reasons, and the
     * second is the one that matters. It gives the emptiness something to stand on, so walking out of
     * a Bedrockless room is a one-block step down rather than a fall. And in a Compatible Terrain
     * world — no basement, {@link PortalTwinLanes#FLOOR_MARGIN} putting the lowest lane two rows off
     * the build floor — the row {@code footprintOf} reaches is inside the world's <i>own</i> bedrock
     * layer, and sweeping a hundred-block disc of it would open the bottom of the world.</p>
     */
    static BoundingBox voidHaloOf(ServerLevel level, PortalStructure structure,
                                  CarriageDims dims) {
        PortalCarriageLayout layout = layoutFor(dims, structure.kind());
        BlockPos roomOrigin = structure.roomOrigin(dims, layout);
        Vec3i roomSize = structure.roomSize();
        BoundingBox footprint = footprintOf(level, structure, dims);
        int c = PortalRoomLayout.VOID_CLEARANCE;

        return new BoundingBox(
            Math.min(footprint.minX(), roomOrigin.getX() - c),
            structure.origin().getY(),
            Math.min(footprint.minZ(), roomOrigin.getZ() - c),
            Math.max(footprint.maxX(), roomOrigin.getX() + roomSize.getX() - 1 + c),
            footprint.maxY(),
            Math.max(footprint.maxZ(), roomOrigin.getZ() + roomSize.getZ() - 1 + c));
    }

    /**
     * {@code halo} minus {@code footprint}, as up to four disjoint slabs — everything a Bedrockless
     * room clears, and nothing the structure owns.
     *
     * <p><b>Slabs rather than one box and a mask.</b> The corridors, their doors, the seal rings and
     * the plugs all have to survive, and a mask is a predicate that has to be right; carving the
     * structure's own box out of the halo geometrically means the clear cannot reach them however the
     * corridor layout changes. It is also the cheaper shape: the volume the structure occupies is
     * never walked at all.</p>
     *
     * <p>The carve is horizontal, and every slab takes the <b>halo's</b> Y band. The halo is the
     * shallower of the two boxes — it spares the row under the floor that {@link #footprintOf}
     * claims — so a slab can never reach below what was asked for, and no slab intersects the
     * footprint at any height because none of them overlaps it in X or Z to begin with.</p>
     *
     * <p>Pure integer geometry with no level, so the covering can be unit-tested: the slabs are
     * pairwise disjoint, none intersects the footprint, and together they cover every cell of the halo
     * that the footprint does not.</p>
     */
    static List<BoundingBox> voidSlabs(BoundingBox halo, BoundingBox footprint) {
        List<BoundingBox> slabs = new ArrayList<>(4);
        int y0 = halo.minY();
        int y1 = halo.maxY();

        // The two X ends run the halo's full Z, so the corners belong to them and not to the Z sides.
        if (footprint.minX() > halo.minX()) {
            slabs.add(new BoundingBox(halo.minX(), y0, halo.minZ(),
                footprint.minX() - 1, y1, halo.maxZ()));
        }
        if (footprint.maxX() < halo.maxX()) {
            slabs.add(new BoundingBox(footprint.maxX() + 1, y0, halo.minZ(),
                halo.maxX(), y1, halo.maxZ()));
        }
        // The Z sides are therefore only as wide as the footprint.
        if (footprint.minZ() > halo.minZ()) {
            slabs.add(new BoundingBox(footprint.minX(), y0, halo.minZ(),
                footprint.maxX(), y1, footprint.minZ() - 1));
        }
        if (footprint.maxZ() < halo.maxZ()) {
            slabs.add(new BoundingBox(footprint.minX(), y0, footprint.maxZ() + 1,
                footprint.maxX(), y1, halo.maxZ()));
        }
        return slabs;
    }

    /**
     * Empty the space around a {@link PortalRoomMode#BEDROCKLESS} room — its answer to
     * {@link #bedrockSkin}.
     *
     * <p><b>Usually free.</b> Twins stand in the basement under the world's bedrock, which generation
     * never reaches, so in an ordinary Dungeon Train world every section this touches is already air
     * and {@link PortalClear#clearBox} skips it wholesale on {@code hasOnlyAir}. What it costs is a
     * few hundred section probes. The world that pays for real is Compatible Terrain, where there is
     * no basement and the twin is cut into rock — which is exactly the world the mode would otherwise
     * be a lie in.</p>
     *
     * <p><b>The clearance is not part of {@link #footprintOf}, on purpose.</b> Claiming it there would
     * make {@code eraseTwin} sweep a hundred-block box every time the train drifts far enough to
     * re-stamp, and make every pair collide with every other pair for tiling purposes. What is left
     * behind when a structure moves is air, in a basement nothing can reach — so there is nothing to
     * clean up. The one consequence worth knowing is that another pair's room may later be stamped
     * inside a void this one swept; that is no worse than the two structures being neighbours
     * anywhere else, which the Y lanes already make rare.</p>
     */
    private static void clearVoidAround(ServerLevel level, PortalStructure structure,
                                        CarriageDims dims) {
        BoundingBox halo = voidHaloOf(level, structure, dims);
        BoundingBox footprint = footprintOf(level, structure, dims);
        for (BoundingBox slab : voidSlabs(halo, footprint)) {
            PortalClear.clearBox(level, slab, PortalCorridorMask.NONE);
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
        PortalCarriageLayout layout = layoutFor(dims, structure.kind());
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

        // Every corridor this structure owns, wherever it stands. An extra corridor outlives the tile
        // it is anchored to (PortalExitCopies), and the pair's own exit can be beside another tile
        // entirely (PortalStructure#exitTile) — so neither is bounded by the tiled rectangle or by
        // the contiguous span the terms above assume. Something outside the footprint is something
        // the erase never reaches and another pair may stamp into. Read off the same masks that place
        // them, so the two cannot disagree about where they are.
        BoundingBox corridors = allCorridorMask(structure, dims).bounds();
        if (corridors != null) {
            minX = Math.min(minX, corridors.minX() - PLUG_DEPTH);
            maxX = Math.max(maxX, corridors.maxX() + PLUG_DEPTH);
            minZ = Math.min(minZ, corridors.minZ() - 1);
            maxZ = Math.max(maxZ, corridors.maxZ() + 1);
        }

        // One row below the floor as well as one past the top: Bedrock Lock skins both.
        int minY = lowestWritableY(level.getMinBuildHeight(), origin.getY());
        int maxY = origin.getY() + Math.max(dims.height(), roomSize.getY());

        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Solid rock filling {@code depth} blocks from {@code from} along +X, across the corridor's
     * cross-section — the dead space behind a twin's door that leads nowhere, so nothing is reachable
     * or visible through it if it is ever forced open.
     *
     * <p>{@code fill} is the caller's, because "not reachable" is a stronger claim for some rooms
     * than others: a {@link PortalRoomMode#BEDROCK_LOCK} room plugs with {@link #LOCK}, which is the
     * end cap of the shell {@link #bedrockSkinCorridor} runs down the corridor's sides, every other
     * mode with ordinary {@link #PLUG} rock.</p>
     */
    private static void plugBeyond(ServerLevel level, BlockPos from, int depth, CarriageDims dims,
                                   BlockState fill) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < depth; dx++) {
            for (int dz = -1; dz <= dims.width(); dz++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    pos.set(from.getX() + dx, from.getY() + dy, from.getZ() + dz);
                    level.setBlock(pos, fill, Block.UPDATE_ALL);
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
     * <p>{@code variantIndex} is what makes one pair's room differ from another pair's, and what makes
     * one copy of a room differ from another under {@link PortalRoomCopies#DYNAMIC} and identical
     * under {@link PortalRoomCopies#EXACT} — see {@code PortalStructure.variantIndexFor}.</p>
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
                                   int liveMobCount, PortalRoomContents contents) {
        stampRoomAt(level, roomOrigin, dims, roomName, size, relight, clearMask, writeMask);
        // Contents first, the room's own authored cells second. Where the two overlap the author's
        // explicit entry is the one that should stand — and applyRoomVariants evicts a live block
        // entity before it writes, so a chest this pass just filled cannot spill when it does.
        applyRoomContents(level, roomOrigin, size, roomName, writeMask, variantIndex, pairKey, contents);
        applyRoomVariants(level, roomOrigin, roomName, size, writeMask, variantIndex, pairKey, tile,
            liveMobCount);
    }

    /**
     * Furnish a room from the ordinary contents pool, when its author asked for it.
     *
     * <p><b>Off unless asked.</b> {@link PortalRoomContents#OFF} is the default and returns before
     * rolling anything, so a room authored before this existed stamps exactly as it did.</p>
     *
     * <h2>What the roll is a function of</h2>
     * <p>The world seed and {@code variantIndex} go into the <b>seed</b>; {@code pairKey} is passed
     * as the carriage index. That split is deliberate and does two jobs at once:</p>
     * <ul>
     *   <li>{@code pairKey} is a real carriage index — the entry corridor's — so
     *       {@code DifficultyProgression.positionTier} inside the contents pass reads the portal's
     *       actual position on the train, and the furnishing is themed to the stage the player is
     *       in rather than to a hash.</li>
     *   <li>{@code variantIndex} carries the pair and copy identity
     *       ({@code PortalStructure.variantIndexFor}), so one portal's furnishing differs from the
     *       next's, Exact copies within a room share it and Dynamic copies each get their own — for
     *       free, and always agreeing with the block variants rolled from the same number.</li>
     * </ul>
     *
     * <p><b>Pure, not memoised</b>, exactly like the room's variant pass: a copy that retires and is
     * re-stamped as the tiling window slides — or a whole structure re-stamped after the train drifts
     * — reproduces the room the player left, rather than refilling its chests. That property is the
     * reason the roll may not depend on anything but position.</p>
     *
     * <p><b>Ungated</b> ({@code gateCtx} null), for the same reason {@link PortalCorridorContents} is:
     * the stamp runs from the portal tick handler and there is no spawn context to test a template's
     * gate against. Contents with a min/max Diff-Level or phase gate are drawn here as if ungated.</p>
     */
    private static void applyRoomContents(ServerLevel level, BlockPos roomOrigin, Vec3i size,
                                          String roomName, PortalCorridorMask writeMask,
                                          int variantIndex, int pairKey,
                                          PortalRoomContents contents) {
        PortalRoomContents setting = contents == null ? PortalRoomContents.DEFAULT : contents;
        if (!setting.furnishes()) return;

        Vec3i interior = new Vec3i(size.getX() - 2, size.getY() - 2, size.getZ() - 2);
        if (interior.getX() <= 0 || interior.getY() <= 0 || interior.getZ() <= 0) return;
        BlockPos interiorOrigin = roomOrigin.offset(1, 1, 1);

        // What this room is allowed to draw. Absent sidecar = everything, which is what a furnished
        // room did before authors could steer it.
        CarriageContentsAllowList allow = PortalRoomContentsAllowStore.getOrEmpty(roomName);
        // An all-excluded list means an empty room, not the built-in default. CarriageContentsRegistry
        // .pick would fall back to DEFAULT here — right for a carriage, which must never spawn
        // hollow; wrong for a room whose author turned every template off on purpose.
        if (!CarriageContentsRegistry.anyAllowed(allow)) {
            LOGGER.info("[DungeonTrain] Portal room '{}' excludes every contents template — "
                + "left unfurnished.", roomName);
            return;
        }

        long worldSeed = level.getSeed();
        long rollSeed = worldSeed ^ (variantIndex * CONTENTS_GOLDEN_GAMMA);
        CarriageContents picked = CarriageContentsRegistry.pick(
            rollSeed, pairKey, allow, /*gateCtx*/ null);

        Optional<StructureTemplate> template =
            games.brennan.dungeontrain.editor.CarriageContentsStore.getFitting(level, picked, interior);
        if (template.isEmpty()) return;

        Vec3i box = template.get().getSize();
        List<BlockPos> anchors = setting.anchorsIn(interior, box);
        if (anchors.isEmpty()) {
            LOGGER.info("[DungeonTrain] Portal room contents '{}' ({}x{}x{}) do not qualify for a "
                + "{}x{}x{} interior under {} — room left unfurnished.",
                picked.id(), box.getX(), box.getY(), box.getZ(),
                interior.getX(), interior.getY(), interior.getZ(), setting.id());
            return;
        }

        for (BlockPos anchor : anchors) {
            CarriageContentsPlacer.placeBlocksAt(level, interiorOrigin.offset(anchor), picked, box,
                rollSeed, pairKey, writeMask);
        }
    }

    /**
     * The mix {@link #applyRoomContents} folds the copy identity into its seed with — the same
     * constant {@link PortalCorridorContents} uses, so neighbouring rooms separate rather than
     * walking in step.
     */
    private static final long CONTENTS_GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

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
                //
                // No-cascade: this cell really does become air, and an UPDATE_ALL air write breaks
                // whatever was standing on it with drops — see the eviction note further down. An
                // authored block left unsupported here simply stays put, which is the right trade for
                // a room the author built.
                SilentBlockOps.setBlockSilentNoCascade(level, world, Blocks.AIR.defaultBlockState(), null);
                if (PortalRoomMobs.spawn(level, world, picked, pairKey, tile, worldSeed, live)) {
                    live++;
                }
                continue;
            }
            if (CarriageVariantBlocks.isEmptyPlaceholder(picked.state())) {
                // No-cascade, for the same reason as the mob branch above.
                SilentBlockOps.setBlockSilentNoCascade(level, world, Blocks.AIR.defaultBlockState(), null);
                continue;
            }
            // The contents pass may have put a filled chest in this cell a moment ago. Writing over a
            // live block entity runs its onRemove and sprays the loot across the floor — the same
            // hazard PortalClear and PortalRoomTiler.stampTile were both written for. Evict first.
            //
            // The eviction, and NOT a clear to air. This used to call PortalClear.clearCell, which
            // writes AIR with UPDATE_ALL; markAndNotifyBlock strips UPDATE_SUPPRESS_DROPS out of the
            // cascade subflags, so every block standing on a cell — and this walks the whole floor —
            // went through Block.updateOrDestroy -> destroyBlock(dropBlock = true) and BROKE, dropping
            // as an item. singlepillar's eight pressure plates did exactly that on every stamp: knocked
            // off by their own floor cells, then put back by their own entries further down the sidecar,
            // so the room ended up holding the plates AND eight plate items. Never letting the cell
            // become air is the fix; the write below replaces it in one step.
            //
            // evictBlockEntity rather than leaving it to setBlockSilent's removeBlockEntity: this one
            // promotes a PENDING block entity to live before dropping it, which is the whole point of
            // the eviction here — a freshly stamped chest's NBT has not been promoted yet.
            SilentBlockOps.evictBlockEntity(level.getChunkAt(world), world);
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
     * Stamp a box from a template the caller already holds, rather than from the one on disk.
     *
     * <p>What the editor's resize uses. {@link #stampRoomAt} re-reads the <b>saved</b> room, which is
     * right when a copy is being laid on the corridor row but wrong on a stepper click: everything
     * the author had built since their last save would be replaced by the last thing they saved. The
     * caller captures the plot as it currently stands and hands it here instead, so a resize carries
     * the live room across and nothing on disk is consulted or written.</p>
     *
     * <p>{@code shift} is where the old box's contents land in the new one — non-zero when the resize
     * moved the {@code MIN} face, which grows the room outwards rather than always off the far end.
     * The built-in shell goes down first so whatever the box grew into has walls, and the live room
     * is laid over it clipped to the box, exactly as the saved-template resize path does.</p>
     */
    public static void stampRoomFromLive(ServerLevel level, BlockPos roomOrigin, Vec3i size,
                                         StructureTemplate live, Vec3i shift, boolean relight) {
        clearRoomBox(level, roomOrigin, size, PortalCorridorMask.NONE, relight);
        clearIntruders(level, roomOrigin, size);
        plugFluidsAround(level, roomOrigin, size);

        stampRoomBuiltIn(level, roomOrigin, size, relight, PortalCorridorMask.NONE);
        CarriagePlacer.stampTemplateAt(level, roomOrigin.offset(shift), live,
            clipTo(roomOrigin, size, PortalCorridorMask.NONE), relight);
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
     * Wall off everything in a corridor's door plane that the room's own shell does not cover,
     * <b>in the room's own blocks</b>.
     *
     * <p>Only cells <b>outside</b> the corridor's cross-section are written — the corridor's own
     * blocks, doorway included, are never touched. That is what keeps a twin block-identical to its
     * carriage regardless of what the room is authored as.</p>
     *
     * <p>The fill used to be {@link #POCKET_SHELL}, which is the built-in room's palette and belongs
     * to nothing else in an authored one. A corridor's mouth then read as a slab of polished
     * blackstone dropped into somebody else's library — roughly fifty blocks of it per corridor, and
     * an endless room on {@link PortalRoomExits.Kind#RANDOM} scatters a corridor every few tiles.
     * {@link PortalRoomTiler#closeFace} had exactly this problem at the tiling boundary and solved it
     * the same way: what closes a face should be what the player would have seen had the room simply
     * carried on. See {@link #sealFillFor} for the three tiers.</p>
     *
     * <p><b>The plane still fills the room's whole cross-section and is still solid.</b> It is the
     * only thing between the room and the basement rock when the next copy is never stamped — the
     * budget is spent, or the chunks are not loaded — which {@link PortalCorridorMask}'s javadoc
     * records at length. Only the material changed.</p>
     */
    private static void sealCorridorMouth(ServerLevel level, int planeX, BlockPos corridorOrigin,
                                          CarriageDims dims, BlockPos roomOrigin, Vec3i roomSize,
                                          BlockPos baseRoomOrigin, PortalCarriageRole role) {
        int floorY = roomOrigin.getY();
        int ceilingY = floorY + roomSize.getY() - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = roomOrigin.getZ(); z < roomOrigin.getZ() + roomSize.getZ(); z++) {
            for (int y = floorY; y <= ceilingY; y++) {
                boolean coveredByCorridor = z >= corridorOrigin.getZ()
                    && z < corridorOrigin.getZ() + dims.width()
                    && y < floorY + dims.height();
                if (coveredByCorridor) continue;
                BlockState fill = sealFillFor(level, baseRoomOrigin, roomOrigin, roomSize, role,
                    y, z, floorY);
                level.setBlock(pos.set(planeX, y, z), fill, Block.UPDATE_ALL);
            }
        }
    }

    /**
     * What one cell of a seal plane is made of, in three tiers.
     *
     * <ol>
     *   <li><b>The room's own wall beside it</b> — the end column the plane stands one block outside
     *       of. Where a room has a wall, the wall carries on and the boundary stops being visible as
     *       one.</li>
     *   <li><b>The room's floor in that column</b>, when it has no wall there. {@code distantenemies}
     *       is a sculk floor, a sculk ceiling and pillars, with open sides on purpose — so it gets a
     *       plane of its own sculk rather than of a palette it has nothing to do with. This does
     *       invent a wall the author never drew, which is unavoidable: unlike
     *       {@link PortalRoomTiler#closeFace}, this plane cannot be left open.</li>
     *   <li><b>The built-in palette</b>, exactly as before, if neither is a block that can stand in a
     *       wall. Never reached by any shipped room, and here so that no path can leave air.</li>
     * </ol>
     *
     * <p>Each candidate is gated by {@link PortalRoomTiler#usableAsFill} — the same test the face
     * fill uses, and for the same reasons: air closes nothing, a copied block entity plants empty
     * chests along the boundary, and a copied stair or torch keeps the facing it had and leaves a
     * hole besides.</p>
     */
    private static BlockState sealFillFor(ServerLevel level, BlockPos baseRoomOrigin,
                                          BlockPos roomOrigin, Vec3i roomSize,
                                          PortalCarriageRole role, int y, int z, int floorY) {
        BlockPos wall = sealFillSource(baseRoomOrigin, roomOrigin, roomSize, role, y, z);
        BlockState wallState = level.getBlockState(wall);
        if (PortalRoomTiler.usableAsFill(level, wall, wallState)) return wallState;

        BlockPos floor = wall.atY(baseRoomOrigin.getY());
        BlockState floorState = level.getBlockState(floor);
        if (PortalRoomTiler.usableAsFill(level, floor, floorState)) return floorState;

        return y == floorY ? POCKET_FLOOR : POCKET_SHELL;
    }

    /**
     * Which cell of the <b>base</b> room a seal cell copies — the whole of the rule, as integer
     * geometry, so it is testable without a live level.
     *
     * <p><b>Which column.</b> A seal plane sits one block outside the room box: an
     * {@link PortalCarriageRole#ENTRY} mouth at {@code roomOrigin.x - 1}, an
     * {@link PortalCarriageRole#EXIT} mouth at {@code roomOrigin.x + roomSize.x}. So the wall it
     * should carry on is the room's own end column on that side — local X {@code 0} for an entry,
     * {@code roomSize.x - 1} for an exit. Same {@code y} and {@code z}, so a room whose walls change
     * with height or across its width is copied faithfully rather than averaged.</p>
     *
     * <p><b>Why the base room and not this one.</b> {@code roomOrigin} may belong to a
     * {@link PortalStructure#shadowAt shadow} — a copy's anchor tile, or the tile a pair stood its
     * exit beside — and a shadow's room is not always standing when its corridor is laid.
     * {@code stampPairStructure} stamps the base room and <i>then</i> the corridors, so a pair whose
     * exit has moved seals against a tile the tiling has not reached yet; reading beside it would
     * copy raw basement rock. Every tile is the same room, which is the argument
     * {@link PortalRoomTiler#closeFace} already makes, so the base room answers for all of them and
     * is the one copy guaranteed to be there. The shadow differs from it by a pure X/Z translation,
     * which is all this subtracts.</p>
     */
    static BlockPos sealFillSource(BlockPos baseRoomOrigin, BlockPos roomOrigin, Vec3i roomSize,
                                   PortalCarriageRole role, int y, int z) {
        int localX = role == PortalCarriageRole.ENTRY ? 0 : roomSize.getX() - 1;
        return new BlockPos(
            baseRoomOrigin.getX() + localX,
            y - (roomOrigin.getY() - baseRoomOrigin.getY()),
            z - (roomOrigin.getZ() - baseRoomOrigin.getZ()));
    }
}
