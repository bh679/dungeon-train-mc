package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.train.NearbyCarriages;
import games.brennan.playermob.neoforge.compat.DungeonTrainEnvironment;

/**
 * Hands PlayerMob's Dungeon Train environment DT's own carriage lookup
 * ({@link NearbyCarriages#at}, served from the per-tick carriage index), through PlayerMob's
 * {@code DungeonTrainEnvironment.installCarriageLocator} seam (playermob 0.132.0+).
 *
 * <p>Every per-tick train question a PlayerMob goal asks ({@code isOnTrain}, {@code sharesBlockFrame},
 * {@code toBlockFrame}, {@code fromBlockFrame}, {@code carriageIndex}...) resolves "which carriage is
 * this mob on?". PlayerMob's built-in answer walks every carriage, so it cost echoes x carriages per
 * tick; the index answers from one walk per tick. PlayerMob also remembers the answer for the rest of
 * the tick while the mob has not moved.</p>
 *
 * <p>Mirrors {@link PlayerMobRigSurfaceBridge}: the hard reference to PlayerMob lives only inside
 * {@link #install()}, and the caller ({@code DungeonTrain.commonSetup}) gates on
 * {@code ModList.isLoaded} and catches {@link Throwable}, so a PlayerMob predating the seam keeps its
 * own walk.</p>
 */
public final class PlayerMobCarriageLocatorBridge {

    private PlayerMobCarriageLocatorBridge() {}

    public static void install() {
        DungeonTrainEnvironment.installCarriageLocator(NearbyCarriages::at);
    }
}
