package games.brennan.dungeontrain.fabric.mixin;

import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtPlayerTickCallback;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gap-filler for {@code DtEvents.PLAYER_TICK} (NeoForge {@code PlayerTickEvent.Post}).
 * Fabric has no player-tick event; fires at the TAIL of {@code Player.tick} (both
 * sides, matching NeoForge — handlers self-filter to server).
 */
@Mixin(Player.class)
public abstract class PlayerTickMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void dungeonTrain$playerTickPost(CallbackInfo ci) {
        if (DtEvents.PLAYER_TICK.isEmpty()) {
            return;
        }
        Player self = (Player) (Object) this;
        for (DtPlayerTickCallback cb : DtEvents.PLAYER_TICK.listeners()) {
            cb.onPlayerTick(self);
        }
    }
}
