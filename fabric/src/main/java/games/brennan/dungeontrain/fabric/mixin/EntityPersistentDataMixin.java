package games.brennan.dungeontrain.fabric.mixin;

import games.brennan.dungeontrain.fabric.DtPersistentDataAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gap-filler: gives every {@code Entity} a persistent, mod-owned scratch
 * {@link CompoundTag} (Fabric has no {@code Entity.getPersistentData()}). The tag
 * is written under a {@code dungeontrain:persistent} subtag in
 * {@code Entity.saveWithoutId} and re-read in {@code Entity.load}, so it survives
 * save/reload — the live-mutable contract {@code DtEntityData} requires.
 */
@Mixin(Entity.class)
public abstract class EntityPersistentDataMixin implements DtPersistentDataAccess {

    @Unique
    private static final String DUNGEONTRAIN$KEY = "dungeontrain:persistent";

    @Unique
    private final CompoundTag dungeonTrain$persistentData = new CompoundTag();

    @Override
    public CompoundTag dungeonTrain$getPersistentData() {
        return this.dungeonTrain$persistentData;
    }

    @Inject(method = "saveWithoutId", at = @At("RETURN"))
    private void dungeonTrain$save(CompoundTag compound, CallbackInfoReturnable<CompoundTag> cir) {
        if (!this.dungeonTrain$persistentData.isEmpty()) {
            cir.getReturnValue().put(DUNGEONTRAIN$KEY, this.dungeonTrain$persistentData.copy());
        }
    }

    @Inject(method = "load", at = @At("TAIL"))
    private void dungeonTrain$load(CompoundTag compound, CallbackInfo ci) {
        if (compound.contains(DUNGEONTRAIN$KEY, 10)) { // 10 = CompoundTag
            this.dungeonTrain$persistentData.merge(compound.getCompound(DUNGEONTRAIN$KEY));
        }
    }
}
