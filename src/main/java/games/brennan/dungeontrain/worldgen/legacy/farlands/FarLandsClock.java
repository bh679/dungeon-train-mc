package games.brennan.dungeontrain.worldgen.legacy.farlands;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.EditorQuietRuleEvents;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The Far Lands' fast clock: the day/night cycle speeds up the whole way through the Far Lands, peaking at
 * {@link #MAX_SPEED}×, then falls back to the ordinary pace just before the train leaves them.
 *
 * <p>The curve is laid in the band's script space ({@link FarLandsShift}), so it stretches with the band
 * exactly as the walls do: normal across the ordinary Beta land before the entry wall, then one continuous
 * linear climb from 1× at the entry wall to {@link #MAX_SPEED}× at {@link #PEAK}, then a fast smoothstep
 * fall over {@link #FALL_LEN} script blocks to 1× at {@link #NORMAL_BY} of the way through the core — just
 * short of the exit wall — and normal from there on, through the exit fade and beyond.</p>
 *
 * <p>Like {@code EditorClock}, it rides NeoForge's variable day length ({@code ServerLevel#setDayTimePerTick}),
 * which the server syncs to every client on the ordinary time packet — no client side. The clock is the
 * world's, so the fastest player's speed is everyone's.</p>
 *
 * <p><b>Only its own speed.</b> The clock is reset to vanilla only when the speed on it is the one this
 * class last set, so a speed from a command or another mod is never stomped. Editor worlds are skipped —
 * their clock is {@code EditorClock}'s.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class FarLandsClock {

    /** Clock ticks per game tick at the peak: a day in twelve seconds. */
    public static final float MAX_SPEED = 100.0f;
    /** Script block of the entry wall — where the Far Lands proper begin and the climb starts. */
    static final int ENTRY_WALL = FarLandsShift.APPROACH;
    /** Fraction of the way through the core by which the clock is back to normal (just before the exit wall). */
    static final double NORMAL_BY = 0.938D;
    /** Script block where the clock is back to 1×. */
    static final double FALL_END = NORMAL_BY * FarLandsShift.SCRIPT_LEN;
    /** Script blocks over which the clock falls from the peak back to 1×. */
    static final double FALL_LEN = 128.0D;
    /** Script block of the peak, where the climb ends and the fall begins. */
    static final double PEAK = FALL_END - FALL_LEN;

    /** NeoForge's "no speed set" sentinel — clock and game ticks coupled 1:1, as vanilla. */
    private static final float VANILLA_SPEED = -1.0f;
    /** Game ticks between checks; each write forces a time sync, so at most one a second. */
    private static final int CHECK_INTERVAL_TICKS = 20;
    /** Smallest change worth a write (and its sync packet). */
    private static final float MIN_STEP = 0.05f;

    /** The speed this class last put on the clock, or NaN when the clock is not ours. */
    private static float ownedSpeed = Float.NaN;

    private FarLandsClock() {}

    /**
     * Clock speed at script position {@code scriptPos} of a Far Lands core (script blocks from the core
     * start, as {@link FarLandsShift} lays them): 1 up to the entry wall, climbing to {@link #MAX_SPEED} at
     * {@link #PEAK}, falling back to 1 at {@link #FALL_END}, then 1. Pure.
     */
    public static float speedAt(double scriptPos) {
        if (Double.isNaN(scriptPos) || scriptPos <= ENTRY_WALL || scriptPos >= FALL_END) return 1.0f;
        if (scriptPos <= PEAK) {
            double t = (scriptPos - ENTRY_WALL) / (PEAK - ENTRY_WALL);
            return (float) (1.0D + (MAX_SPEED - 1.0D) * t);
        }
        double t = (scriptPos - PEAK) / FALL_LEN;
        double eased = t * t * (3.0D - 2.0D * t);
        return (float) (MAX_SPEED - (MAX_SPEED - 1.0D) * eased);
    }

    /** Clock speed for a player standing at {@code worldX} under {@code cycle}. Pure. */
    static float speedAtWorldX(WorldGenCycle cycle, int worldX) {
        long coreStart = cycle.legacyCoreStartX(LegacyBandKind.FAR_LANDS, worldX);
        if (coreStart == WorldGenCycle.NOT_IN_LEGACY_SLOT) return 1.0f;
        long holdLen = cycle.legacyCoreLenBlocks(LegacyBandKind.FAR_LANDS, worldX);
        long local = worldX - coreStart;
        if (holdLen <= 0L || local >= holdLen) return 1.0f;
        return speedAt((double) local * FarLandsShift.SCRIPT_LEN / holdLen);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % CHECK_INTERVAL_TICKS != 0) return;
        ServerLevel overworld = event.getServer().overworld();
        if (overworld == null || EditorQuietRuleEvents.isEditorWorld(overworld)) return;
        apply(overworld, targetSpeed(overworld));
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerLevel overworld = event.getServer().overworld();
        // Don't let a world quit mid-Far-Lands come back with a fast clock saved into level.dat.
        if (overworld != null) apply(overworld, 1.0f);
        ownedSpeed = Float.NaN;
    }

    /** The fastest speed any overworld player's position asks for; 1 in a world without a train. */
    private static float targetSpeed(ServerLevel overworld) {
        if (overworld.players().isEmpty()) return 1.0f;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.legacyLen(LegacyBandKind.FAR_LANDS) <= 0L) return 1.0f;
        if (!DungeonTrainWorldData.get(overworld).startsWithTrain()) return 1.0f;
        float speed = 1.0f;
        for (ServerPlayer player : overworld.players()) {
            speed = Math.max(speed, speedAtWorldX(cycle, player.getBlockX()));
        }
        return speed;
    }

    /** Put {@code speed} on the clock if it is ours to change and the change is worth a sync. */
    private static void apply(ServerLevel level, float speed) {
        float current = level.getDayTimePerTick();
        boolean ours = current == ownedSpeed;
        if (speed <= 1.0f) {
            if (ours) {
                level.setDayTimePerTick(VANILLA_SPEED); // forces its own time sync
                ownedSpeed = Float.NaN;
            }
            return;
        }
        if (!ours && current != VANILLA_SPEED) return; // someone else's speed — leave it
        if (ours && Math.abs(current - speed) < MIN_STEP) return;
        level.setDayTimePerTick(speed);
        ownedSpeed = speed;
    }
}
