package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.cheat.WorldSettingGate;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Holds the game mode a world is <b>opened</b> with — vanilla Open to LAN, Essential's Host button —
 * until the host confirms Free Play. See {@link WorldSettingGate}.
 *
 * <p>Target: {@code IntegratedServer#publishServer(GameType, boolean, int)} (1.21.1 Mojmap), which
 * stores the mode in {@code publishedGameType} directly rather than through
 * {@code setDefaultGameType}, so {@code MinecraftServerWorldSettingsMixin} never sees it. The world
 * still opens — failing the publish would leave the host on a "couldn't open" screen — but with the
 * mode the world already had; the held mode is written to {@code publishedGameType} once the host
 * confirms. Cheats in the same call are held separately, by {@code PlayerListCheatsMixin}.</p>
 *
 * <p>{@code publishServer} runs on the client thread, so the decision is queued to the server
 * thread; only a creative/spectator mode is ever held.</p>
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerPublishGameModeMixin {

    @Shadow @Nullable private GameType publishedGameType;

    @ModifyVariable(method = "publishServer", at = @At("HEAD"), argsOnly = true)
    private GameType dungeontrain$holdPublishedGameMode(@Nullable GameType gameMode) {
        if (gameMode != GameType.CREATIVE && gameMode != GameType.SPECTATOR) return gameMode;
        if (WorldSettingGate.isApplying()) return gameMode;
        IntegratedServer server = (IntegratedServer) (Object) this;
        GameType requested = gameMode;
        server.execute(() -> {
            if (!WorldSettingGate.holdGameMode(server, requested, () -> this.publishedGameType = requested)) {
                this.publishedGameType = requested; // nothing to ask (already Free Play, or no host yet)
            }
        });
        // Open with the world's own mode for now; the confirmed one replaces it.
        return server.getDefaultGameType();
    }
}
