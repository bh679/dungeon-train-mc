package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.editor.ContainerContentsRoller;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * Teaches {@link DecoratedPotBlockEntity} to round-trip the
 * {@link ContainerContentsRoller#NBT_POT_LOOT} field that the roller writes
 * when a vase's content pool rolls an item. Vanilla's {@code saveAdditional}
 * only emits {@code sherds}, so without this hook the rolled item is silently
 * stripped on the next BE save (chunk unload, world save, etc.) and the
 * vase-break handler finds nothing to drop.
 *
 * <p>Forward-compat: if a future Minecraft version gives decorated_pot a
 * native {@code Container}, the roller no longer writes
 * {@code dt_pot_loot} and these hooks become no-ops.
 */
@Mixin(DecoratedPotBlockEntity.class)
public abstract class DecoratedPotBlockEntityPotLootMixin {

    @Unique
    @Nullable
    private CompoundTag dungeontrain$potLoot;

    @Inject(method = "load", at = @At("TAIL"))
    private void dungeontrain$loadPotLoot(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains(ContainerContentsRoller.NBT_POT_LOOT, Tag.TAG_COMPOUND)) {
            this.dungeontrain$potLoot = tag.getCompound(ContainerContentsRoller.NBT_POT_LOOT).copy();
        } else {
            this.dungeontrain$potLoot = null;
        }
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void dungeontrain$savePotLoot(CompoundTag tag, CallbackInfo ci) {
        if (this.dungeontrain$potLoot != null && !this.dungeontrain$potLoot.isEmpty()) {
            tag.put(ContainerContentsRoller.NBT_POT_LOOT, this.dungeontrain$potLoot.copy());
        }
    }
}
