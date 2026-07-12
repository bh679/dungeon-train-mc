package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.platform.DtEntityData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/**
 * NeoForge-backed {@link DtEntityData}, registered for {@link
 * java.util.ServiceLoader} lookup via {@code META-INF/services} in this
 * module's resources. Pure delegation to NeoForge's patched
 * {@code Entity.getPersistentData()} — no behavior change from the pre-seam
 * callsites.
 */
public final class NeoForgeEntityData implements DtEntityData {

    @Override
    public CompoundTag getPersistentData(Entity entity) {
        return entity.getPersistentData();
    }
}
