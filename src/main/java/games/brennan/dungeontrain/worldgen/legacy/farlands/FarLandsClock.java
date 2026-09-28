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
 * The Far Lands' fast clock: the day/night cycle runs up to {@link #MAX_SPEED}× while the train is inside
 * the Far Lands proper, and at the ordinary pace everywhere else.
 *
 * <p>The speed follows the band's script ({@link FarLandsShift}), so the clock and the walls agree: normal
 * across the ordinary Beta land before the entry wall, a smoothstep ramp up over the {@link #RAMP} script
 * blocks riding inside the edge lands, full speed through the closing walls, the canyon and the opening,
 * then the mirror-image ramp down to normal at the exit wall — and normal again on the land beyond it.</p>
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

    /** Clock ticks per game tick at the heart of the Far Lands: a day in two and a half minutes. */
    public static final float MAX_SPEED = 8.0f;
    /** Script blocks over which the speed ramps, after the entry wall and before the exit wall. */
    static final int RAMP = FarLandsShift.ENTRY_INSIDE;
    /** Script block of the entry wall — where the Far Lands proper begin. */
    static final int ENTRY_WALL = FarLandsShift.APPROACH;

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
     * Clock speed at script position {@code scriptPos} of a Far Lands band (script blocks from the core
     * start, as {@link FarLandsShift} lays them): 1 outside the walls, {@link #MAX_SPEED} between the ramps. Pure.
     */
    public static float speedAt(double scriptPos) {
        if (Double.isNaN(scriptPos)) return 1.0f;
        double into = scriptPos - ENTRY_WALL;
        double left = FarLandsShift.EXIT_WALL - scriptPos;
        if (into <= 0.0D || left <= 0.0D) return 1.0f;
        double t = Math.min(1.0D, Math.min(into, left) / RAMP);
        double eased = t * t * (3.0D - 2.0D * t);
        return (float) (1.0D + (MAX_SPEED - 1.0D) * eased);
    }

    /** Clock speed for a player standing at {@code worldX} under {@code cycle}. Pure. */
    static float speedAtWorldX(WorldGenCycle cycle, int worldX) {
        long coreStart = cycle.legacyCoreStartX(LegacyBandKind.FAR_LANDS, worldX);
        if (coreStart == WorldGenCycle.NOT_IN_LEGACY_SLOT) return 1.0f;
        long holdLen = cycle.legacyCoreLenBlocks(LegacyBandKind.FAR_LANDS, worldX);
        long local = worldX - coreStart;
        double scriptPos = holdLen <= 0L ? local : (double) local * FarLandsShift.SCRIPT_LEN / holdLen;
        return speedAt(scriptPos);
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
