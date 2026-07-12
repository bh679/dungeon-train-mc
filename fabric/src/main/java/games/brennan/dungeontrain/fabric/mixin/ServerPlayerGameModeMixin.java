package games.brennan.dungeontrain.fabric.mixin;

import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtPlayerChangeGameModeCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gap-filler for {@code DtEvents.PLAYER_CHANGE_GAMEMODE} (NeoForge
 * {@code PlayerChangeGameModeEvent}). Fabric has no game-mode-change event; fires at
 * the HEAD of {@code ServerPlayer.setGameMode} with the requested new mode. DT's sole
 * handler ({@code CheatDetectionEvents}) only observes.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerGameModeMixin {

    @Inject(method = "setGameMode", at = @At("HEAD"))
    private void dungeonTrain$changeGameMode(GameType gameType, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        for (DtPlayerChangeGameModeCallback cb : DtEvents.PLAYER_CHANGE_GAMEMODE.listeners()) {
            cb.onChangeGameMode(self, gameType);
        }
    }
}
