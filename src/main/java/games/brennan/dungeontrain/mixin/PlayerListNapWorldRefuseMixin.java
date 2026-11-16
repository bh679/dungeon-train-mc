package games.brennan.dungeontrain.mixin;

import com.mojang.authlib.GameProfile;
import games.brennan.dungeontrain.nap.NapWake;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/**
 * A singleplayer world its owner napped in can't be played while the nap is pending: the life is in
 * the nap, and the nap always wins. Refused through vanilla's own login check, before the player is
 * loaded from disk, so nothing in the world or the nap changes; the client goes back to the title
 * screen, which deletes the world. The server stops with it. See {@link NapWake#isNappedWorld}.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListNapWorldRefuseMixin {

    @Shadow @Final private MinecraftServer server;

    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$refuseNappedWorld(SocketAddress address, GameProfile profile,
                                                CallbackInfoReturnable<Component> cir) {
        if (cir.getReturnValue() != null) return;
        if (NapWake.isNappedWorld(server, profile)) {
            cir.setReturnValue(Component.translatable("disconnect.dungeontrain.nap.world_napped"));
            // Nobody else can play this world, so stop its server too. Left running, it keeps its
            // session lock (the title sweep can't delete the world) and its Sable physics, which
            // wedges the next world's server at 33% when it starts beside it.
            server.halt(false);
        }
    }
}
