package games.brennan.dungeontrain.train.id;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.BandFlavor;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

import java.util.Set;

/**
 * Every carriage's id, from the world it is in: the mark comes from the train generation seed, the
 * number is the carriage index, and the rest from {@link CarriageIdLedger} — or, for a group built
 * before the ledger existed, from what the carriage index alone gives.
 */
public final class CarriageIds {

    private CarriageIds() {}

    /**
     * A carriage's id, and whether its context was recorded when it was built. A carriage whose group
     * predates the ledger gets a context worked out from its index, which can disagree with what
     * stands there near a band edge or after the difficulty offset was changed.
     */
    public record Lookup(CarriageId id, boolean recorded) {}

    /** The full id of carriage {@code pIdx} in {@code level}'s world. */
    public static Lookup idFor(ServerLevel level, int pIdx) {
        ServerLevel overworld = level.getServer().overworld();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        String mark = WorldMark.of(data.getGenerationConfig().seed());
        int groupSize = Math.max(1, data.getGenerationConfig().groupSize());
        int anchor = GateContext.groupAnchorPIdx(pIdx, groupSize);
        int baseline = baselineLevel(anchor);
        GroupRecord record = CarriageIdLedger.get(overworld).get(anchor);
        if (record != null) {
            return new Lookup(new CarriageId(mark, pIdx, record.contextFor(pIdx - anchor, baseline)), true);
        }
        GateContext derived = GateContext.forCarriage(overworld, pIdx, data.dims().length());
        GroupRecord guess = recordOf(derived, groupSize, false, null, false, null);
        return new Lookup(new CarriageId(mark, pIdx, guess.contextFor(pIdx - anchor, baseline)), false);
    }

    /**
     * The difficulty level the group anchored at {@code anchorPIdx} gets from its carriage number
     * alone, with no difficulty offset. An id leaves its level out when it is this one.
     */
    public static int baselineLevel(int anchorPIdx) {
        return DifficultyProgression.tierForTravelled(Math.abs(anchorPIdx));
    }

    /** The world mark of the world {@code level} is in. */
    public static String markOf(ServerLevel level) {
        return WorldMark.of(DungeonTrainWorldData.get(level.getServer().overworld()).getGenerationConfig().seed());
    }

    /**
     * The record for a group built at {@code anchorGate}.
     *
     * @param transitionFlatbed per-slot transition-flatbed claims, or {@code null} for none
     * @param sharedBySlot      per-slot relay build ids ({@link GroupRecord#NOT_SHARED} for none),
     *                          or {@code null} when no slot was leased
     */
    public static GroupRecord recordOf(GateContext anchorGate, int groupSize, boolean portal,
                                       boolean[] transitionFlatbed, boolean forced, int[] sharedBySlot) {
        int transitionMask = 0;
        if (transitionFlatbed != null) {
            for (int i = 0; i < Math.min(transitionFlatbed.length, 30); i++) {
                if (transitionFlatbed[i]) transitionMask |= 1 << i;
            }
        }
        int[] shared = new int[groupSize];
        java.util.Arrays.fill(shared, GroupRecord.NOT_SHARED);
        if (sharedBySlot != null) System.arraycopy(sharedBySlot, 0, shared, 0, Math.min(groupSize, sharedBySlot.length));
        return new GroupRecord(groupSize, anchorGate.level(), anchorGate.phase().ordinal(), bandMaskOf(anchorGate.bands()),
            currentVersion(), portal, transitionMask, forced, shared);
    }

    /** Record what the group anchored at {@code anchorPIdx} was built with. A rebuild records nothing. */
    public static void record(ServerLevel level, int anchorPIdx, GroupRecord record) {
        if (RebuildScope.active()) return;
        CarriageIdLedger.get(level.getServer().overworld()).put(anchorPIdx, record);
    }

    static int bandMaskOf(Set<BandFlavor> bands) {
        int mask = 0;
        for (BandFlavor b : bands) mask |= 1 << b.ordinal();
        return mask;
    }

    /** The running mod's minor version, the {@code 1737} of {@code 0.1737.0}; 0 when unreadable. */
    public static int currentVersion() {
        return ModList.get().getModContainerById(DungeonTrain.MOD_ID)
            .map(c -> minorOf(c.getModInfo().getVersion().toString()))
            .orElse(0);
    }

    static int minorOf(String version) {
        String[] parts = version.split("[.+-]");
        if (parts.length < 2) return 0;
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
