package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.portal.PortalCarriageSelection;
import games.brennan.dungeontrain.template.GateContext;
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
 * <p>Two rolls decide a whole group, the same two that decide a whole room
 * ({@link WholeCarriageSelection}). The Group-carriage draw decides <em>whether</em> the run is a
 * whole one, by landing on the {@link #VARIANT_ID wholegroup} template in the Group row — so that
 * template's weight in {@code templates/weights.json} is the whole-group rate. This class then
 * decides <em>which</em> group, by a weighted draw over the pool salted with {@link #GROUP_SALT}.
 * When nothing fits, the caller places {@code wholegroup.nbt} as an ordinary Group carriage,
 * exactly as a whole-room slot falls back to {@code whole.nbt}. A portal group never takes one.</p>
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

    /** The Group carriage template whose weight is the whole-group frequency. */
    public static final String VARIANT_ID = "wholegroup";

    public static boolean isWholeGroupVariant(CarriageVariant variant) {
        return variant != null && VARIANT_ID.equals(variant.id());
    }

    /** The forced test cadence, pure: every {@code forced}-th group ordinal; {@code forced <= 0} is off. */
    public static boolean isForced(int anchorPIdx, int groupSize, int forced) {
        if (forced <= 0) return false;
        long groupIndex = Math.floorDiv((long) anchorPIdx, Math.max(1, groupSize));
        return Math.floorMod(groupIndex, (long) forced) == 0L;
    }

    /**
     * Whether this run should try for a whole group: the Group-carriage draw landed on the
     * {@link #VARIANT_ID Whole Group} template ({@code drawnShell}, null when the run drew no Group
     * carriage), or the session's forced cadence says so. Never a portal group.
     */
    public static boolean wantsWholeGroup(ServerLevel level, int anchorPIdx, int groupSize, CarriageVariant drawnShell) {
        if (PortalCarriageSelection.isPortalGroup(level, anchorPIdx)) return false;
        return isWholeGroupVariant(drawnShell) || isForced(anchorPIdx, groupSize, WholeGroupSettings.forced());
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
