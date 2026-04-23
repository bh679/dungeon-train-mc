package games.brennan.dungeontrain.tunnel;

import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.track.TrackPalette;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Procedural fallback painting for tunnel sections, portals, and approach
 * trenches. Used when no NBT template is saved for {@code tunnel_section} /
 * {@code tunnel_portal}, and by {@link games.brennan.dungeontrain.editor.TunnelEditor}
 * to render the default geometry inside the editor plots.
 *
 * <p>Extracted from the original column-by-column paint inside
 * {@link TunnelGenerator}. All geometry constants (half-widths, arch tiers,
 * lamp spacing, lamp height) kept identical so the visible output matches the
 * pre-refactor tunnel exactly when no templates have been saved.</p>
 */
public final class LegacyTunnelPaint {

    /** Half-widths of each pyramid tier. Widths are {11, 9, 7, 5}. */
    private static final int[] PYRAMID_HALF_WIDTHS = { 5, 4, 3, 2 };

    /** Number of tiers in the arched interior roof (rising above {@code ceilingY}). */
    static final int ARCH_TIERS = 3;

    /** Distance between lamp stations along X. Every Nth world X gets a pair of wall lamps. */
    static final int LAMP_SPACING = 10;

    /** Lamp Y sits this many blocks above the rail — walking eye height inside the tunnel. */
    private static final int LAMP_Y_OFFSET_FROM_RAIL = 2;

    private LegacyTunnelPaint() {}

    /**
     * Synthesize a {@link TunnelGeometry} whose {@code floorY} / {@code wallMinZ}
     * match the given editor plot origin. Used so the editor can render the
     * same procedural fill a real tunnel would produce, inside the plot's
     * 10×14×13 footprint.
     */
    public static TunnelGeometry geometryForPlot(BlockPos origin) {
        int floorY = origin.getY();
        int wallMinZ = origin.getZ();
        // Track corridor is 5 wide, centred in the 13-wide wall span.
        int trackZMin = wallMinZ + 4;
        int trackZMax = wallMinZ + 8;
        TrackGeometry tg = new TrackGeometry(floorY, floorY + 1, trackZMin, trackZMax);
        return TunnelGeometry.from(tg);
    }

    /** Paint a full 10-block section at world column {@code originX}. */
    public static void paintSection(ServerLevel level, int originX, TunnelGeometry tg) {
        for (int dx = 0; dx < 10; dx++) {
            paintTunnelColumn(level, originX + dx, tg);
        }
    }

