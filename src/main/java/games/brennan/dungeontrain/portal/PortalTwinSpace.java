package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import games.brennan.dungeontrain.worldgen.WorldFloor;
import net.minecraft.server.level.ServerLevel;

/**
 * Which sealed region of a given world a portal twin belongs in at a given world-X — the basement
 * under the bedrock everywhere, the attic above the inverted bedrock lid inside the upside-down band.
 *
 * <p><b>Why the band is different.</b> A twin's hiding place is not a depth, it is a seal: world that
 * generation never fills and a player can never reach. The band inverts which end of the world that
 * is. Its mirror clears the bedrock row so the underside opens onto void — leaving the basement, and
 * every structure in it, hanging in plain view under an open world — and stamps a bedrock lid
 * overhead instead. Above that lid is untouched, sealed, and roomier than the basement it replaces
 * (about 205 blocks at stock settings against 80). See {@link PortalTwinRegion}.</p>
 *
 * <p><b>Only the core band, deliberately.</b> The entry lead-in and the exit crossfade have no
 * continuous lid — it grows in over the lead-in and recedes across the exit — so an attic there would
 * be sealed only sometimes. Those columns keep the basement and behave exactly as they did before
 * this class existed, as does any world running with {@code upsideDownBedrockRoof} off.</p>
 *
 * <p>The level-aware half of {@link PortalTwinRegion}; the geometry itself is over there, without
 * Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class PortalTwinSpace {

    /**
     * Rows kept clear under the build ceiling, matching the margin the twin fit gate has always held
     * a structure's top to. The attic's ceiling, not a separate rule.
     */
    public static final int CEILING_MARGIN = 4;

    private PortalTwinSpace() {}

    /**
     * The region a pair whose entry carriage is at {@code worldX} stamps into.
     *
     * <p>{@code structureHeight} is the world's lane spacing — the tallest structure it knows how to
     * stamp — because an attic too shallow to stand one of those up would hand out lanes every pair
     * then failed the fit gate on, leaving portal carriages that look ordinary and never cross. Such
     * a world falls back to the basement, which is what it did before the band knew about portals.</p>
     */
    public static PortalTwinRegion regionFor(ServerLevel level, int worldX, int structureHeight) {
        PortalTwinRegion basement = basementOf(level);
        if (!DungeonTrainCommonConfig.isUpsideDownBedrockRoof()) return basement;
        if (!UpsideDownBand.isInBand(level, worldX)) return basement;
        PortalTwinRegion attic = atticOf(level);
        return attic.canHold(structureHeight) ? attic : basement;
    }

    /** The basement under this world's bedrock — the region outside the band. */
    public static PortalTwinRegion basementOf(ServerLevel level) {
        return PortalTwinRegion.basement(level.getMinBuildHeight(), WorldFloor.bedrockY(level));
    }

    /**
     * Whether {@code region} is this world's attic rather than its basement — which is worth asking
     * because only the attic shares its space with the mirror that stamps the lid.
     */
    public static boolean isAttic(ServerLevel level, PortalTwinRegion region) {
        return region.equals(atticOf(level));
    }

    /** The space above this world's in-band bedrock lid, whether or not one is stamped here. */
    public static PortalTwinRegion atticOf(ServerLevel level) {
        return PortalTwinRegion.attic(
            UpsideDownBand.roofY(level), level.getMaxBuildHeight(), CEILING_MARGIN);
    }

    /**
     * Whether {@code (worldX, y)} is inside twin space — the honest form of the "is this player below
     * the bedrock, so they must be in a portal room" shorthand several systems use.
     *
     * <p>The basement counts everywhere; the attic counts only where a lid is actually stamped over
     * it, or every mountainside outside the band would answer yes. Both are tested rather than the one
     * {@link #regionFor} would choose, because a structure outlives the choice: a pair stamped in the
     * basement keeps standing there until the train has carried it far enough to be re-stamped, band
     * edge or no. A caller asking this question wants to know where somebody <i>is</i>, not where a
     * new twin would go.</p>
     */
    public static boolean isInside(ServerLevel level, int worldX, double y) {
        if (basementOf(level).contains(y)) return true;
        return DungeonTrainCommonConfig.isUpsideDownBedrockRoof()
            && UpsideDownBand.isInBand(level, worldX)
            && atticOf(level).contains(y);
    }
}
