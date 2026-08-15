package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Which contents template a portal's corridors are furnished with.
 *
 * <p>The {@code portal} contents carries a group sidecar ({@code contents/portal.group.json}), so it
 * is a parent with sub-variants rather than a single template. Nothing used to draw from it: the
 * corridor stamp named {@code portal} literally, and a group is only ever rolled on
 * {@code CarriageContentsRegistry.pick}'s path — which the portal branch of {@code CarriagePlacer}
 * returns before reaching. Every corridor in every world got {@code portal.nbt}.</p>
 *
 * <p><b>One roll per pair, and it has to be.</b> A corridor exists twice — as a carriage on the
 * train and as a static twin underground — and the two are stamped by independent calls. They must
 * produce identical blocks or the crossing tears open, which is the same reason
 * {@code PortalCarriageBuilder}'s {@code CONTENTS_SEED} and {@code CONTENTS_INDEX} are fixed. Both
 * sites can name the portal without any state passing between them, because {@code pairKey} is a
 * pure function of the carriage index ({@code PortalCarriageRole.entryIndexOf}). A pair's entry and
 * exit corridors share that key, so they share a member too — which suits a corridor whose geometry
 * is already mirror-symmetric between the two roles.</p>
 *
 * <p><b>Memoised, not merely deterministic.</b> A pure function of {@code (worldSeed, pairKey)} would
 * give the same answer every time only for as long as the group file does. Caching the draw means an
 * author who re-weights the group, or adds a member, while a portal is standing cannot make a
 * carriage disagree with the twin already in the ground beneath it. A pair keeps its corridor for the
 * life of the world, the same promise {@code PortalCarriageBuilder.planStructure} makes about the
 * room.</p>
 */
public final class PortalCorridorContents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The same mix {@code CarriageContentsRegistry} uses for its per-carriage draws, so neighbouring
     * portals separate rather than walking in step.
     */
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private static final Map<Integer, CarriageContents> PICKS = new HashMap<>();

    /** Which world {@link #PICKS} belongs to — a second world must not inherit the first's draws. */
    private static Long seed;

    private PortalCorridorContents() {}

    /**
     * The contents both of this pair's corridors — and both of their twins — are furnished with.
     *
     * <p>The parent is the one authored for this pair's {@link PortalCorridorKind}: the two kinds
     * furnish different-sized interiors, so they cannot draw from the same group. The pair's kind is
     * itself fixed for the life of the pair, so this does not weaken the memoisation below.</p>
     */
    public static synchronized CarriageContents forPair(ServerLevel level, PortalCorridorKind kind,
                                                        int pairKey) {
        long worldSeed = level.getSeed();
        if (seed == null || seed != worldSeed) {
            PICKS.clear();
            seed = worldSeed;
        }

        CarriageContents cached = PICKS.get(pairKey);
        if (cached != null) return cached;

        CarriageContents parent = PortalCarriageBuilder.portalContents(kind);
        // Ungated on purpose. A member's gate is tested against a spawn context, and the two stamp
        // sites do not share one — the carriage goes down when the train spawns, the twin from the
        // portal tick handler. Gating here could let them draw differently, which is the single
        // failure this class exists to prevent. Gates on portal sub-variants therefore do not apply;
        // making them work means deriving a context from pairKey alone, at both sites.
        CarriageContents picked = CarriageContentsRegistry.resolveSubVariant(
            parent, seedFor(worldSeed, pairKey), /*gateCtx*/ null);

        PICKS.put(pairKey, picked);
        LOGGER.info("[DungeonTrain] Portal pair {} corridors furnished with contents '{}'",
            pairKey, picked.id());
        return picked;
    }

    /**
     * What the pair's draw is seeded with — pure, so it can be tested without a world.
     *
     * <p>Package-private rather than inlined because the property that matters is not obvious from
     * the call site: neighbouring pair keys must separate rather than walk in step, or every portal
     * on a train draws the same member and the group may as well not exist.</p>
     */
    static long seedFor(long worldSeed, int pairKey) {
        return worldSeed ^ (pairKey * GOLDEN_GAMMA);
    }

    /** Drop every draw — called when the server stops, alongside the template caches. */
    public static synchronized void clear() {
        PICKS.clear();
        seed = null;
    }
}
