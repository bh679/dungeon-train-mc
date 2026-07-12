package games.brennan.dungeontrain.fabric;

import net.minecraft.nbt.CompoundTag;

/**
 * Duck-type interface implemented (by mixin) on every {@code Entity} to give it a
 * persistent, mod-owned scratch {@link CompoundTag} — the Fabric stand-in for
 * NeoForge's patched {@code Entity.getPersistentData()}. Backed by
 * {@code EntityPersistentDataMixin}, which persists the tag through
 * {@code Entity.saveWithoutId}/{@code load} under a {@code dungeontrain:persistent}
 * subtag. {@code FabricEntityData} casts to this to serve {@code DtEntityData}.
 */
public interface DtPersistentDataAccess {

    /** The live, mutable persistent scratch tag (writes to it persist on save). */
    CompoundTag dungeonTrain$getPersistentData();
}
