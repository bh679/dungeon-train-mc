package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.portal.PortalCarriageSelection;
import games.brennan.dungeontrain.worldgen.TransitionLandmarks;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Transition flatbeds</b>: a whole group of open flatbed deck ({@link #GROUP_SHELL}) at each dimension/band
 * transition ({@link TransitionLandmarks}), so the player is out in the open as the world changes.
 *
 * <p>Timed landmarks follow the <em>lead</em> player — the one furthest forward, whose walking drives
 * forward generation — and are claimed when the group holding the predicted carriage spawns
 * ({@link TransitionFlatbedPlan}). Every claim is written to {@link Data} keyed by train and carriage
 * index, so a group respawned after a cull, or rebuilt by backward generation, comes back as the same
 * flatbed; a landmark is only ever claimed once per train.</p>
 *
 * <p>Portal groups are left alone: a portal stamps over the slot regardless, so no claim is made in one —
 * a target that lands in one slides to the next group ({@link TransitionFlatbedPlan#LATE_SLACK_CARRIAGES}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TransitionFlatbeds {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The group-sized flatbed a group holding a transition flatbed stands as. */
    public static final String GROUP_SHELL = "flatbedgroup";

    /** The pace window, in Diff-Levels: the rider's average over at most this many levels' worth of carriages. */
    private static final int PACE_WINDOW_LEVELS = 4;

    /** An observation older than this no longer counts as the train's lead (two seconds). */
    private static final long LEAD_STALE_TICKS = 40L;

    /** Per train: each riding player's latest carriage, world X and pace. */
    private static final Map<UUID, Map<UUID, Rider>> RIDERS = new ConcurrentHashMap<>();

    private static final class Rider {
        final PlayerCarriagePace pace;
        int pIdx;
        double worldX;
        long tick;

        Rider(double seedPace) {
            this.pace = new PlayerCarriagePace(seedPace, PACE_WINDOW_LEVELS * DungeonTrainConfig.getCarriagesPerTier());
        }
    }

    private TransitionFlatbeds() {}

    /**
     * Called each tick from the appender for every player on train {@code trainId}'s line — riding it, or
     * out ahead of / beside it with an estimated index (a creative flyer outrunning the generated train).
     */
    public static void observe(UUID trainId, UUID player, int pIdx, double worldX, long tick) {
        Rider r = RIDERS.computeIfAbsent(trainId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(player, k -> new Rider(DungeonTrainCommonConfig.getTransitionFlatbedPace()));
        r.pace.observe(pIdx, tick);
        r.pIdx = pIdx;
        r.worldX = worldX;
        r.tick = tick;
    }

    /** The train's lead rider right now — the furthest-forward fresh observation — or {@code null}. */
    public static TransitionFlatbedPlan.Lead lead(UUID trainId, long now) {
        Map<UUID, Rider> riders = RIDERS.get(trainId);
        if (riders == null) return null;
        Rider best = null;
        for (Rider r : riders.values()) {
            if (now - r.tick > LEAD_STALE_TICKS) continue;
            if (best == null || r.pIdx > best.pIdx) best = r;
        }
        return best == null ? null : new TransitionFlatbedPlan.Lead(best.pIdx, best.worldX, best.pace.pace());
    }

    /** A new life: the player's pace starts again from the seed on every train. */
    public static void resetRider(UUID player) {
        for (Map<UUID, Rider> riders : RIDERS.values()) {
            Rider r = riders.get(player);
            if (r != null) r.pace.reset();
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered()) return; // End → overworld portal, not a death.
        resetRider(event.getEntity().getUUID());
    }

    /** Forget all riders (server stop). Claims live in {@link Data} and survive. */
    public static void clearRuntime() {
        RIDERS.clear();
    }

    /**
     * Which slots of the group {@code [anchorPIdx, anchorPIdx+groupSize)} are transition flatbeds. Makes any
     * new claims first when the group is ahead of the lead rider; a group behind them (backward generation,
     * a respawn) only reads claims already recorded. Returns {@code null} when no slot is forced.
     *
     * @param speed             train speed, blocks/s
     * @param stride            one whole group's length along X, pads and seam gap included
     */
    public static boolean[] forcedSlots(ServerLevel level, UUID trainId, int anchorPIdx, int groupSize,
                                        double speed, double stride) {
        if (trainId == null || groupSize < 1 || !DungeonTrainCommonConfig.isTransitionFlatbedsEnabled()) return null;
        ServerLevel overworld = level.getServer().overworld();
        Data data = Data.get(overworld);
        TransitionFlatbedPlan.Lead lead = lead(trainId, overworld.getGameTime());
        if (PortalCarriageSelection.isPortalGroup(overworld, anchorPIdx)) {
            logPortalDeferral(data, trainId, lead, anchorPIdx, groupSize, speed, stride);
            return null;
        }
        if (lead != null && anchorPIdx > lead.pIdx()) {
            claimAhead(data, trainId, lead, anchorPIdx, groupSize, speed, stride);
        }
        boolean[] forced = null;
        for (int s = 0; s < groupSize; s++) {
            if (data.landmarkAt(trainId, anchorPIdx + s) == null) continue;
            if (forced == null) forced = new boolean[groupSize];
            forced[s] = true;
        }
        return forced;
    }

    /** Say so when a portal group holds a pending landmark's predicted carriage — the next group takes it. */
    private static void logPortalDeferral(Data data, UUID trainId, TransitionFlatbedPlan.Lead lead, int anchorPIdx,
                                          int groupSize, double speed, double stride) {
        if (lead == null || anchorPIdx <= lead.pIdx()) return;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (!cycle.hasLayout()) return;
        double pitch = stride / Math.max(1, groupSize);
        for (TransitionLandmarks.Landmark lm : upcoming(cycle, lead)) {
            if (data.isClaimed(trainId, lm.id()) || lead.worldX() >= lm.worldX()) continue;
            int target = TransitionFlatbedPlan.predictTarget(lead.pIdx(), lead.worldX(), lm.worldX(), speed, lead.pace(), pitch);
            if (target < anchorPIdx || target >= anchorPIdx + groupSize) continue;
            LOGGER.info("[DungeonTrain][transition-flatbed] portal group {} holds {} (predicted pIdx {}) — deferring to the next group",
                anchorPIdx, lm.id(), target);
        }
    }

    /** The landmarks of the lead rider's run and the next. */
    private static List<TransitionLandmarks.Landmark> upcoming(WorldGenCycle cycle, TransitionFlatbedPlan.Lead lead) {
        int run = cycle.runIndexAt((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, lead.worldX())));
        List<TransitionLandmarks.Landmark> landmarks = new ArrayList<>(TransitionLandmarks.forRun(cycle, run));
        landmarks.addAll(TransitionLandmarks.forRun(cycle, run + 1));
        return landmarks;
    }

    private static void claimAhead(Data data, UUID trainId, TransitionFlatbedPlan.Lead lead, int anchorPIdx,
                                   int groupSize, double speed, double stride) {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (!cycle.hasLayout()) return;
        for (TransitionLandmarks.Landmark lm : upcoming(cycle, lead)) {
            if (data.isClaimed(trainId, lm.id())) continue;
            int[] claim = TransitionFlatbedPlan.claimFor(lm, lead, speed, anchorPIdx, groupSize, stride);
            if (claim == null) continue;
            data.claim(trainId, lm.id(), claim);
            LOGGER.info("[DungeonTrain][transition-flatbed] {} (x={}) → carriage(s) {} — lead pIdx={} x={} pace={} c/s, speed={} b/s",
                lm.id(), lm.worldX(), java.util.Arrays.toString(claim), lead.pIdx(),
                String.format("%.1f", lead.worldX()), String.format("%.3f", lead.pace()), speed);
        }
    }

    /** The landmark carriage {@code pIdx} of train {@code trainId} was claimed for, or {@code null}. */
    public static String landmarkAt(ServerLevel level, UUID trainId, int pIdx) {
        return trainId == null ? null : Data.get(level.getServer().overworld()).landmarkAt(trainId, pIdx);
    }

    /** Claims of train {@code trainId}: landmark id → carriage indices, in claim order. */
    public static Map<String, int[]> claims(ServerLevel level, UUID trainId) {
        return Data.get(level.getServer().overworld()).claims(trainId);
    }

    // ---- persistence ---------------------------------------------------------------------

    /** Per-world record of which carriage each train's transition flatbeds went to. */
    public static final class Data extends SavedData {

        private static final String NAME = "dungeontrain_transition_flatbeds";
        private static final String TAG_TRAINS = "trains";
        private static final String TAG_TRAIN = "train";
        private static final String TAG_CLAIMS = "claims";
        private static final String TAG_ID = "id";
        private static final String TAG_PIDX = "pIdx";

        private final Map<UUID, Map<String, int[]>> byLandmark = new HashMap<>();
        private final Map<UUID, Map<Integer, String>> byPIdx = new HashMap<>();

        public static Data get(ServerLevel overworld) {
            return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(Data::new, (tag, registries) -> load(tag)), NAME);
        }

        synchronized boolean isClaimed(UUID trainId, String landmarkId) {
            Map<String, int[]> m = byLandmark.get(trainId);
            return m != null && m.containsKey(landmarkId);
        }

        synchronized String landmarkAt(UUID trainId, int pIdx) {
            Map<Integer, String> m = byPIdx.get(trainId);
            return m == null ? null : m.get(pIdx);
        }

        synchronized Map<String, int[]> claims(UUID trainId) {
            Map<String, int[]> m = byLandmark.get(trainId);
            return m == null ? Map.of() : Map.copyOf(m);
        }

        synchronized void claim(UUID trainId, String landmarkId, int[] pIdxs) {
            put(trainId, landmarkId, pIdxs);
            setDirty();
        }

        private void put(UUID trainId, String landmarkId, int[] pIdxs) {
            byLandmark.computeIfAbsent(trainId, k -> new LinkedHashMap<>()).put(landmarkId, pIdxs.clone());
            Map<Integer, String> rev = byPIdx.computeIfAbsent(trainId, k -> new HashMap<>());
            // An earlier claim on the same carriage keeps it; the later landmark simply has one fewer flatbed.
            for (int p : pIdxs) rev.putIfAbsent(p, landmarkId);
        }

        static Data load(CompoundTag tag) {
            Data data = new Data();
            ListTag trains = tag.getList(TAG_TRAINS, Tag.TAG_COMPOUND);
            for (int t = 0; t < trains.size(); t++) {
                CompoundTag train = trains.getCompound(t);
                if (!train.hasUUID(TAG_TRAIN)) continue;
                UUID trainId = train.getUUID(TAG_TRAIN);
                ListTag claims = train.getList(TAG_CLAIMS, Tag.TAG_COMPOUND);
                for (int c = 0; c < claims.size(); c++) {
                    CompoundTag claim = claims.getCompound(c);
                    data.put(trainId, claim.getString(TAG_ID), claim.getIntArray(TAG_PIDX));
                }
            }
            return data;
        }

        @Override
        public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag trains = new ListTag();
            for (Map.Entry<UUID, Map<String, int[]>> e : byLandmark.entrySet()) {
                CompoundTag train = new CompoundTag();
                train.putUUID(TAG_TRAIN, e.getKey());
                ListTag claims = new ListTag();
                for (Map.Entry<String, int[]> c : e.getValue().entrySet()) {
                    CompoundTag claim = new CompoundTag();
                    claim.putString(TAG_ID, c.getKey());
                    claim.putIntArray(TAG_PIDX, c.getValue());
                    claims.add(claim);
                }
                train.put(TAG_CLAIMS, claims);
                trains.add(train);
            }
            tag.put(TAG_TRAINS, trains);
            return tag;
        }
    }
}
