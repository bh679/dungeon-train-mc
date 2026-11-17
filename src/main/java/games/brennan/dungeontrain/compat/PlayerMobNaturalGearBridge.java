package games.brennan.dungeontrain.compat;

import games.brennan.playermob.entity.NaturalSpawnGear;

/**
 * Turns off PlayerMob's wild-spawn items ({@link NaturalSpawnGear}) in a Dungeon Train game. On its own,
 * PlayerMob hands a naturally spawning PlayerMob a few items at the nearest player's gear tier; Dungeon
 * Train gears its PlayerMobs by difficulty tier instead ({@code difficulty.DifficultyApplier}), so the two
 * must not stack.
 *
 * <p>Mirrors {@link PlayerMobRigSurfaceBridge}: the hard reference lives only inside {@link #install()},
 * and the caller ({@code DungeonTrain.commonSetup}) gates on {@code ModList.isLoaded} and catches
 * {@link Throwable}, so a PlayerMob build predating the seam (which has no wild-spawn items) is fine.</p>
 */
public final class PlayerMobNaturalGearBridge {

    private PlayerMobNaturalGearBridge() {}

    public static void install() {
        NaturalSpawnGear.setEnabled(false);
    }
}
