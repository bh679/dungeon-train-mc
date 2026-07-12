package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Loader-neutral form of NeoForge's {@code LivingEquipmentChangeEvent}: fired on
 * the server thread when a living entity's equipment in a slot changes. Not
 * cancellable; read-only. DT's single handler was NORMAL priority.
 *
 * @param entity the entity whose equipment changed (matches {@code getEntity()})
 * @param slot   the affected slot (matches {@code getSlot()})
 * @param to     the new stack in the slot (matches {@code getTo()})
 */
@FunctionalInterface
public interface DtLivingEquipmentChangeCallback {
    void onEquipmentChange(LivingEntity entity, EquipmentSlot slot, ItemStack to);
}
