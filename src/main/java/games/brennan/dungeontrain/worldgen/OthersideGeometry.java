package games.brennan.dungeontrain.worldgen;

/**
 * Where a Deeper Dark core's terrain is: Deeper Darker's Otherside density ({@code deeperdarker:otherside}
 * {@code final_density}) read per column and translated onto the track bed, Otherside y
 * {@link OthersideCore#CENTER_Y} on {@code bedY} — the same slab {@code OthersideCore} describes. The
 * Otherside is sampled at the display chunk's own X/Z (no offset), so neighbouring band chunks join up
 * exactly as Otherside chunks do.
 *
 * <p>A thin naming of {@link CellDensityGeometry} over the Otherside's settings: solid where the density is
 * positive, {@code sculk_grime} below the Otherside's sea level ({@link OthersideSettings#seaLevel}), air
 * above it. The surface skin and carvers run over that fill ({@code feature.OthersideSurfaceSkin},
 * {@code feature.OthersideCarve}). Not thread-safe — one per chunk, like the Nether's.</p>
 */
public final class OthersideGeometry {

    private final OthersideSettings settings;
    private final CellDensityGeometry cells;

    OthersideGeometry(OthersideSettings settings, int bedY, int worldMinY, int worldMaxY) {
        this.settings = settings;
        this.cells = new CellDensityGeometry(settings.finalDensity(), settings.cellW(), settings.cellH(),
                OthersideCore.CENTER_Y, OthersideCore.SAMPLE_Y_MIN, OthersideCore.SAMPLE_Y_MAX, bedY,
                worldMinY, worldMaxY);
    }

    /** Lowest world Y the slab occupies (its bedrock floor, unless the world floor clamps it). */
    public int minY() {
        return cells.minY();
    }

    /** Highest world Y the slab occupies (its bedrock roof, unless the world ceiling clamps it). */
    public int maxY() {
        return cells.maxY();
    }

    /** True when the geometry has any sampled rows at all. */
    public boolean isUsable() {
        return cells.isUsable();
    }

    /** Otherside y for a world Y. */
    public int othersideY(int worldY) {
        return cells.spaceY(worldY);
    }

    /** True when an open cell at this world Y is sculk grime rather than air (below the Otherside's sea). */
    public boolean isGrimeLevel(int worldY) {
        return othersideY(worldY) < settings.seaLevel();
    }

    /** One column's density view — open once per column, as {@link NetherCoreGeometry#column}. */
    public CellDensityGeometry.Column column(int worldX, int worldZ) {
        return cells.column(worldX, worldZ);
    }
}
