package games.brennan.dungeontrain.mixin.client.windy;

import games.brennan.dungeontrain.client.wind.WindyDrive;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Brings Windy's streaks in closer while DT owns the wind. Windy places each streak {@code 40 + nextInt(30)}
 * blocks (40-69) from an anchor near the player, so most of them land far out in the fog and are never seen; DT
 * places them a bit nearer between gusts and pulls them in close as a gust arrives ({@link WindyDrive#spawnNear},
 * {@link WindyDrive#spawnSpread}), so the same number of streaks (same cost) reads as much more wind.
 *
 * <p>Applied only when {@code WindyMixinPlugin} finds {@code createShiftedParticle}; {@code require = 0} so a
 * Windy that changes the numbers just keeps its own spacing.</p>
 */
@Pseudo
@Mixin(targets = "xyz.bonfiremc.windy.particle.WindParticle", remap = false)
public abstract class WindySpawnCloserMixin {

    @ModifyConstant(method = "createShiftedParticle", constant = @Constant(intValue = 30), require = 0, remap = false)
    private static int dungeontrain$spawnSpread(int spread) {
        return WindyDrive.owns() ? WindyDrive.spawnSpread() : spread;
    }

    @ModifyConstant(method = "createShiftedParticle", constant = @Constant(intValue = 40), require = 0, remap = false)
    private static int dungeontrain$spawnNear(int near) {
        return WindyDrive.owns() ? WindyDrive.spawnNear() : near;
    }
}