    /**
     * Fill the "don't touch" corner wedges above the arched roof (inside the
     * 10×14×13 template bounding box but outside the arch profile) with
     * {@link Blocks#STRUCTURE_VOID}. Called only from the editor — the
     * saved NBT uses {@code STRUCTURE_VOID} as the {@code fillFromWorld}
     * ignore block, so positions filled here are stripped from the template
     * and the in-world stamp leaves whatever's already at those corners
     * (mountain rock) untouched.
     *
     * <p>Void markers are placed only where the block is currently air, so
     * any block the user puts in a corner is preserved and captured
     * normally.</p>
     */
    public static void fillCornersWithVoid(ServerLevel level, int originX, TunnelGeometry tg) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 10; dx++) {
            int worldX = originX + dx;

            // Arch tiers — at tier k the arch profile occupies z ∈ [wallMinZ+k-1, wallMaxZ-k+1]
            // (stairs at the outer edges, stones + air inside). Anything
            // outside that range at tier-y is a corner wedge.
            for (int tier = 1; tier <= ARCH_TIERS; tier++) {
                int y = tg.ceilingY() + tier;
                int inLo = tg.wallMinZ() + tier - 1;
                int inHi = tg.wallMaxZ() - tier + 1;
                for (int z = tg.wallMinZ(); z < inLo; z++) {
                    setVoidIfAir(level, pos, worldX, y, z);
                }
                for (int z = inHi + 1; z <= tg.wallMaxZ(); z++) {
                    setVoidIfAir(level, pos, worldX, y, z);
                }
            }

            // Apex cap — stones occupy z ∈ [wallMinZ+ARCH_TIERS+1, wallMaxZ-ARCH_TIERS-1].
            int apexY = tg.ceilingY() + ARCH_TIERS + 1;
            int apexInLo = tg.wallMinZ() + ARCH_TIERS + 1;
            int apexInHi = tg.wallMaxZ() - ARCH_TIERS - 1;
            for (int z = tg.wallMinZ(); z < apexInLo; z++) {
                setVoidIfAir(level, pos, worldX, apexY, z);
            }
            for (int z = apexInHi + 1; z <= tg.wallMaxZ(); z++) {
                setVoidIfAir(level, pos, worldX, apexY, z);
            }
        }
    }

    /**
     * Paint a full 10-block portal section — section fill plus a 4-tier
     * stepped pyramid facade at one end.
     *
     * @param mirrorX when {@code false}, pyramid sits at {@code originX}
     *                (entrance, facing −X); when {@code true}, pyramid sits
     *                at {@code originX + 9} (exit, facing +X).
     */
    public static void paintPortal(ServerLevel level, int originX, TunnelGeometry tg, boolean mirrorX) {
        paintSection(level, originX, tg);
        int pyramidX = mirrorX ? (originX + 9) : originX;
        placePortalPyramid(level, pyramidX, tg);
    }

    /**
     * Paint a single-X slice of tunnel: floor (incl. track bed and rails so
     * the editor plot is self-contained and the saved template carves out
     * the full 10×14×13 volume when stamped underground), airspace, walls
     * (with sea-lantern pair on lamp columns), and stepped arched roof.
     *
     * <p>In-world, track bed + rails already exist at these coordinates
     * from {@link games.brennan.dungeontrain.track.TrackGenerator}. The
     * {@link #setIfNeeded} / {@link #placeRail} idempotence guards skip
     * the duplicate writes so this stays O(0) per already-placed block.</p>
     *
     * Called for {@code PARTIAL} sections in {@link TunnelGenerator} and as
     * the inner loop of {@link #paintSection}.
     */
    static void paintTunnelColumn(ServerLevel level, int worldX, TunnelGeometry tg) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // 1. Full 11-wide stone-brick floor. The middle 5 overlap with the
        //    track bed that TrackGenerator owns — idempotent on in-world
        //    repaints (bed is already stone-brick). In the editor plot
        //    there's no TrackGenerator, so this also gives the player a
        //    continuous floor to stand on.
        for (int z = tg.airMinZ(); z <= tg.airMaxZ(); z++) {
            setIfNeeded(level, pos, worldX, tg.floorY(), z, TunnelPalette.FLOOR);
        }

        // 1b. Rails at (railY, railZMin) and (railY, railZMax). Idempotent
        //     against TrackGenerator's pre-placed rails in-world.
        placeRail(level, pos, worldX, tg.railY(), tg.railZMin());
        placeRail(level, pos, worldX, tg.railY(), tg.railZMax());

        // 2. Airspace: 11 wide × 9 tall between floor and ceiling (now including
        //    y=ceilingY since the flat ceiling is gone — replaced by the arch).
        //    Preserve rails at y=railY, z=railZMin|railZMax (just placed above).
        int airYMin = tg.railY();
        int airYMax = tg.ceilingY();
        for (int y = airYMin; y <= airYMax; y++) {
            for (int z = tg.airMinZ(); z <= tg.airMaxZ(); z++) {
                if (y == tg.railY() && (z == tg.railZMin() || z == tg.railZMax())) continue;
                setAirIfNeeded(level, pos, worldX, y, z);
            }
        }

        // 3. Walls: full-height stone-brick columns just outside the airspace.
        //    Every LAMP_SPACING X blocks a pair of sea lanterns replaces the wall
        //    stone-brick at walking eye height (railY + LAMP_Y_OFFSET_FROM_RAIL).
        int lampY = tg.railY() + LAMP_Y_OFFSET_FROM_RAIL;
        boolean isLampColumn = Math.floorMod(worldX, LAMP_SPACING) == 0;
        for (int y = tg.floorY(); y <= tg.ceilingY(); y++) {
            BlockState sideBlock = (isLampColumn && y == lampY) ? TunnelPalette.SEA_LANTERN : TunnelPalette.WALL;
            setIfNeeded(level, pos, worldX, y, tg.wallMinZ(), sideBlock);
            setIfNeeded(level, pos, worldX, y, tg.wallMaxZ(), sideBlock);
        }

        // 4. Arched interior roof — stepped pyramid profile rising 4 rows above wall tops.
        paintArchedRoof(level, worldX, tg);
    }

    /**
     * Stepped arch rising above the tunnel's wall tops, mirroring the portal
     * pyramid profile. Tier N sits at y = ceilingY + N, with a 1-block inset
     * per tier from the walls, stone-brick stairs smoothing each step, and
     * a flat 5-wide stone-brick cap at the apex.
     */
    static void paintArchedRoof(ServerLevel level, int worldX, TunnelGeometry tg) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int tier = 1; tier <= ARCH_TIERS; tier++) {
            int y = tg.ceilingY() + tier;
            int stoneZLo = tg.wallMinZ() + tier;        // inner edge of this tier's stone row
            int stoneZHi = tg.wallMaxZ() - tier;

            // Stone-brick roof edges — two single blocks spanning the tier width.
            setIfNeeded(level, pos, worldX, y, stoneZLo, TunnelPalette.WALL);
            setIfNeeded(level, pos, worldX, y, stoneZHi, TunnelPalette.WALL);

            // Smoothing stairs — one block outside stoneZLo/stoneZHi, facing inward
            // (toward centerZ) so the high half of the stair sits flush with the
            // stone-brick step on the inside edge.
            placeStair(level, pos, worldX, y, stoneZLo - 1, Direction.SOUTH);
            placeStair(level, pos, worldX, y, stoneZHi + 1, Direction.NORTH);

            // Air fill inside the tier.
            for (int z = stoneZLo + 1; z <= stoneZHi - 1; z++) {
                setAirIfNeeded(level, pos, worldX, y, z);
            }
        }

        // Apex cap: flat stone-brick closing the 5-wide air column at the peak.
        int apexY = tg.ceilingY() + ARCH_TIERS + 1;
        int apexZLo = tg.wallMinZ() + ARCH_TIERS + 1;
        int apexZHi = tg.wallMaxZ() - ARCH_TIERS - 1;
        for (int z = apexZLo; z <= apexZHi; z++) {
            setIfNeeded(level, pos, worldX, apexY, z, TunnelPalette.CEILING);
        }
    }

    /**
     * Open cutting — floor extension plus a 13-wide × 15-tall air column, no
     * walls / no roof. Visually "the train has carved an uncovered trench
     * into the hillside right up to the portal face".
     */
    static void paintApproachColumn(ServerLevel level, int worldX, TunnelGeometry tg) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // 1. Floor extension — 11-wide stone-brick at y=floorY, excluding the
        //    5-wide track bed which TrackGenerator owns.
        for (int z = tg.airMinZ(); z <= tg.airMaxZ(); z++) {
            if (z >= tg.trackZMin() && z <= tg.trackZMax()) continue;
            setIfNeeded(level, pos, worldX, tg.floorY(), z, TunnelPalette.FLOOR);
        }

        // 2. Air cutting — everything in the tunnel's external cross-section
        //    from floorY+1 up to the arch apex, across the 13-wide wall span.
        //    Preserve rails.
        int topY = tg.ceilingY() + ARCH_TIERS + 1;
        for (int y = tg.floorY() + 1; y <= topY; y++) {
            for (int z = tg.wallMinZ(); z <= tg.wallMaxZ(); z++) {
                if (y == tg.railY() && (z == tg.railZMin() || z == tg.railZMax())) continue;
                setAirIfNeeded(level, pos, worldX, y, z);
            }
        }
    }

    /**
     * Stepped portal — 4 tiers of stone-brick stacked above the arched roof,
     * overriding the arch's central air with a solid facade at the boundary
     * between tunnel and non-tunnel territory. Stair-smoothed outer edges
     * match the arched-roof stair convention (facing inward, toward centerZ).
     */
    static void placePortalPyramid(ServerLevel level, int worldX, TunnelGeometry tg) {
        int centerZ = tg.centerZ();
        int baseY = tg.ceilingY() + 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int tier = 0; tier < PYRAMID_HALF_WIDTHS.length; tier++) {
            int y = baseY + tier;
            int hw = PYRAMID_HALF_WIDTHS[tier];
            // Core stone-brick row — solid, overrides the arch's air at this X.
            for (int z = centerZ - hw; z <= centerZ + hw; z++) {
                setIfNeeded(level, pos, worldX, y, z, TunnelPalette.PYRAMID);
            }
            // Stairs at outer edges of this tier, facing inward.
            placeStair(level, pos, worldX, y, centerZ - hw - 1, Direction.SOUTH);
            placeStair(level, pos, worldX, y, centerZ + hw + 1, Direction.NORTH);
        }
    }

    static void setIfNeeded(ServerLevel level, BlockPos.MutableBlockPos pos,
                            int x, int y, int z, BlockState state) {
        pos.set(x, y, z);
        if (!level.hasChunkAt(pos)) return;
        if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) return;
        BlockState existing = level.getBlockState(pos);
        if (existing.is(state.getBlock())) return;
        SilentBlockOps.setBlockSilent(level, pos.immutable(), state);
    }

    static void setAirIfNeeded(ServerLevel level, BlockPos.MutableBlockPos pos,
                               int x, int y, int z) {
        pos.set(x, y, z);
        if (!level.hasChunkAt(pos)) return;
        if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) return;
        BlockState existing = level.getBlockState(pos);
        if (existing.isAir()) return;
        SilentBlockOps.clearBlockSilent(level, pos.immutable());
    }

    static void placeStair(ServerLevel level, BlockPos.MutableBlockPos pos,
                           int x, int y, int z, Direction facing) {
        pos.set(x, y, z);
        if (!level.hasChunkAt(pos)) return;
        if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) return;
        BlockState existing = level.getBlockState(pos);
        if (existing.is(TunnelPalette.STAIRS)) return;
        BlockState state = TunnelPalette.STAIRS.defaultBlockState()
            .setValue(StairBlock.FACING, facing);
        SilentBlockOps.setBlockSilent(level, pos.immutable(), state);
    }

    /**
     * Idempotent rail placement — skips if a rail is already there (any shape),
     * and skips ship-owned voxels. Used so {@link #paintTunnelColumn} can
     * keep rails present inside templates without stomping on the specific
     * rail shape {@link games.brennan.dungeontrain.track.TrackGenerator}
     * already placed.
     */
    static void placeRail(ServerLevel level, BlockPos.MutableBlockPos pos,
                          int x, int y, int z) {
        pos.set(x, y, z);
        if (!level.hasChunkAt(pos)) return;
        if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) return;
        BlockState existing = level.getBlockState(pos);
        if (existing.is(TrackPalette.RAIL.getBlock())) return;
        SilentBlockOps.setBlockSilent(level, pos.immutable(), TrackPalette.RAIL);
    }

    /**
     * Place a {@code STRUCTURE_VOID} marker only if the current block is air.
     * Preserves user edits at corner positions (any non-air block the player
     * placed there stays). Used by {@link #fillCornersWithVoid}.
     */
    private static void setVoidIfAir(ServerLevel level, BlockPos.MutableBlockPos pos,
                                     int x, int y, int z) {
        pos.set(x, y, z);
        if (!level.hasChunkAt(pos)) return;
        if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) return;
        BlockState existing = level.getBlockState(pos);
        if (!existing.isAir()) return;
        SilentBlockOps.setBlockSilent(level, pos.immutable(), Blocks.STRUCTURE_VOID.defaultBlockState());
    }
}
