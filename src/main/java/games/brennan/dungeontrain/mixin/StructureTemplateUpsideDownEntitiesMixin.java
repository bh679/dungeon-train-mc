package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.worldgen.UpsideDownSpawnerStructures;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Leaves a structure's mobs out of the upside-down band — see
 * {@link UpsideDownSpawnerStructures#dropsTemplateEntity}. Wraps the entity creation in
 * {@code addEntitiesToWorld} (NeoForge's name for vanilla's {@code placeEntities}); its NBT already
 * carries the placed position, so an emptied result skips the whole spawn, finalize included.
 *
 * <p>Worldgen only ({@link WorldGenRegion}): DT's own template stamping on a live level — carriages,
 * portal rooms — places its occupants deliberately and is never touched.</p>
 */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateUpsideDownEntitiesMixin {

    @WrapOperation(
        method = "addEntitiesToWorld",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;createEntityIgnoreException(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/Optional;"))
    private Optional<Entity> dungeontrain$dropUpsideDownResidents(ServerLevelAccessor accessor, CompoundTag tag,
                                                                  Operation<Optional<Entity>> original) {
        Optional<Entity> entity = original.call(accessor, tag);
        if (entity.isPresent() && accessor instanceof WorldGenRegion region
                && UpsideDownSpawnerStructures.dropsTemplateEntity(region.getLevel(), entity.get())) {
            return Optional.empty();
        }
        return entity;
    }
}
