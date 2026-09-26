package games.brennan.dungeontrain.worldgen.legacy.preset;

import games.brennan.dungeontrain.portal.PortalTwinSpace;
import games.brennan.dungeontrain.worldgen.WorldFloor;
import net.minecraft.server.level.ServerLevel;

/**
 * How far the Amplified band's terrain is sunk, and where the lid over it sits.
 *
 * <p><b>Why.</b> Amplified relief towers over the track, so at the stock noise window the train spends
 * most of the band inside mountains. Sinking the band by {@link #TARGET_DROP} blocks lets it ride high
 * over the valleys instead. The space it sinks into is the basement under the bedrock — the portal
 * system's twin space — so inside the band the twins move up to an <em>attic</em> over a barrier lid
 * at {@link #lidY} (see {@code PortalTwinSpace.regionFor}).</p>
 *
 * <p><b>The geometry.</b> The drop is clamped to the basement (a world with less room sinks less, one
 * with none — Compatible Terrain — not at all) and rounded down to a whole section, which keeps the
 * preset's noise window on the 16-block grid vanilla's {@code NoiseSettings} demands. The lid is placed
 * so the attic above it is at least as deep as the basement it replaces, then rounded down to the same
 * grid; the preset's noise window is capped there, so no terrain can ever reach it. Pure — shared by
 * the server and the client ({@code ClientUpsideDownBand}), which must agree on where twin space is.</p>
 *
 * @param drop blocks the band's terrain is lowered by; 0 means the band is untouched
 * @param lidY world Y of the barrier lid (and the attic's floor); {@link Integer#MAX_VALUE} when inactive
 */
public record AmplifiedDrop(int drop, int lidY) {

    /** The drop asked for: five sections, the depth of a stock basement. */
    public static final int TARGET_DROP = 80;

    /** Grid every value here snaps to — the section height vanilla's noise settings must align to. */
    static final int SECTION = 16;

    /** Shortest noise window worth sinking into; below this the band is left as it was. */
    static final int MIN_WINDOW = 128;

    /** The band untouched: no drop, no lid, no attic. */
    public static final AmplifiedDrop NONE = new AmplifiedDrop(0, Integer.MAX_VALUE);

    /** This level's drop — {@link #NONE} in a world with no basement. */
    public static AmplifiedDrop of(ServerLevel level) {
        return compute(WorldFloor.bedrockY(level), level.getMinBuildHeight(), level.getMaxBuildHeight(),
                PortalTwinSpace.CEILING_MARGIN);
    }

    /**
     * The pure form of {@link #of}.
     *
     * @param bedrockY       the terrain floor ({@link WorldFloor#bedrockY}) — also the stock noise floor
     * @param minBuildY      the build floor, bottom of the basement
     * @param maxBuildY      the build ceiling
     * @param ceilingMargin  rows kept clear under the build ceiling (the attic's ceiling)
     */
    public static AmplifiedDrop compute(int bedrockY, int minBuildY, int maxBuildY, int ceilingMargin) {
        int basement = bedrockY - minBuildY;
        int drop = floorToSection(Math.min(TARGET_DROP, Math.max(0, basement)));
        if (drop <= 0) return NONE;
        int lid = floorToSection(maxBuildY - ceilingMargin - basement);
        if (lid - (bedrockY - drop) < MIN_WINDOW) return NONE;
        return new AmplifiedDrop(drop, lid);
    }

    public boolean active() {
        return drop > 0;
    }

    /** The band's terrain floor (its bedrock row) given the world's ordinary one. */
    public int floorY(int bedrockY) {
        return bedrockY - drop;
    }

    /** Bottom of the preset's noise window. */
    public int noiseMinY(int baseMinY) {
        return baseMinY - drop;
    }

    /** Height of the preset's noise window: the stock window lowered by the drop, capped at the lid. */
    public int noiseHeight(int baseMinY, int baseHeight) {
        if (!active()) return baseHeight;
        int top = Math.min(baseMinY + baseHeight - drop, lidY);
        return floorToSection(top - noiseMinY(baseMinY));
    }

    /** The preset's sea level: lowered with the land, so the valleys don't flood. */
    public int seaLevel(int baseSeaLevel) {
        return baseSeaLevel - drop;
    }

    static int floorToSection(int v) {
        return Math.floorDiv(v, SECTION) * SECTION;
    }
}
