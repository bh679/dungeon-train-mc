package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.echo.EchoDropCredit;
import games.brennan.dungeontrain.compat.PlayerMobDrops;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets {@link EchoDropCredit} write an echo's credit line into an item just before the echo drops it.
 *
 * <p>{@code Entity.spawnAtLocation(ItemStack, float)} is the one funnel every PlayerMob drop goes
 * through: vanilla's {@code Mob.dropCustomDeathLoot} calls it for each equipped slot, and PlayerMob's
 * own {@code dropAtLocation} (backpack spill, gear swaps) wraps it. The HEAD inject edits the stack in
 * place before the {@code ItemEntity} is built, so the dropped item carries the line from its first
 * tick. Anything that is not a PlayerMob returns at the {@code instanceof} check.</p>
 */
@Mixin(Entity.class)
public abstract class EchoDropCreditMixin {

    @Inject(
            method = "spawnAtLocation(Lnet/minecraft/world/item/ItemStack;F)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"))
    private void dungeontrain$creditEchoDrop(ItemStack stack, float yOffset,
                                             CallbackInfoReturnable<ItemEntity> cir) {
        if ((Object) this instanceof PlayerMobEntity mob) {
            EchoDropCredit.onDrop(mob, stack);
            // The join event fires inside spawnAtLocation: let it know a PlayerMob is the one dropping,
            // so a disposable-camera photograph among its death loot / spill lands instead of igniting.
            PlayerMobDrops.begin();
        }
    }

    @Inject(
            method = "spawnAtLocation(Lnet/minecraft/world/item/ItemStack;F)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("RETURN"))
    private void dungeontrain$endPlayerMobDrop(ItemStack stack, float yOffset,
                                               CallbackInfoReturnable<ItemEntity> cir) {
        if ((Object) this instanceof PlayerMobEntity) {
            PlayerMobDrops.end();
        }
    }
}
