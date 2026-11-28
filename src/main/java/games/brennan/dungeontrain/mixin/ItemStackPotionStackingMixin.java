package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.StackStamp;
import games.brennan.dungeontrain.perks.effects.alchemist.PotionStacking;
import games.brennan.dungeontrain.perks.effects.tank.SoupStacking;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

/**
 * Stack Potions and Stack Soups stamp a stack size on the potions/soups they stack. Without this, a stamped stack would refuse
 * an identical potion that is not stamped yet (one just picked up, crafted, or taken from a brewing stand), since
 * the stamp makes their components differ. So two potions that differ only by that stamp count as the
 * same item for merging ({@code isSameItemSameComponents}), while {@code matches}, which menus use to
 * decide what to send the client, still sees the stamp, so a new stamp always reaches the client.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackPotionStackingMixin {

    @Inject(method = "isSameItemSameComponents", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$sameIgnoringStamp(ItemStack stack, ItemStack other, CallbackInfoReturnable<Boolean> cir) {
        if (stack == other || !dungeontrain$isStampedKind(stack) || !stack.is(other.getItem())) return;
        if (StackStamp.sameIgnoringStamp(stack, other)) cir.setReturnValue(true);
    }

    @Inject(method = "matches", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$matchesSeesStamp(ItemStack stack, ItemStack other, CallbackInfoReturnable<Boolean> cir) {
        if (stack == other || !dungeontrain$isStampedKind(stack) || !stack.is(other.getItem())) return;
        cir.setReturnValue(stack.getCount() == other.getCount()
            && Objects.equals(stack.getComponents(), other.getComponents()));
    }

    /** Items a stacking perk stamps: potions (Stack Potions) and soups (Stack Soups). */
    private static boolean dungeontrain$isStampedKind(ItemStack stack) {
        return PotionStacking.isPotion(stack) || SoupStacking.isSoup(stack);
    }
}
