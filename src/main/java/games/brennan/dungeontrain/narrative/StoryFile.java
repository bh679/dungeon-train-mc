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
 * <p>{@code after} chains a series behind another: a lectern never starts it while the named
 * prerequisite (a story basename) is loaded and unfinished. {@code null} = no prerequisite. A chain of
 * {@code after} links therefore occupies one slot in the start pick — only the member the world is up to
 * is eligible.</p>
 *
 * <p>{@code weight} scales how likely a series is to be the one a lectern starts, among those eligible
 * in its tier ({@code 1} baseline, {@code 0} never picked while anything else is eligible). Like
 * {@code deferred} it only shapes the first-read ORDER; re-reads ignore it.</p>
 *
 * <p>Immutable. The {@code letters} list preserves source order.</p>
 */
public record StoryFile(
    ResourceLocation id,
    String character,
    String story,
    boolean deferred,
    String after,
    double weight,
    List<Letter> letters
) {
    public StoryFile {
        letters = List.copyOf(letters);
        after = (after == null || after.isBlank()) ? null : after;
        weight = Double.isFinite(weight) && weight >= 0 ? weight : 1.0;
    }

    /** Serving-order tuning carried by a story file — none of it is translated content. */
    public record Tuning(boolean deferred, String after, double weight) {}

    public Tuning tuning() {
        return new Tuning(deferred, after, weight);
    }

    /**
     * A copy of this story carrying {@code newTuning}. Used by {@link StoryRegistry} to keep the
     * ENGLISH base file's tuning when a localized copy has replaced the prose — hold-back, chain and
     * weight are tuning, not translated content.
     */
    public StoryFile withTuning(Tuning newTuning) {
        return new StoryFile(id, character, story,
            newTuning.deferred(), newTuning.after(), newTuning.weight(), letters);
    }

    /** The story's basename — the path tail progress and {@code after} links are keyed by. */
    public String basename() {
        String path = id.getPath();
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Find a Letter by its 1-based index. */
    public Optional<Letter> letterByIndex(int index) {
        for (Letter l : letters) {
            if (l.index() == index) return Optional.of(l);
        }
        return Optional.empty();
    }
}
