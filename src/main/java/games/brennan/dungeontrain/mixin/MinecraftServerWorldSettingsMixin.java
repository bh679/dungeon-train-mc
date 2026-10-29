package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.cheat.WorldSettingGate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the world's game mode and difficulty when something writes them straight to the server —
 * Essential's world panel does both — until the host has answered DT's confirmation. See
 * {@link WorldSettingGate}.
 *
 * <ul>
 *   <li>{@code setDefaultGameType(GameType)} — creative/spectator asks the Free Play confirmation.
 *       {@code /defaultgamemode} reaches here only after the command gate has already confirmed
 *       (the run is then permanently Free Play, so it passes).</li>
 *   <li>{@code setDifficulty(Difficulty, boolean)} — routes to DT's difficulty confirmation. The
 *       Options-screen button and {@code /difficulty} are asked about earlier, by
 *       {@code ServerGamePacketListenerImplDifficultyMixin} / {@code DifficultyChangeConfirmEvents};
 *       when those let a change through, {@code request} gives the same "nothing to ask" answer
 *       here, so nothing is asked twice.</li>
 * </ul>
 *
 * <p>{@code IntegratedServer#setDefaultGameType} calls {@code super} first, so this one injection
 * covers it too.</p>
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerWorldSettingsMixin {

    @Shadow public abstract void setDefaultGameType(GameType gameMode);

    @Shadow public abstract void setDifficulty(Difficulty difficulty, boolean forced);

    @Inject(method = "setDefaultGameType", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$confirmDefaultGameType(GameType gameMode, CallbackInfo ci) {
        if (WorldSettingGate.isApplying()) return;
        MinecraftServer server = (MinecraftServer) (Object) this;
        if (!WorldSettingGate.onServerThread(server, () -> setDefaultGameType(gameMode))) {
            ci.cancel();
            return;
        }
        if (WorldSettingGate.holdGameMode(server, gameMode, () -> setDefaultGameType(gameMode))) {
            ci.cancel();
        }
    }

    @Inject(method = "setDifficulty", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$confirmDifficulty(Difficulty difficulty, boolean forced, CallbackInfo ci) {
        if (WorldSettingGate.isApplying()) return;
        MinecraftServer server = (MinecraftServer) (Object) this;
        if (!WorldSettingGate.onServerThread(server, () -> setDifficulty(difficulty, forced))) {
            ci.cancel();
            return;
        }
        if (WorldSettingGate.holdDifficulty(server, difficulty, forced)) {
            ci.cancel();
        }
    }
}
