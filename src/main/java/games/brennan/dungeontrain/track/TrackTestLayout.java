package games.brennan.dungeontrain.track;

import games.brennan.dungeontrain.builder.BuilderTrackScene;

import java.util.ArrayList;
import java.util.List;

/**
 * Where everything goes in a Tracks-category Test the Carriage — a stretch of line built in the
 * basement, rolled twice, with a carriage standing on the first roll:
 *
 * <pre>
 *   [½ flatbed | carriage | ½ flatbed]
 * ══ stretch A: columns in the open → portal · sections · portal ══╪══ stretch B, same, next roll ══
 * </pre>
 *
 * <p>Every offset is relative to the scene's corner: X along the line, Z from the corridor's first
 * block, and Y from the first free row over the floor the columns stand on — the floor itself is row
 * {@code -1}. The world's own rules are terrain-driven, but over a flat floor they collapse to
 * arithmetic on the column height — the reason {@link BuilderTrackScene} can draw its preview
 * without a level, and this without one either.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 *
 * @param carriageLength the world's carriage length
 * @param halfPad        {@code CarriagePlacer.halfPadLen} — each end pad's length
 * @param carriageHeight the world's carriage height
 * @param trackWidth     the corridor's width on Z
 * @param spacing        {@code TrackGenerator.computeSpacing(COLUMN_HEIGHT)}
 * @param thickness      {@code TrackGenerator.computeThickness(spacing)}
 */
