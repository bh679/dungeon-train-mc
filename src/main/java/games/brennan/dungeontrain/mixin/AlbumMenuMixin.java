package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.compat.photo.album.PlayerAlbums;
import io.github.mortuusars.exposure.world.inventory.AlbumMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DT albums ({@code compat.photo.album.PlayerAlbums}) on Exposure's album menu: a player's album
 * burns the moment a photograph lands on one of its pages, and no album can be signed — a player's
 * album always carries their name. Applied on both sides; the client hides the sign button.
 */
@Mixin(value = AlbumMenu.class, remap = false)
public abstract class AlbumMenuMixin {

    @Shadow @Final protected int albumSlot;

    @Unique
    private Player dungeontrain$player;

    @Inject(method = "<init>(Lnet/minecraft/world/inventory/MenuType;ILnet/minecraft/world/entity/player/Inventory;I)V",
            at = @At("RETURN"), require = 0)
    private void dungeontrain$rememberPlayer(MenuType<?> type, int containerId, Inventory inventory, int slot, CallbackInfo ci) {
        dungeontrain$player = inventory.player;
    }

    @Inject(method = "onPhotographSlotChanged", at = @At("TAIL"), require = 0)
    private void dungeontrain$burnOnPhotoPlaced(int pageIndex, ItemStack photograph, CallbackInfo ci) {
        if (!photograph.isEmpty() && dungeontrain$player instanceof ServerPlayer player) {
            PlayerAlbums.onPhotoPlaced(player, albumSlot);
        }
    }

    @Inject(method = "canSignAlbum", at = @At("HEAD"), cancellable = true, require = 0)
    private void dungeontrain$neverSign(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
