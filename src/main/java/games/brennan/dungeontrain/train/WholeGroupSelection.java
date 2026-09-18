package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.portal.PortalCarriageSelection;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.template.SeededDraw;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a carriage group is a whole <b>group</b> — one {@link CarriageGroup} template
 * stamped over the entire run — and which one.
 *
 * <p>Modelled on {@code PortalCarriageSelection.isPortalGroup}: a seeded lottery per group ordinal
 * at one-in-{@link WholeGroupSettings#every() N}, hashed rather than rolled so a re-stamped window
 * gets the same answer. Salted with {@link #GROUP_SALT} so it never lines up with the portal draw,
 * and a portal group always wins the collision anyway. No minimum gap: two whole groups in a row is
 * an ordinary thing for a pool of authored runs.</p>
 *
 * <p>A template is only offered when its footprint holds exactly this train's
 * {@code groupSize} carriages; a group that has no fitting template at all is not a whole group.</p>
 */
public final class WholeGroupSelection {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long GROUP_SALT = 0x57484F4C45475250L; // "WHOLEGRP"

    /** A group chosen for a run, its template resolved at this world's dims and group size. */
    public record GroupPick(CarriageGroup group, StructureTemplate template) {}

    private WholeGroupSelection() {}

    /** The pure lottery — testable without a level. {@code every <= 0} is off. */
    public static boolean isWholeGroup(int anchorPIdx, int groupSize, int every, long worldSeed) {
        if (every <= 0) return false;
        long groupIndex = Math.floorDiv((long) anchorPIdx, Math.max(1, groupSize));
        return SeededDraw.hit(worldSeed ^ GROUP_SALT, groupIndex, every);
    }

    /** The live verdict: settings, the session-only forced cadence, and the portal exclusion. */
    public static boolean isWholeGroup(ServerLevel level, int anchorPIdx, int groupSize, long worldSeed) {
        if (PortalCarriageSelection.isPortalGroup(level, anchorPIdx)) return false;
        int forced = WholeGroupSettings.forced();
        if (forced > 0) {
            long groupIndex = Math.floorDiv((long) anchorPIdx, Math.max(1, groupSize));
            return Math.floorMod(groupIndex, (long) forced) == 0L;
        }
        return isWholeGroup(anchorPIdx, groupSize, WholeGroupSettings.every(), worldSeed);
    }

    /**
     * The group template for this run, or null when none fits — an empty pool, every group gated
     * out, or no template at this train's group size. Null means "not a whole group after all".
     */
    public static GroupPick pick(ServerLevel level, int anchorPIdx, int groupSize, CarriageDims dims,
                                 long worldSeed, GateContext anchorGate) {
        List<String> fits = new ArrayList<>();
        for (String id : CarriageGroupRegistry.ids()) {
            if (WholeWeights.weightFor(WholeKind.GROUP, id) <= 0) continue;
            if (anchorGate != null && !anchorGate.allows(WholeWeights.gateFor(WholeKind.GROUP, id))) continue;
            if (CarriageGroupTemplateStore.carriagesIn(level, new CarriageGroup(id), dims) != groupSize) continue;
            fits.add(id);
        }
        if (fits.isEmpty()) {
            LOGGER.info("[DungeonTrain] whole group anchorPIdx={} → NO_FIT (groupSize={})", anchorPIdx, groupSize);
            return null;
        }
        int groupIndex = (int) Math.floorDiv((long) anchorPIdx, Math.max(1, groupSize));
        String chosen = WholeCarriageSelection.weightedSeededPick(worldSeed ^ GROUP_SALT, groupIndex, fits,
            id -> WholeWeights.weightFor(WholeKind.GROUP, id));
        StructureTemplate template = CarriageGroupTemplateStore.get(level, new CarriageGroup(chosen), dims, groupSize).orElse(null);
        if (template == null) return null;
        LOGGER.info("[DungeonTrain] whole group anchorPIdx={} → GROUP_HIT id={}", anchorPIdx, chosen);
        return new GroupPick(new CarriageGroup(chosen), template);
    }

    /**
     * Stamp the pick over the whole run at {@code runOrigin}, then roll its Z/C overlay at
     * {@code (seed, anchorPIdx)}; the footprint for the shipyard.
     */
    public static Set<BlockPos> place(ServerLevel level, BlockPos runOrigin, GroupPick pick,
                                      CarriageDims dims, int groupSize, long seed, int anchorPIdx) {
        CarriageGroupPlacer.placeForTrain(level, runOrigin, pick.template(), dims, groupSize);
        WholeOverlay.apply(level, runOrigin, WholeKind.GROUP, pick.group().id(),
            CarriageGroupPlacer.sizeOf(dims, groupSize), seed, anchorPIdx);
        java.util.Set<BlockPos> placed = new java.util.HashSet<>();
        for (int i = 0; i < Math.max(1, groupSize); i++) {
            placed.addAll(CarriagePlacer.collectFootprint(level, runOrigin.offset(i * dims.length(), 0, 0), dims));
        }
        return placed;
    }
}
