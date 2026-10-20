package games.brennan.dungeontrain.wind;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.joml.primitives.AABBdc;

/**
 * Frostbite on top of the train in the End, from the second cycle on: out on a roof, a flatbed or a coupling pad
 * under an End sky, a player freezes the way powder snow freezes them. Inside a carriage, in a tunnel or under
 * cover they thaw. The first cycle ({@code cycleIndex == 0}) never freezes.
 *
 * <p>Drives vanilla's {@code ticksFrozen}, so the frost overlay, the slowness, the freeze damage once fully frozen
 * and leather armour's immunity ({@code canFreeze}) all come from vanilla and sync to the client on their own.
 * Vanilla takes {@value #VANILLA_THAW} off every tick out of powder snow; this adds that back plus a net rate that
 * starts gentle and tightens with the distance the train has travelled.</p>
 *
 * <p><b>Pace.</b> Where the cold first appears (the start of lap {@value #FIRST_FREEZING_CYCLE}) a player takes
 * {@value #SECONDS_TO_FREEZE_AT_START} s out on the roof to freeze solid; the gap to powder snow's
 * {@value #SECONDS_TO_FREEZE_FLOOR} s halves every {@link WorldGenCycle#period() cycle} travelled, so it creeps
 * toward that floor and never quite reaches it (cycle 2 ≈ 30 s, 3 ≈ 18.5 s, 4 ≈ 13 s, 5 ≈ 10 s, …). The counter is
 * an int, so fractional rates are applied with a stateless step on the level's game time
 * ({@link #frostThisTick}) that averages exactly the rate over time. Scaled by the End-sky intensity so it fades
 * in and out with the band's edges, netting a thaw below about two-thirds sky.</p>
 *
 * <p><b>Shelter is stricter than the wind's.</b> The cold reaches everyone at the train; only being boxed in by
 * carriage blocks on every side ({@link TrainExposure#enclosed}) saves you. A flatbed, a coupling pad, an open
 * roof or standing in a doorway all leave you freezing, and a tunnel does not help: the End has no wind to hide
 * from, and no warmth to find outside four walls.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EndFrostbite {

    /** First cycle that freezes (0-based): the second time through. */
    static final long FIRST_FREEZING_CYCLE = 1L;
    static final int VANILLA_THAW = 2;
    /** Seconds on the roof to freeze solid where the cold first appears. */
    static final double SECONDS_TO_FREEZE_AT_START = 30.0;
    /** Seconds the pace approaches but never reaches: powder snow's. */
    static final double SECONDS_TO_FREEZE_FLOOR = 7.0;
    /** Cycles travelled per halving of the gap between the current pace and the floor. */
    static final double HALF_LIFE_CYCLES = 1.0;
    /** How far past the fully-frozen mark the counter may climb, so a step out of the cold doesn't thaw at once. */
    static final int OVERSHOOT = 20;
    private static final int TICKS_PER_SECOND = 20;
    private static final double SIDE_REACH = 1.0;
    private static final double ABOVE_REACH = 4.0;

    private EndFrostbite() {}

    /**
     * Seconds to freeze solid {@code blocksIn} blocks past where the cold first appears, given {@code halfLife}
     * blocks per halving of the gap to the floor ({@code halfLife <= 0} holds the starting pace).
     */
    static double secondsToFreeze(long blocksIn, long halfLife) {
        if (halfLife <= 0L || blocksIn <= 0L) return SECONDS_TO_FREEZE_AT_START;
        double gap = SECONDS_TO_FREEZE_AT_START - SECONDS_TO_FREEZE_FLOOR;
        return SECONDS_TO_FREEZE_FLOOR + gap * Math.pow(0.5, (double) blocksIn / halfLife);
    }

    /** Net frost per tick (after vanilla's thaw) that freezes {@code ticksToFreeze} in {@link #secondsToFreeze}. */
    static double netRate(long blocksIn, long halfLife, int ticksToFreeze) {
        return ticksToFreeze / (secondsToFreeze(blocksIn, halfLife) * TICKS_PER_SECOND);
    }

    /**
     * Frost to add per tick, on average, at End-sky intensity {@code endSky} ({@code 0..1}) in cycle
     * {@code cycleIndex}, {@code blocksIn} blocks past the start of the first freezing cycle. Zero before that
     * cycle and under no End sky.
     */
    static double frostRate(double endSky, long cycleIndex, long blocksIn, long halfLife, int ticksToFreeze) {
        if (cycleIndex < FIRST_FREEZING_CYCLE || !(endSky > 0.0)) return 0.0;
        return (VANILLA_THAW + netRate(blocksIn, halfLife, ticksToFreeze)) * Math.min(1.0, endSky);
    }

    /** Whole frost to add on tick {@code gameTime} so that the ticks average {@code rate}: no per-player state. */
    static int frostThisTick(double rate, long gameTime) {
        if (!(rate > 0.0)) return 0;
        return (int) (Math.floor(gameTime * rate) - Math.floor((gameTime - 1) * rate));
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (player.isSpectator() || player.isCreative() || !player.isAlive() || !player.canFreeze()) return;
        int x = (int) Math.floor(player.getX());
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        double rate = frostRate(WindZones.endSkyAt(x), cycle.cycleIndex(x), blocksIn(cycle, x), halfLife(cycle),
                player.getTicksRequiredToFreeze());
        int frost = frostThisTick(rate, level.getGameTime());
        if (frost == 0 || !inTheCold(level, player)) return;
        int cap = player.getTicksRequiredToFreeze() + OVERSHOOT;
        player.setTicksFrozen(Math.min(cap, player.getTicksFrozen() + frost));
    }

    /** Blocks past the start of the first freezing cycle (0 before it, or when the cycle cannot say). */
    static long blocksIn(WorldGenCycle cycle, int x) {
        long start = cycle.lapStartX((int) FIRST_FREEZING_CYCLE);
        return start < 0L ? 0L : Math.max(0L, x - start);
    }

    /** Blocks per halving of the gap to the floor: {@link #HALF_LIFE_CYCLES} cycles, or 0 when the cycle is empty. */
    static long halfLife(WorldGenCycle cycle) {
        long period = cycle.period();
        return period <= 0L ? 0L : Math.round(period * HALF_LIFE_CYCLES);
    }

    /** At any carriage group near the player, and boxed in by none of them. */
    static boolean inTheCold(ServerLevel level, ServerPlayer player) {
        boolean atTrain = false;
        for (Trains.Carriage c : Trains.allCarriages(level)) {
            ManagedShip ship = c.ship();
            if (!near(ship.worldAABB(), player)) continue;
            atTrain = true;
            if (ship instanceof SableManagedShip sable && TrainExposure.enclosed(sable.subLevel(), player)) return false;
        }
        return atTrain;
    }

    private static boolean near(AABBdc bb, ServerPlayer p) {
        return p.getX() >= bb.minX() - SIDE_REACH && p.getX() <= bb.maxX() + SIDE_REACH
                && p.getZ() >= bb.minZ() - SIDE_REACH && p.getZ() <= bb.maxZ() + SIDE_REACH
                && p.getY() >= bb.minY() && p.getY() <= bb.maxY() + ABOVE_REACH;
    }
}
