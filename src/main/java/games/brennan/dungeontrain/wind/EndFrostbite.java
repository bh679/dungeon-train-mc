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
 * Vanilla takes {@value #VANILLA_THAW} off every tick out of powder snow, so adding {@value #FROST_AT_FULL_SKY}
 * nets one a tick: fully frozen in about 7 s, powder snow's pace. Scaled by the End-sky intensity so it fades
 * in and out with the band's edges.</p>
 *
 * <p>Exposure reuses the wind's tests with the same rule as {@code WindServer#nearVerdict} (shelter in any nearby
 * group wins), minus the lee scan: the End has no wind to hide from.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EndFrostbite {

    /** First cycle that freezes (0-based): the second time through. */
    static final long FIRST_FREEZING_CYCLE = 1L;
    static final int VANILLA_THAW = 2;
    static final int FROST_AT_FULL_SKY = 3;
    /** How far past the fully-frozen mark the counter may climb, so a step out of the cold doesn't thaw at once. */
    static final int OVERSHOOT = 20;
    private static final double SIDE_REACH = 1.0;
    private static final double ABOVE_REACH = 4.0;

    private EndFrostbite() {}

    /** Frost added this tick at End-sky intensity {@code endSky} ({@code 0..1}) in cycle {@code cycleIndex}. */
    static int frostPerTick(double endSky, long cycleIndex) {
        if (cycleIndex < FIRST_FREEZING_CYCLE || !(endSky > 0.0)) return 0;
        return (int) Math.round(FROST_AT_FULL_SKY * Math.min(1.0, endSky));
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (player.isSpectator() || player.isCreative() || !player.isAlive() || !player.canFreeze()) return;
        int x = (int) Math.floor(player.getX());
        int frost = frostPerTick(WindZones.endSkyAt(x), WorldGenCycle.fromConfig().cycleIndex(x));
        if (frost == 0 || !onTopOfTrain(level, player)) return;
        int cap = player.getTicksRequiredToFreeze() + OVERSHOOT;
        player.setTicksFrozen(Math.min(cap, player.getTicksFrozen() + frost));
    }

    /** Out in the open on any carriage group near the player, and sheltered by none of them. */
    static boolean onTopOfTrain(ServerLevel level, ServerPlayer player) {
        boolean exposed = false;
        for (Trains.Carriage c : Trains.allCarriages(level)) {
            ManagedShip ship = c.ship();
            if (!near(ship.worldAABB(), player)) continue;
            if (FarWind.classify(c, player) == FarWind.Verdict.SHELTERED) return false;
            if (!(ship instanceof SableManagedShip sable) || sable.subLevel() == null) continue;
            TrainExposure.Result r = TrainExposure.classify(sable.subLevel(), player, 0.0, 0.0);
            if (r == TrainExposure.Result.EXPOSED) exposed = true;
            else if (r != TrainExposure.Result.NOT_ON_TRAIN) return false;
        }
        return exposed;
    }

    private static boolean near(AABBdc bb, ServerPlayer p) {
        return p.getX() >= bb.minX() - SIDE_REACH && p.getX() <= bb.maxX() + SIDE_REACH
                && p.getZ() >= bb.minZ() - SIDE_REACH && p.getZ() <= bb.maxZ() + SIDE_REACH
                && p.getY() >= bb.minY() && p.getY() <= bb.maxY() + ABOVE_REACH;
    }
}
