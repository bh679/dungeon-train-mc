package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A template's raw NBT, from the user config dir or the bundled resource — <b>ungated</b>.
 *
 * <p>Every store already walks exactly these two tiers in its private {@code loadFromConfig} /
 * {@code loadFromResource} pair, and then validates the result against the world's
 * {@code CarriageDims} before handing it back. That gate is right for stamping — a template
 * authored at other dims would corrupt the shell — and wrong for looking at, because it hides
 * precisely the odd-sized templates a builder is most likely to be hunting for. This is the read
 * without the gate, for callers that only want to <em>show</em> the blocks.</p>
 *
 * <p>The stores each keep their own {@code rawTag} wrapper supplying their own paths, so the
 * subdirectory and resource-prefix constants stay private where they belong; this is only the
 * two-tier read they all share.</p>
 *
 * <p>Not cached. The one caller that repeats itself ({@code BuilderTileTemplates}) caches the far
 * more expensive thing it builds from the result.</p>
 */
public final class TemplateNbt {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TemplateNbt() {}

    /**
     * The template's NBT, or empty when neither tier has it.
     *
     * @param userSubdir       config subtree to search, as {@link UserContentPaths#findFile} takes
     *                         it — which covers {@code user/} and every {@code imported/<pkg>/}
     * @param fileName         the file's name including its extension
     * @param bundledResource  absolute classpath resource path for the shipped copy
     * @param what             what this is, for the log line when a read fails
     */
    public static Optional<CompoundTag> read(String userSubdir, String fileName,
                                             String bundledResource, String what) {
        Path file = UserContentPaths.findFile(userSubdir, fileName);
        if (file != null) {
            try {
                return Optional.of(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Failed to read config {} at {}: {}",
                    what, file, e.toString());
                // Fall through to the bundled copy: a truncated override shouldn't hide the
                // template that ships with the mod.
            }
        }
        try (InputStream in = TemplateNbt.class.getResourceAsStream(bundledResource)) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()));
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled {} at {}: {}",
                what, bundledResource, e.toString());
            return Optional.empty();
        }
    }
}
