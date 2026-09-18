package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import games.brennan.dungeontrain.portal.PortalCarriageSelection;
import games.brennan.dungeontrain.template.GateContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Picks and places a whole <b>room</b> — a {@link WholeCarriage} stamped verbatim into one train
 * slot, the Whole section's answer to the shared-carriage lease.
 *
 * <p>Two rolls decide a room, and they are separate on purpose. The shell roll in
 * {@link CarriagePlacer#enclosedVariantForIndex} decides <em>whether</em> the slot is a whole one
 * by landing on the {@link #VARIANT_ID whole} entry in {@code templates/weights.json} — the same
 * way the {@code shared} entry decides a relay slot. This class then decides <em>which</em> room,
 * by a weighted draw over the pool salted with {@link #ROOM_SALT} so it is decorrelated from the
 * shell roll. When nothing fits — empty pool, every room gated out, wrong dims — the caller falls
 * through to placing {@code whole.nbt} as an ordinary carriage, exactly as a shared slot falls
 * back to {@code shared.nbt}.</p>
 */
public final class WholeCarriageSelection {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The carriage-variant id whose weight is the whole-room frequency. */
    public static final String VARIANT_ID = "whole";

    private static final long ROOM_SALT = 0x57484F4C45524F4DL; // "WHOLEROM"

    /** A room chosen for a slot, with its template already resolved at this world's dims. */
    public record RoomPick(WholeCarriage room, StructureTemplate template) {}

    private WholeCarriageSelection() {}

    public static boolean isWholeVariant(CarriageVariant variant) {
        return variant != null && VARIANT_ID.equals(variant.id());
    }

    /**
     * The room this slot draws, or null when the slot is not a whole one or nothing in the pool fits
     * — in which case the caller places the {@code whole} shell as a normal carriage.
     */
    public static RoomPick pickRoom(ServerLevel level, CarriageVariant variant, int carriagePIdx,
                                    CarriageDims dims, long generationSeed, GateContext gateCtx) {
        if (!isWholeVariant(variant)) return null;
        if (PortalCarriageSelection.isPortalPart(level, carriagePIdx)) {
            log(carriagePIdx, "PORTAL_SLOT", "");
            return null;
        }
        List<String> ids = WholeCarriageRegistry.ids();
        if (ids.isEmpty()) {
            log(carriagePIdx, "POOL_EMPTY", "");
            return null;
        }
        List<String> fits = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (WholeWeights.weightFor(WholeKind.ROOM, id) <= 0) continue;
            if (gateCtx != null && !gateCtx.allows(WholeWeights.gateFor(WholeKind.ROOM, id))) continue;
            if (WholeCarriageTemplateStore.get(level, new WholeCarriage(id), dims).isEmpty()) continue;
            fits.add(id);
        }
        if (fits.isEmpty()) {
            log(carriagePIdx, "NO_FIT", "");
            return null;
        }
        String chosen = weightedSeededPick(generationSeed ^ ROOM_SALT, carriagePIdx, fits,
            id -> WholeWeights.weightFor(WholeKind.ROOM, id));
        StructureTemplate template = WholeCarriageTemplateStore.get(level, new WholeCarriage(chosen), dims).orElse(null);
        if (template == null) return null;
        log(carriagePIdx, "ROOM_HIT", chosen);
        return new RoomPick(new WholeCarriage(chosen), template);
    }

    /** Stamp the pick into the train at {@code origin}; the footprint for the shipyard. */
    public static Set<BlockPos> place(ServerLevel level, BlockPos origin, RoomPick pick, CarriageDims dims) {
        return WholeCarriagePlacer.placeForTrain(level, origin, pick.template(), dims);
    }

    /**
     * Seeded weighted draw over {@code ids} — the same mixing {@link CarriagePlacer#weightedSeededPick}
     * uses, on a pool of ids rather than variants. Pure, so it can be tested without a level.
     */
    public static String weightedSeededPick(long seed, int index, List<String> ids, ToIntFunction<String> weight) {
        int n = ids.size();
        int[] cumulative = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            total += Math.max(0, weight.applyAsInt(ids.get(i)));
            cumulative[i] = total;
        }
        Random rng = new Random(seed ^ ((long) index * 0x9E3779B97F4A7C15L));
        if (total <= 0) return ids.get(rng.nextInt(n));
        int r = rng.nextInt(total);
        for (int i = 0; i < n; i++) {
            if (r < cumulative[i]) return ids.get(i);
        }
        return ids.get(n - 1);
    }

    private static void log(int pIdx, String outcome, String id) {
        LOGGER.info("[DungeonTrain] whole room pIdx={} → {}{}", pIdx, outcome, id.isEmpty() ? "" : " id=" + id);
    }
}
