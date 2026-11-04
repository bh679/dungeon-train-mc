package games.brennan.dungeontrain.editor;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * How a {@link ThreeTierStore} turns bytes into a value and back. Two live in {@link StoreCodecs}:
 * pretty-printed JSON for sidecars, and compressed NBT loaded into a {@code StructureTemplate} for
 * templates — the latter needs the world's block registry, which arrives per read as a
 * {@link DecodeContext}.
 */
public interface StoreCodec<V> {

    /**
     * Decode one value. Returning {@code null} means "present but unusable, already logged" — the
     * store treats it as absent at that tier. Throw for an I/O failure; the store logs it.
     *
     * @param what a label for log lines, e.g. {@code "contents group foo"}
     */
    V decode(InputStream in, String what, DecodeContext ctx) throws IOException;

    /** Write {@code value} to {@code file}; the parent directory already exists. */
    void encode(V value, Path file) throws IOException;

    /** What a decode may need beyond the bytes: the level, for NBT codecs. {@link #NONE} for JSON. */
    record DecodeContext(ServerLevel level) {
        public static final DecodeContext NONE = new DecodeContext(null);

        public static DecodeContext of(ServerLevel level) {
            return new DecodeContext(level);
        }

        public HolderGetter<Block> blocks() {
            if (level == null) {
                throw new IllegalStateException("This store's codec needs a ServerLevel — call get(key, level).");
            }
            return level.registryAccess().lookupOrThrow(Registries.BLOCK);
        }
    }
}
