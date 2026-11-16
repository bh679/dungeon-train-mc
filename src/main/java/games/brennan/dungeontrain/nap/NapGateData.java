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
 * the gate itself ({@link PlayerRunState#setNapGate}, at the bed the player slept in) and the last X
 * the player stood at on the train's level. Written on wake and by {@link NapTrainXTracker}, read back
 * on login, dropped on a death. Stored at
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
    private static final String TAG_LAST_TRAIN_X = "lastTrainX";
    private static final String TAG_FURTHEST = "furthest";
    private static final String TAG_MIN_X = "minTrainX";
    private static final String TAG_MAX_X = "maxTrainX";

    /**
     * One player's entry; NaN = not known. {@code dir} 0 = no gate. {@code furthest} is the gate's
     * furthest point ({@link PlayerRunState#napFurthestX}); {@code minX}/{@code maxX} this life's
     * train-level X range, for the next nap.
     */
    record Entry(double x, int dir, double furthest, double lastTrainX, double minX, double maxX) {
        static final Entry EMPTY = new Entry(0.0, 0, 0.0, Double.NaN, Double.NaN, Double.NaN);

        boolean isEmpty() {
            return dir == 0 && Double.isNaN(lastTrainX) && Double.isNaN(minX) && Double.isNaN(maxX);
        }

        /** This entry having seen the player at train-level {@code tx}. */
        Entry withTrainX(double tx) {
            return new Entry(x, dir, furthest, tx,
                Double.isNaN(minX) ? tx : Math.min(minX, tx), Double.isNaN(maxX) ? tx : Math.max(maxX, tx));
        }

        CompoundTag toTag() {
            CompoundTag t = new CompoundTag();
            if (dir != 0) {
                t.putDouble(TAG_X, x);
                t.putInt(TAG_DIR, dir);
                t.putDouble(TAG_FURTHEST, furthest);
            }
            if (!Double.isNaN(lastTrainX)) t.putDouble(TAG_LAST_TRAIN_X, lastTrainX);
            if (!Double.isNaN(minX)) t.putDouble(TAG_MIN_X, minX);
            if (!Double.isNaN(maxX)) t.putDouble(TAG_MAX_X, maxX);
            return t;
        }

        /** An older entry has no furthest point: it is the bed. */
        static Entry fromTag(CompoundTag g) {
            int dir = Integer.signum(g.getInt(TAG_DIR));
            double x = dir == 0 ? 0.0 : g.getDouble(TAG_X);
            double furthest = dir == 0 ? 0.0 : g.contains(TAG_FURTHEST) ? g.getDouble(TAG_FURTHEST) : x;
            return new Entry(x, dir, furthest, optional(g, TAG_LAST_TRAIN_X), optional(g, TAG_MIN_X),
                optional(g, TAG_MAX_X));
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
                Entry e = Entry.fromTag(tag.getCompound(key));
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
        entries.forEach((id, e) -> tag.put(id.toString(), e.toTag()));
        return tag;
    }

    private Entry entry(UUID id) {
        return entries.getOrDefault(id, Entry.EMPTY);
    }

    /** Record {@code run}'s gate for {@code player} (or forget it when the run has none). */
    public static void remember(ServerPlayer player, PlayerRunState run) {
        NapGateData data = get(player.server);
        Entry old = data.entry(player.getUUID());
        boolean gated = run.napGated();
        Entry next = new Entry(gated ? run.napGateX() : 0.0, gated ? run.napGateDir() : 0,
            gated ? run.napFurthestX() : 0.0, old.lastTrainX(), run.lifeMinTrainX(), run.lifeMaxTrainX());
        data.put(player.getUUID(), old, next);
    }

    /** Record that {@code player} stands at {@code x} on the train's level now ({@link NapTrainXTracker}). */
    static void rememberTrainX(ServerPlayer player, double x) {
        NapGateData data = get(player.server);
        Entry old = data.entry(player.getUUID());
        data.put(player.getUUID(), old, old.withTrainX(x));
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

    /** After a relog: give the run state back the gate this world holds for the player. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Entry e = get(player.server).entries.get(player.getUUID());
        if (e == null) return;
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        if (e.dir() != 0 && !run.napGated()) run.setNapGate(e.x(), e.dir(), e.furthest());
        run.noteTrainRange(e.minX(), e.maxX());
    }

    /** A death ends the life, and the gate with it. */
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
     * else the gate's furthest point (counts as ahead).
     */
    static double gateX(boolean onTrainLevel, double x, double lastTrainX, double napGateX) {
        if (onTrainLevel && Double.isFinite(x)) return x;
        return Double.isFinite(lastTrainX) ? lastTrainX : napGateX;
    }

    /**
     * How {@code player}'s count-based counters move now ({@link PlayerRunState#counterStep}): -1
     * behind the bed, 0 short of the furthest point reached before the nap, else +1.
     */
    public static int step(ServerPlayer player) {
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        if (!run.napGated()) return 1;
        boolean on = onTrainLevel(player);
        double last = on ? Double.NaN : lastTrainX(player);
        return run.counterStep(gateX(on, player.getX(), last, run.napFurthestX()));
    }
}
