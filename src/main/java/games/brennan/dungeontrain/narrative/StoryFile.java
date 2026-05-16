package games.brennan.dungeontrain.narrative;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * Parsed narrative story loaded from a {@code .json} file under
 * {@code data/<modid>/narratives/stories/}. Each file is one story containing
 * one or more {@link Letter}s; the runtime renders each Letter as a single
 * Minecraft signed book.
 *
 * <p>Immutable. The {@code letters} list preserves source order.</p>
 */
public record StoryFile(
    ResourceLocation id,
    String character,
    String story,
    List<Letter> letters
) {
    public StoryFile {
        letters = List.copyOf(letters);
    }

    /** Find a Letter by its 1-based index. */
    public Optional<Letter> letterByIndex(int index) {
        for (Letter l : letters) {
            if (l.index() == index) return Optional.of(l);
        }
        return Optional.empty();
    }
}
