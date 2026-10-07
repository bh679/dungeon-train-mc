package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.compat.photo.album.PlayerAlbums;
import io.github.mortuusars.exposure.world.item.AlbumItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player's album opens onto their one album: its pages are refilled from the world's copy just
 * before Exposure builds the menu from them ({@code PlayerAlbums#beforeOpen}). Another player's album
 * is opened as a read-only found album instead.
 */
@Mixin(value = AlbumItem.class, remap = false)
public abstract class AlbumItemOpenMixin {

    @Inject(method = "open", at = @At("HEAD"), cancellable = true, require = 0)
    private void dungeontrain$refreshBeforeOpen(ServerPlayer player, ItemStack stack, int slot, CallbackInfo ci) {
        if (!PlayerAlbums.beforeOpen(player, stack, slot)) ci.cancel();
    }
}
