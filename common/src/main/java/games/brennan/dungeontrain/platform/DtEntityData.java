package games.brennan.dungeontrain.platform;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

import java.util.ServiceLoader;

/**
 * Loader-neutral access to an entity's persistent scratch NBT — the mod-owned
 * {@code CompoundTag} that survives save/reload and is <em>not</em> part of
 * vanilla's entity serialization.
 *
 * <p>On NeoForge this maps 1:1 onto {@code Entity.getPersistentData()} (added by
 * NeoForge's {@code IAttachmentHolder}/patched {@code Entity}); vanilla / Fabric
 * has no such method, so callers in {@code :common} go through this seam instead
 * of calling {@code entity.getPersistentData()} directly. The returned tag is the
 * live, mutable backing store — writes to it persist, exactly as the NeoForge
 * method's contract.</p>
 *
 * <p>Resolved once via {@link ServiceLoader}; the NeoForge module registers its
 * implementation via
 * {@code META-INF/services/games.brennan.dungeontrain.platform.DtEntityData} in
 * its own resources. A future Fabric module registers an equivalent impl over a
 * custom data attachment / component. Same holder pattern as
 * {@code games.brennan.dungeontrain.net.platform.DtNetSender}.</p>
 */
public interface DtEntityData {

    /**
     * The entity's persistent, mod-owned scratch tag. The instance is live and
     * mutable — mutating it (e.g. {@code putBoolean}) persists on the entity.
     */
    CompoundTag getPersistentData(Entity entity);

    /** The loader-provided singleton, resolved lazily on first use. */
    static DtEntityData get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        static final DtEntityData INSTANCE = load();

        private Holder() {}

        private static DtEntityData load() {
            ServiceLoader<DtEntityData> loader =
                ServiceLoader.load(DtEntityData.class, DtEntityData.class.getClassLoader());
            for (DtEntityData impl : loader) {
                return impl;
            }
            throw new IllegalStateException(
                "No DtEntityData implementation found via ServiceLoader — the loader module "
                    + "must provide META-INF/services/" + DtEntityData.class.getName());
        }
    }
}
