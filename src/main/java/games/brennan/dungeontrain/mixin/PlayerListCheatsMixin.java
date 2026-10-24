package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.cheat.WorldSettingGate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds cheats being switched on for everyone until the host confirms Free Play — see
 * {@link WorldSettingGate}.
 *
 * <p>Target: {@code PlayerList#setAllowCommandsForAllPlayers(boolean)} (1.21.1 Mojmap). Vanilla's only
 * caller is {@code IntegratedServer#publishServer} (Open to LAN with "Allow Cheats"); Essential's world
 * panel calls it directly for its Cheats toggle and when hosting with cheats on. Turning cheats
 * <em>off</em> is never held.</p>
 */
@Mixin(PlayerList.class)
public abstract class PlayerListCheatsMixin {

    @Shadow public abstract MinecraftServer getServer();

    @Shadow public abstract void setAllowCommandsForAllPlayers(boolean allow);

    @Inject(method = "setAllowCommandsForAllPlayers", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$confirmCheats(boolean allow, CallbackInfo ci) {
        if (!allow || WorldSettingGate.isApplying()) return;
        MinecraftServer server = getServer();
        if (server == null) return;
        if (!WorldSettingGate.onServerThread(server, () -> setAllowCommandsForAllPlayers(true))) {
            ci.cancel(); // decided again on the server thread
            return;
        }
        if (WorldSettingGate.holdCheats(server, true, () -> setAllowCommandsForAllPlayers(true))) {
            ci.cancel();
        }
    }
}
