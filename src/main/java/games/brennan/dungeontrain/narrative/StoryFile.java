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
 * <p>{@code deferred} holds a series back: a lectern never starts a deferred series while any
 * ordinary series is still unfinished, so the deferred ones are what is left once the rest of the
 * corpus has been read. Once everything is complete they are ordinary again — the post-completion
 * re-read pool treats every story alike.</p>
 *
 * <p>Immutable. The {@code letters} list preserves source order.</p>
 */
public record StoryFile(
    ResourceLocation id,
    String character,
    String story,
    boolean deferred,
    List<Letter> letters
) {
    public StoryFile {
        letters = List.copyOf(letters);
    }

    /**
     * A copy of this story carrying {@code newDeferred}. Used by {@link StoryRegistry} to keep the
     * ENGLISH base file's flag when a localized copy has replaced the prose — the hold-back tier is
     * tuning, not translated content.
     */
    public StoryFile withDeferred(boolean newDeferred) {
        return new StoryFile(id, character, story, newDeferred, letters);
    }

    /** Find a Letter by its 1-based index. */
    public Optional<Letter> letterByIndex(int index) {
        for (Letter l : letters) {
            if (l.index() == index) return Optional.of(l);
        }
        return Optional.empty();
    }
}
