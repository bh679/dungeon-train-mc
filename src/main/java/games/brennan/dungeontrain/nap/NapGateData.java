package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.ship.Shipyards;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per player, what the nap gate needs that the run-state codec (full) can't keep across a relog:
 * the gate itself ({@link PlayerRunState#setNapGate}), how far this life has reached each way
 * ({@link PlayerRunState#noteReach}), and the last X the player stood at on the train's level.
 * Written on wake and by {@link NapReachTracker}, read back on login, dropped on a death. Stored at
 * {@code <world>/data/dungeontrain_nap_gates.dat}.
 *
 * <p>The train runs in the overworld. Anywhere else (a dimensional carriage, a home room, a real
 * dimension, the Sable plot grid) X says nothing about the train, so the gate is judged at the last
 * train-level X instead: a room counts as being where it was entered from.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NapGateData extends SavedData {

    public static final String NAME = "dungeontrain_nap_gates";
    private static final String TAG_X = "x";
    private static final String TAG_DIR = "dir";
    private static final String TAG_REACH_MIN = "reachMin";
    private static final String TAG_REACH_MAX = "reachMax";
    private static final String TAG_LAST_TRAIN_X = "lastTrainX";

    /** One player's entry; NaN = not known. {@code dir} 0 = no gate. */
    private record Entry(double x, int dir, double reachMin, double reachMax, double lastTrainX) {
        static final Entry EMPTY = new Entry(0.0, 0, Double.NaN, Double.NaN, Double.NaN);

        boolean isEmpty() {
            return dir == 0 && Double.isNaN(reachMin) && Double.isNaN(lastTrainX);
        }
    }

    private final Map<UUID, Entry> entries = new HashMap<>();

    private NapGateData() {}

    public static NapGateData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(NapGateData::new, (tag, registries) -> load(tag)), NAME);
    }

    private static NapGateData load(CompoundTag tag) {
        NapGateData data = new NapGateData();
        for (String key : tag.getAllKeys()) {
            try {
                CompoundTag g = tag.getCompound(key);
                int dir = Integer.signum(g.getInt(TAG_DIR));
                Entry e = new Entry(dir == 0 ? 0.0 : g.getDouble(TAG_X), dir,
                    optional(g, TAG_REACH_MIN), optional(g, TAG_REACH_MAX), optional(g, TAG_LAST_TRAIN_X));
                if (!e.isEmpty()) data.entries.put(UUID.fromString(key), e);
            } catch (IllegalArgumentException ignored) {
                // not a player id: skip the entry
            }
        }
        return data;
    }

    private static double optional(CompoundTag t, String key) {
        return t.contains(key) ? t.getDouble(key) : Double.NaN;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        entries.forEach((id, e) -> {
            CompoundTag t = new CompoundTag();
            if (e.dir() != 0) {
                t.putDouble(TAG_X, e.x());
                t.putInt(TAG_DIR, e.dir());
            }
            if (!Double.isNaN(e.reachMin()) && !Double.isNaN(e.reachMax())) {
                t.putDouble(TAG_REACH_MIN, e.reachMin());
                t.putDouble(TAG_REACH_MAX, e.reachMax());
            }
            if (!Double.isNaN(e.lastTrainX())) t.putDouble(TAG_LAST_TRAIN_X, e.lastTrainX());
            tag.put(id.toString(), t);
        });
        return tag;
    }

    private Entry entry(UUID id) {
        return entries.getOrDefault(id, Entry.EMPTY);
    }

    /** Record {@code run}'s gate and reach for {@code player}. */
    public static void remember(ServerPlayer player, PlayerRunState run) {
        NapGateData data = get(player.server);
        Entry old = data.entry(player.getUUID());
        Entry next = new Entry(run.napGated() ? run.napGateX() : 0.0, run.napGated() ? run.napGateDir() : 0,
            run.hasReach() ? run.reachMin() : Double.NaN, run.hasReach() ? run.reachMax() : Double.NaN,
            old.lastTrainX());
        data.put(player.getUUID(), old, next);
    }

    /** Record that {@code player} stands at {@code x} on the train's level now ({@link NapReachTracker}). */
    static void rememberTrainX(ServerPlayer player, PlayerRunState run, double x) {
        NapGateData data = get(player.server);
        Entry old = data.entry(player.getUUID());
        Entry next = new Entry(old.x(), old.dir(),
            run.hasReach() ? run.reachMin() : Double.NaN, run.hasReach() ? run.reachMax() : Double.NaN, x);
        data.put(player.getUUID(), old, next);
    }

    private void put(UUID id, Entry old, Entry next) {
        if (next.equals(old)) return;
        if (next.isEmpty()) entries.remove(id);
        else entries.put(id, next);
        setDirty();
    }

    /** The last X {@code player} had on the train's level in this world, NaN when not known. */
    public static double lastTrainX(ServerPlayer player) {
        return get(player.server).entry(player.getUUID()).lastTrainX();
    }

    /** After a relog: give the run state back the gate and reach this world holds for the player. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Entry e = get(player.server).entries.get(player.getUUID());
        if (e == null) return;
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        if (e.dir() != 0 && !run.napGated()) run.setNapGate(e.x(), e.dir());
        run.noteReach(e.reachMin());
        run.noteReach(e.reachMax());
    }

    /** A death ends the life, and the gate and reach with it. */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered() || !(event.getEntity() instanceof ServerPlayer player)) return;
        NapGateData data = get(player.server);
        if (data.entries.remove(player.getUUID()) != null) data.setDirty();
    }

    /**
     * True when {@code player}'s X is a train X: in the overworld and not inside the Sable plot grid.
     * A failed plot-grid lookup counts as "not in it", as {@code RunStatsEvents#screenedPosition} does.
     */
    public static boolean onTrainLevel(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return false;
        try {
            return !Shipyards.isInPlotGrid(level, player.blockPosition());
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * The X the gate is judged at: the player's own on the train's level, else the last train-level X,
     * else the gate itself (counts as ahead).
     */
    static double gateX(boolean onTrainLevel, double x, double lastTrainX, double napGateX) {
        if (onTrainLevel && Double.isFinite(x)) return x;
        return Double.isFinite(lastTrainX) ? lastTrainX : napGateX;
    }

    /** -1 when {@code player} is behind their nap point (counters shrink), else +1. */
    public static int step(ServerPlayer player) {
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        if (!run.napGated()) return 1;
        boolean on = onTrainLevel(player);
        double last = on ? Double.NaN : lastTrainX(player);
        return run.counterStep(gateX(on, player.getX(), last, run.napGateX()));
    }
}
