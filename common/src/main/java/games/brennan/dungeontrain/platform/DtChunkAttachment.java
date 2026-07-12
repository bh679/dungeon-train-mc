package games.brennan.dungeontrain.platform;

import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Loader-neutral handle over one per-chunk persisted value — the chunk-scoped
 * sibling of {@link DtAttachment} (which models per-{@code Player} values). A
 * stand-in for NeoForge's {@code AttachmentType<T>} +
 * {@code ChunkAccess.getData/setData/hasData/removeData}.
 *
 * <p>Game logic moving to {@code :common} (e.g. the upside-down worldgen loop —
 * {@code WorldUpsideDownEvents} / {@code TrainTickEvents}) reads/writes through
 * this handle instead of calling {@code chunk.getData(TYPE.get())} directly. The
 * actual {@code AttachmentType} REGISTRATION stays in the root NeoForge module
 * (see {@code games.brennan.dungeontrain.registry.ModDataAttachments} — chunk
 * attachment-type registration is NeoForge-specific API with no Fabric
 * equivalent yet, so it is intentionally NOT routed through {@link DtRegistrar}).
 * Persistence is bit-identical to the un-seamed path: the handle wraps the exact
 * same {@code AttachmentType} instance, so the on-disk {@code .mca} NBT is
 * unchanged.</p>
 *
 * <p>The receiver is {@link ChunkAccess} (the widest type the callers use;
 * {@code LevelChunk} is a subtype), so both the generation-time marking on a
 * {@code ChunkAccess} and the apply-time clear on a {@code LevelChunk} route
 * through one handle. A future Fabric module backs this with Fabric's
 * chunk {@code AttachmentType} (Fabric API) or a custom per-chunk store.</p>
 *
 * @param <T> the attached value's type
 */
public interface DtChunkAttachment<T> {

    /** Reads the current value, creating the attachment's default if absent. */
    T get(ChunkAccess chunk);

    /** Overwrites the value. */
    void set(ChunkAccess chunk, T value);

    /** True if a value has been explicitly set (presence semantics — e.g. "needs mirror"). */
    boolean has(ChunkAccess chunk);

    /** Clears any explicitly-set value, restoring the "never touched" (default) state. */
    void remove(ChunkAccess chunk);
}
