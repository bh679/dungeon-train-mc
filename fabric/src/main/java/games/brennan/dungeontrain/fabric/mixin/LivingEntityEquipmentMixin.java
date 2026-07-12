package games.brennan.dungeontrain.fabric.mixin;

import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtLivingEquipmentChangeCallback;
import java.util.Map;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gap-filler for {@code DtEvents.LIVING_EQUIPMENT_CHANGE} (NeoForge
 * {@code LivingEquipmentChangeEvent}). Fabric has no equipment-change event; fires
 * per changed slot at the HEAD of {@code LivingEntity.handleEquipmentChanges} — the
 * same detection point NeoForge hooks. Carries the NEW stack per slot ({@code to}).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityEquipmentMixin {

    @Inject(method = "handleEquipmentChanges", at = @At("HEAD"))
    private void dungeonTrain$equipmentChange(Map<EquipmentSlot, ItemStack> equipments, CallbackInfo ci) {
        if (DtEvents.LIVING_EQUIPMENT_CHANGE.isEmpty()) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        for (Map.Entry<EquipmentSlot, ItemStack> entry : equipments.entrySet()) {
            for (DtLivingEquipmentChangeCallback cb : DtEvents.LIVING_EQUIPMENT_CHANGE.listeners()) {
                cb.onEquipmentChange(self, entry.getKey(), entry.getValue());
            }
        }
    }
}
