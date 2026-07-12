package games.brennan.dungeontrain.platform;

import net.minecraft.world.entity.player.Player;

/**
 * Loader-neutral handle over one per-player persisted value — a stand-in for
 * NeoForge's {@code AttachmentType<T>} + {@code Player.getData/setData/hasData}.
 * Game logic (moving to {@code :common} over the Fabric port) reads/writes
 * through this handle instead of calling {@code player.getData(TYPE.get())}
 * directly; the actual {@code AttachmentType} REGISTRATION stays in the root
 * NeoForge module (see {@code games.brennan.dungeontrain.registry.ModDataAttachments}
 * — attachment-type registration is NeoForge-specific API with no Fabric
 * equivalent yet, so it is intentionally NOT routed through {@link DtRegistrar}).
 *
 * <p>Root implementations wrap a NeoForge {@code Supplier<AttachmentType<T>>}
 * and delegate straight to {@code player.getData/setData/hasData}. A future
 * Fabric module would back this with Fabric's {@code AttachmentType} (Fabric
 * API) or a custom per-player capability store.</p>
 *
 * @param <T> the attached value's type
 */
public interface DtAttachment<T> {

    /** Reads the current value, creating the attachment's default if absent. */
    T get(Player player);

    /** Overwrites the value. */
    void set(Player player, T value);

    /** True if a value has been explicitly set (distinguishing "never touched" from "explicit default"). */
    boolean has(Player player);
}
