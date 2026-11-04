package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.TrackPalette;
import games.brennan.dungeontrain.track.TrackPlacer;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.LegacyTunnelPaint;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumMap;
import java.util.Map;

/**
 * The eight track-side plot editors, one per {@link TrackKind} on the Tracks row, and the fallback
 * geometry each stamps for a variant that has no stored template.
 *
 * <p>{@link TrackKind#PORTAL_ROOM} is a Portals-category kind with a variable size and is not here;
 * {@code PortalRoomEditor} keeps it.</p>
 */
public final class TrackSideEditors {

    private static final TrackSidePlotEditor<Template.Track> TILE = new TrackSidePlotEditor<>(
        new TrackSidePlotEditor.Spec<>(TrackKind.TILE, Template.Track::new,
            TrackSideEditors::stampTileFallback, null, null, "made_track", Blocks.AIR,
            /*worldDimsGate*/ true, /*containerLinks*/ true, null));

    private static final Map<PillarSection, TrackSidePlotEditor<Template.Pillar>> PILLARS =
        new EnumMap<>(PillarSection.class);
    private static final Map<PillarAdjunct, TrackSidePlotEditor<Template.Adjunct>> ADJUNCTS =
        new EnumMap<>(PillarAdjunct.class);
    private static final Map<TunnelVariant, TrackSidePlotEditor<Template.Tunnel>> TUNNELS =
        new EnumMap<>(TunnelVariant.class);

    static {
        for (PillarSection section : PillarSection.values()) {
            PILLARS.put(section, new TrackSidePlotEditor<>(new TrackSidePlotEditor.Spec<>(
                PillarTemplateStore.pillarKind(section), name -> new Template.Pillar(section, name),
                (level, origin, dims) -> stampPillarFallback(level, origin, section, dims),
                null, null, "made_pillar", Blocks.AIR, true, false, null)));
        }
        for (PillarAdjunct adjunct : PillarAdjunct.values()) {
            // Adjunct dims are fixed — the store sees the minimum dims since the kind ignores width.
            ADJUNCTS.put(adjunct, new TrackSidePlotEditor<>(new TrackSidePlotEditor.Spec<>(
                PillarTemplateStore.adjunctKind(adjunct), name -> new Template.Adjunct(adjunct, name),
                (level, origin, dims) -> stampAdjunctFallback(level, origin, adjunct),
                null, null, "made_stair", Blocks.AIR, false, false, null)));
        }
        for (TunnelVariant variant : TunnelVariant.values()) {
            TUNNELS.put(variant, new TrackSidePlotEditor<>(new TrackSidePlotEditor.Spec<>(
                TunnelTemplateStore.tunnelKind(variant), name -> new Template.Tunnel(variant, name),
                null,
                (level, origin, name) -> placeTunnel(level, origin, variant, name),
                TrackSideEditors::eraseTunnel,
                // STRUCTURE_VOID: saved templates strip the corner wedges so in-world stamps leave
                // the surrounding rock alone.
                "made_tunnel", Blocks.STRUCTURE_VOID, false, false,
                "[DungeonTrain] Tunnel editor: edits mirror live across the enabled axes. "
                    + "Toggle Mirror X / Y / Z in the X menu.")));
        }
    }

    private TrackSideEditors() {}

    public static TrackSidePlotEditor<Template.Track> tile() {
        return TILE;
    }

    public static TrackSidePlotEditor<Template.Pillar> pillar(PillarSection section) {
        return PILLARS.get(section);
    }

    public static TrackSidePlotEditor<Template.Adjunct> adjunct(PillarAdjunct adjunct) {
        return ADJUNCTS.get(adjunct);
    }

    public static TrackSidePlotEditor<Template.Tunnel> tunnel(TunnelVariant variant) {
        return TUNNELS.get(variant);
    }

