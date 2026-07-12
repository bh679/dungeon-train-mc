package games.brennan.dungeontrain.fabric;

import games.brennan.dungeontrain.platform.DtChunkAttachment;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Fabric {@link DtChunkAttachment} impl over a fabric-data-attachment
 * {@link AttachmentType} on {@code ChunkAccess}. {@code get} uses
 * {@code getAttachedOrElse} (does NOT attach) so the presence semantics
 * ({@code has} == "needs mirror") the upside-down drain relies on are preserved;
 * {@code set}/{@code has}/{@code remove} map straight through.
 */
public final class FabricChunkAttachment<T> implements DtChunkAttachment<T> {

    private final AttachmentType<T> type;
    private final T defaultValue;

    public FabricChunkAttachment(AttachmentType<T> type, T defaultValue) {
        this.type = type;
        this.defaultValue = defaultValue;
    }

    @Override
    public T get(ChunkAccess chunk) {
        return chunk.getAttachedOrElse(type, defaultValue);
    }

    @Override
    public void set(ChunkAccess chunk, T value) {
        chunk.setAttached(type, value);
    }

    @Override
    public boolean has(ChunkAccess chunk) {
        return chunk.hasAttached(type);
    }

    @Override
    public void remove(ChunkAccess chunk) {
        chunk.removeAttached(type);
    }
}
