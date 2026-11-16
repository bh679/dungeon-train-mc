package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.nap.NapWake;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Wakes a nap: a player joining a fresh run world with a nap pending is loaded from the nap, through
 * vanilla's own {@code player.load}, instead of starting empty. See {@link NapWake#tagFor}; every
 * other login is untouched.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListNapLoadMixin {

    @Shadow @Final private MinecraftServer server;

    @Inject(method = "load", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$wakeNap(ServerPlayer player, CallbackInfoReturnable<Optional<CompoundTag>> cir) {
        Optional<CompoundTag> nap = NapWake.tagFor(server, player);
        if (nap.isEmpty()) return;
        player.load(nap.get());
        NapWake.restoreRunState(player);
        cir.setReturnValue(nap);
    }
}