    /** The editor for any Tracks-row kind; {@link TrackKind#PORTAL_ROOM} throws. */
    public static TrackSidePlotEditor<? extends Template> forKind(TrackKind kind) {
        return switch (kind) {
            case TILE -> TILE;
            case PILLAR_TOP -> pillar(PillarSection.TOP);
            case PILLAR_MIDDLE -> pillar(PillarSection.MIDDLE);
            case PILLAR_BOTTOM -> pillar(PillarSection.BOTTOM);
            case ADJUNCT_STAIRS -> adjunct(PillarAdjunct.STAIRS);
            case ADJUNCT_STAIRS_ENTRANCE -> adjunct(PillarAdjunct.STAIRS_ENTRANCE);
            case TUNNEL_SECTION -> tunnel(TunnelVariant.SECTION);
            case TUNNEL_PORTAL -> tunnel(TunnelVariant.PORTAL);
            case PORTAL_ROOM -> throw new IllegalArgumentException("Portal rooms are not a track-side kind");
        };
    }

    // ---- fallbacks and kind-specific placement ----------------------------------------------

    /** Unauthored tile default: bed with a rail one in from each side. */
    private static void stampTileFallback(ServerLevel level, BlockPos origin, CarriageDims dims) {
        int w = dims.width();
        for (int dx = 0; dx < TrackPlacer.TILE_LENGTH; dx++) {
            for (int dz = 0; dz < w; dz++) {
                level.setBlock(origin.offset(dx, 0, dz), TrackPalette.BED, 3);
                if (dz == 1 || dz == w - 2) {
                    level.setBlock(origin.offset(dx, 1, dz), TrackPalette.RAIL, 3);
                }
            }
        }
    }

    /** Unauthored pillar default: a solid {@code 1 × height × width} slab. */
    private static void stampPillarFallback(ServerLevel level, BlockPos origin, PillarSection section,
                                            CarriageDims dims) {
        BlockState fallback = TrackPalette.PILLAR;
        int h = section.height();
        int w = dims.width();
        for (int dy = 0; dy < h; dy++) {
            for (int dz = 0; dz < w; dz++) {
                level.setBlock(origin.offset(0, dy, dz), fallback, 3);
            }
        }
    }

    /** Unauthored adjunct default: procedural stairs for STAIRS, a solid block for the rest. */
    private static void stampAdjunctFallback(ServerLevel level, BlockPos origin, PillarAdjunct adjunct) {
        int xs = adjunct.xSize();
        int ys = adjunct.ySize();
        int zs = adjunct.zSize();
        if (adjunct != PillarAdjunct.STAIRS) {
            BlockState block = TrackPalette.PILLAR;
            for (int dx = 0; dx < xs; dx++) {
                for (int dy = 0; dy < ys; dy++) {
                    for (int dz = 0; dz < zs; dz++) {
                        level.setBlock(origin.offset(dx, dy, dz), block, 3);
                    }
                }
            }
            return;
        }
        BlockState stairBlock = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(StairBlock.FACING, Direction.SOUTH);
        BlockState fill = TrackPalette.PILLAR;
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dy = 0; dy < ys; dy++) {
            int stepZ = Math.min(dy, zs - 1);
            for (int dx = 0; dx < xs; dx++) {
                for (int dz = 0; dz < zs; dz++) {
                    BlockState state;
                    if (dz < stepZ) state = fill;
                    else if (dz == stepZ) state = stairBlock;
                    else state = air;
                    level.setBlock(origin.offset(dx, dy, dz), state, 3);
                }
            }
        }
    }

    /**
     * Tunnels place through their own placer, which reads the store itself. The editor always
     * shows the unmirrored (entrance) orientation; the exit variant at world paint time is the
     * same template with {@code Mirror.FRONT_BACK}.
     */
    private static void placeTunnel(ServerLevel level, BlockPos origin, TunnelVariant variant, String name) {
        if (variant == TunnelVariant.SECTION) {
            TunnelPlacer.placeSectionNamed(level, origin, name);
        } else {
            TunnelPlacer.placePortalNamed(level, origin, false, name);
        }
        // STRUCTURE_VOID corner-wedge overlay so saved templates strip those positions and
        // in-world stamps leave the surrounding rock alone.
        TunnelGeometry tg = LegacyTunnelPaint.geometryForPlot(origin);
        LegacyTunnelPaint.fillCornersWithVoid(level, origin.getX(), tg);
    }

    /** The tunnel placer's own footprint erase (it re-anchors the plot above), then the cage. */
    private static void eraseTunnel(ServerLevel level, BlockPos origin, Vec3i size) {
        TunnelPlacer.eraseAt(level, origin);
        EditorPlotCage.setOutline(level, origin, size, Blocks.AIR.defaultBlockState());
    }
}
