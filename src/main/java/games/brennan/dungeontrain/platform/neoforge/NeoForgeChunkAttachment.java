package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.platform.DtChunkAttachment;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.neoforge.attachment.AttachmentType;

import java.util.function.Supplier;

/**
 * NeoForge {@link DtChunkAttachment} impl: a thin wrapper over a
 * {@code Supplier<AttachmentType<T>>} (as returned by
 * {@code games.brennan.dungeontrain.registry.ModDataAttachments}) that delegates
 * straight to {@code ChunkAccess.getData/setData/hasData/removeData}.
 *
 * <p>The chunk-scoped sibling of {@link NeoForgeAttachment}. The
 * {@code AttachmentType} REGISTRATION stays in {@code ModDataAttachments}
 * (root-only, NeoForge-specific); this class only adapts the loader-neutral
 * {@link DtChunkAttachment} read/write contract onto it, so converted game logic
 * in {@code :common} never touches {@code AttachmentType} or
 * {@code ChunkAccess.getData} directly. Because it resolves the identical
 * {@code AttachmentType} instance, the persisted {@code .mca} NBT is byte-for-byte
 * unchanged from the pre-seam call sites.</p>
 */
public final class NeoForgeChunkAttachment<T> implements DtChunkAttachment<T> {

    private final Supplier<AttachmentType<T>> type;

    public NeoForgeChunkAttachment(Supplier<AttachmentType<T>> type) {
        this.type = type;
    }

    @Override
    public T get(ChunkAccess chunk) {
        return chunk.getData(type.get());
    }

    @Override
    public void set(ChunkAccess chunk, T value) {
        chunk.setData(type.get(), value);
    }

    @Override
    public boolean has(ChunkAccess chunk) {
        return chunk.hasData(type.get());
    }

    @Override
    public void remove(ChunkAccess chunk) {
        chunk.removeData(type.get());
    }
}
