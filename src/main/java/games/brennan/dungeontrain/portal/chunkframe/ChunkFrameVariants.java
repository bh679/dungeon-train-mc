package games.brennan.dungeontrain.portal.chunkframe;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * A frame's variant sidecar — {@code <name>.variants.json} beside its {@code .nbt} — holding what
 * every other template's sidecar holds: per-cell block variants, lock groups, copy rolls and spans,
 * and the editor's mirror axes.
 *
 * <p>The contents are a {@link TrackVariantBlocks}, the same schema a portal room's sidecar uses, read
 * and written through its JSON text with no track kind (so the mirror axes default to all off). Only
 * where the file lives is a frame's own business, which is all this class decides.</p>
 */
public final class ChunkFrameVariants {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String EXT = ".variants.json";

    private static final Map<String, TrackVariantBlocks> CACHE = new HashMap<>();

    private ChunkFrameVariants() {}

    /** The config file {@code name}'s sidecar is written to. */
    public static Path configPathFor(String name) {
        return UserContentPaths.activeSubDir(ChunkFrameStore.SUBDIR).resolve(name + EXT);
    }

    /** {@code name}'s sidecar — config first, then bundled, then empty. The cached instance: edits stick. */
    public static synchronized TrackVariantBlocks loadFor(String name) {
        return CACHE.computeIfAbsent(name, ChunkFrameVariants::read);
    }

    /** Persist {@code name}'s sidecar, and to the source tree when asked. */
    public static synchronized void save(String name, TrackVariantBlocks sidecar, boolean toSource) throws IOException {
        String text = sidecar.asJsonText();
        write(configPathFor(name), text);
        CACHE.put(name, sidecar);
        if (!toSource) return;
        Path source = sourcePathFor(name);
        if (source == null) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        write(source, text);
    }

    /** Replace {@code name}'s sidecar with {@code json} (undo), dropping the cached copy. */
    public static synchronized void restore(String name, String json) throws IOException {
        write(configPathFor(name), json);
        CACHE.remove(name);
    }

    /** Delete {@code name}'s sidecar files; true when any existed. */
    public static synchronized boolean delete(String name, boolean fromSource) throws IOException {
        boolean deleted = Files.deleteIfExists(configPathFor(name));
        if (fromSource) {
            Path source = sourcePathFor(name);
            if (source != null) deleted |= Files.deleteIfExists(source);
        }
        CACHE.remove(name);
        return deleted;
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static Path sourcePathFor(String name) {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        if (projectRoot == null) return null;
        Path resources = projectRoot.resolve("src/main/resources");
        if (!Files.isDirectory(resources) || !Files.isWritable(resources)) return null;
        return projectRoot.resolve("src/main/resources/data/dungeontrain/" + ChunkFrameStore.SUBDIR).resolve(name + EXT);
    }

    private static TrackVariantBlocks read(String name) {
        String json = null;
        Path cfg = UserContentPaths.findFile(ChunkFrameStore.SUBDIR, name + EXT);
        try {
            if (cfg != null) {
                json = Files.readString(cfg, StandardCharsets.UTF_8);
            } else {
                try (InputStream in = ChunkFrameVariants.class.getResourceAsStream(
                        ChunkFrameStore.RESOURCE_PREFIX + name + EXT)) {
                    if (in != null) json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read chunk frame variants for {}: {}", name, e.toString());
        }
        return TrackVariantBlocks.fromJsonText(json, null, name, ChunkFrame.SIZE);
    }
}