public record TrackTestLayout(int carriageLength, int halfPad, int carriageHeight, int trackWidth,
                              int spacing, int thickness) {

    /** Column height — the builder's, for its reason: all three pillar sections and the full arch show. */
    public static final int COLUMN_HEIGHT = BuilderTrackScene.COLUMN_HEIGHT;

    /** {@code TrackPlacer.TILE_LENGTH}: tiles, and so the scene, align to it. */
    public static final int TILE_LENGTH = 4;

    /** {@code TunnelPlacer.LENGTH} — one portal or section along X. */
    public static final int TUNNEL_PIECE = 10;

    /** {@code TunnelPlacer.HEIGHT} — from the bed up to the apex, inclusive. */
    public static final int TUNNEL_HEIGHT = 14;

    /** Sections between the two portals: the fewest that show one section meeting another. */
    public static final int TUNNEL_SECTIONS = 2;

    /** Columns in the open stretch: one either side of the one the staircase stands beside. */
    public static final int COLUMNS = 3;

    /** Track behind the back pad, so the carriage visibly stands on a line rather than its end. */
    public static final int LEAD = TILE_LENGTH;

    /**
     * How far the down-stairs entrance rises above the tunnel's top: its 8 rows, less the 2 it
     * overlaps the shaft by ({@code TrackGenerator.ENTRANCE_OVERLAP_Y}).
     */
    public static final int ENTRANCE_RISE = 8 - 2;

    /** {@code TunnelPlacer.WIDTH} — the tunnel stamp's fixed Z extent, whatever the corridor's width. */
    public static final int TUNNEL_WIDTH = 13;

    /** First Z of the tunnel stamp from the corridor's first block: {@code wallMinZ + 1}. */
    public static final int TUNNEL_Z = -3;

    /**
     * Clearance on Z either side of the corridor that the sweep covers: a down-stairs entrance
     * reaches 5 past the corridor on its side, a pillar staircase 3 on its own. The tunnel's fixed
     * width is covered separately — on a narrow corridor it reaches further than this.
     */
    public static final int Z_MARGIN = 6;

    /** Row the bed sits on, one above the columns' caps. */
    public static int bedY() {
        return COLUMN_HEIGHT + 1;
    }

    /** Row the carriage's floor sits on — the train's Y, two above the bed. */
    public static int trainY() {
        return bedY() + 2;
    }

    /** The row a down-stairs shaft reaches up to: the first above the tunnel. */
    public static int surfaceY() {
        return bedY() + TUNNEL_HEIGHT;
    }

    /** The train on the line: back pad, carriage, front pad — the way a one-carriage sub-level stands. */
    public int trainLength() {
        return 2 * halfPad + carriageLength;
    }

    /**
     * The X the test is about — where the piece under test shows best, and so where the train stands
     * and the author arrives:
     * <ul>
     *   <li>a track tile is laid everywhere, so the open line, where the train always stood;</li>
     *   <li>a pillar section or the staircase: over the middle column, the one the stairs stand beside;</li>
     *   <li>a tunnel portal: its mouth, the train half in and half out;</li>
     *   <li>a tunnel section: inside, over the joint where one section meets the next;</li>
     *   <li>a staircase entrance: inside, under the shaft the entrance caps.</li>
     * </ul>
     */
    public int focusX(TrackTestPiece piece) {
        return switch (piece) {
            case TILE -> LEAD + trainLength() / 2;
            case PILLAR_BOTTOM, PILLAR_MIDDLE, PILLAR_TOP, STAIRS -> stairsColumn();
            case TUNNEL_PORTAL -> tunnelX();
            case TUNNEL_SECTION -> sectionsMidX();
            case STAIRS_ENTRANCE -> downStairsCentre();
        };
    }

    /** Where the train's back pad starts: centred on the focus, kept inside the stretch. */
    public int backPadX(TrackTestPiece piece) {
        int centred = focusX(piece) - trainLength() / 2;
        return Math.max(0, Math.min(centred, stretchLength() - trainLength()));
    }

    public int carriageX(TrackTestPiece piece) { return backPadX(piece) + halfPad; }

    public int frontPadX(TrackTestPiece piece) { return carriageX(piece) + carriageLength; }

    /** Open line before the tunnel: room for the train behind the lead and every column, on the tile grid. */
    public int openLength() {
        int needed = Math.max(COLUMNS * spacing, LEAD + trainLength() + TILE_LENGTH);
        return roundUp(needed, TILE_LENGTH);
    }

    /** Portal, sections, portal. */
    public static int tunnelLength() {
        return TUNNEL_PIECE * (TUNNEL_SECTIONS + 2);
    }

    /** One roll of the line, open then tunnel. */
    public int stretchLength() {
        return openLength() + tunnelLength();
    }

    /** Both rolls, end to end. */
    public int sceneLength() {
        return 2 * stretchLength();
    }

    /** Column centres in one stretch, on the generator's own spacing. */
    public List<Integer> columnCentres() {
        List<Integer> out = new ArrayList<>(COLUMNS);
        for (int i = 0; i < COLUMNS; i++) out.add(spacing / 2 + i * spacing);
        return out;
    }

    /** First X a column occupies — the generator's own footprint rule. */
    public int columnMinX(int centre) {
        return centre - (thickness - 1) / 2;
    }

    public int columnMaxX(int centre) {
        return columnMinX(centre) + thickness - 1;
    }

    /** The column the pillar staircase stands beside: the middle one. */
    public int stairsColumn() {
        return columnCentres().get(COLUMNS / 2);
    }

    /** First X of the tunnel. */
    public int tunnelX() {
        return openLength();
    }

    /** A tunnel piece: where it starts, whether it is a portal, and whether it faces back down the line. */
    public record TunnelPiece(int x, boolean portal, boolean mirrored) {}

    /** The tunnel's pieces in order: entrance portal, sections, exit portal (mirrored, as the world lays it). */
    public List<TunnelPiece> tunnelPieces() {
        List<TunnelPiece> out = new ArrayList<>(TUNNEL_SECTIONS + 2);
        int x = tunnelX();
        out.add(new TunnelPiece(x, true, false));
        for (int i = 0; i < TUNNEL_SECTIONS; i++) {
            x += TUNNEL_PIECE;
            out.add(new TunnelPiece(x, false, false));
        }
        out.add(new TunnelPiece(x + TUNNEL_PIECE, true, true));
        return out;
    }

    /**
     * Centre of the down-stairs shaft: the middle of the sections, clear of both portals — where a
     * train stood under it fits inside the tunnel.
     */
    public int downStairsCentre() {
        return sectionsMidX();
    }

    /** The joint between the sections: the tunnel's middle, a whole section clear of either portal. */
    private int sectionsMidX() {
        return tunnelX() + TUNNEL_PIECE + TUNNEL_SECTIONS * TUNNEL_PIECE / 2;
    }

    /** Highest row anything reaches: the down-stairs entrance, or a carriage taller than it. */
    public int topY() {
        int entranceTop = surfaceY() + ENTRANCE_RISE - 1;
        int carriageTop = trainY() + carriageHeight - 1;
        return Math.max(entranceTop, carriageTop);
    }

    /** Lowest Z the scene reaches. */
    public static int minZ() {
        return -Z_MARGIN;
    }

    /** Highest Z the scene reaches: past the corridor, or the tunnel's far wall if that is further. */
    public int maxZ() {
        return Math.max(trackWidth - 1 + Z_MARGIN, TUNNEL_Z + TUNNEL_WIDTH - 1);
    }

    private static int roundUp(int value, int step) {
        return Math.floorDiv(value + step - 1, step) * step;
    }
}
