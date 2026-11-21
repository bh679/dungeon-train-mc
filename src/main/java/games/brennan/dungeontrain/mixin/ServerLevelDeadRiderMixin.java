package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.ship.sable.DeadRiderAnchors;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A level whose only player is dead keeps ticking its entities until they respawn.
 *
 * <p>{@code ServerLevel.tick} counts ticks with no player in {@code players} (no forced chunks
 * either) and after 300 of them skips entity ticking for the whole level. Vanilla removes a dead
 * player from that list at death tick 20, so a lone rider on the death screen watches every mob on
 * the train stop in its tracks exactly 16 seconds after dying, while the carriages (moved by
 * Sable's physics step, outside that gate) roll on. Resetting the empty clock while a
 * {@link DeadRiderAnchors dead anchor} is in the level keeps the mobs moving as they did a second
 * before death. Vanilla's own increment runs after this, so the clock sits at one, never at 300.</p>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelDeadRiderMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void dungeontrain$keepTickingForDeadRiders(CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (!DeadRiderAnchors.deadIn(level).isEmpty()) level.resetEmptyTime();
    }
}
