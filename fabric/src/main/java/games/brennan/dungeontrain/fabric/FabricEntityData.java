package games.brennan.dungeontrain.fabric;

import games.brennan.dungeontrain.platform.DtEntityData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/**
 * Fabric-backed {@link DtEntityData}, registered via {@code META-INF/services}.
 * Delegates to {@link DtPersistentDataAccess} (mixed onto every {@code Entity} by
 * {@code EntityPersistentDataMixin}) — Fabric has no {@code Entity.getPersistentData()},
 * so the persistent scratch NBT is carried in a mixin-added field persisted under a
 * {@code dungeontrain:persistent} subtag. NBT layout differs from NeoForge's
 * {@code NeoForgeData} subtag (cross-loader world portability is out of scope; the
 * store is self-consistent within a Fabric world).
 */
public final class FabricEntityData implements DtEntityData {

    @Override
    public CompoundTag getPersistentData(Entity entity) {
        return ((DtPersistentDataAccess) entity).dungeonTrain$getPersistentData();
    }
}
