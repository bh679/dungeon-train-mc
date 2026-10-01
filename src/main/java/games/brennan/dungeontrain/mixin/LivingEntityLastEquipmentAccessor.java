package games.brennan.dungeontrain.mixin;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link LivingEntity}'s private last-tick equipment snapshot — what each hand and armour slot
 * held when equipment changes were last collected. {@code echo.EchoDropCredit} uses it to recognise a
 * piece an echo dropped the same tick it swapped to better gear, when the slot already holds the new one.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityLastEquipmentAccessor {

    @Invoker("getLastHandItem")
    ItemStack dungeontrain$getLastHandItem(EquipmentSlot slot);

    @Invoker("getLastArmorItem")
    ItemStack dungeontrain$getLastArmorItem(EquipmentSlot slot);
}
