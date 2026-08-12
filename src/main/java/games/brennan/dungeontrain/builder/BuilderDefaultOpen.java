package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.variant.TrackKind;

import java.util.List;
import java.util.Optional;

/**
 * What a Train Builder menu tile opens into.
 *
 * <p>A tile on the picker names a <em>kind of work</em>, not a template — so for as long as the
 * world it launched was stamped straight from {@code BuilderMode}, it came up holding the bare
 * default shell and, for the two track modes, nothing at all. You landed in a sandbox and had to
 * go to Open before there was anything to look at.</p>
 *
 * <p>This is the missing half of that click: the template each mode should arrive holding, phrased
 * as the same {@link BuilderOpenRequest} the Open screen sends, so the tile goes through
 * {@code BuilderWorldSetup.applyOpen} and gets its resolve-before-you-stamp contract rather than a
 * second, more forgiving stamp path of its own.</p>
 *
 * <p>Pure, with the registry contents passed in — the same reason {@link BuilderOpenOptions} and
 * {@link BuilderNewOptions} are: the table is small, the consequences of getting it wrong are a
 * world stamped with the wrong thing, and neither is worth a game context to test.</p>
 */
public final class BuilderDefaultOpen {

    private BuilderDefaultOpen() {}

    /**
     * The template this mode's tile opens, or empty when nothing is registered to open.
     *
     * <p>Empty is a real answer rather than a failure: a stock install has no saved whole carriages
     * at all, and an install with no contents registered has no room to show. The caller falls back
     * to the bare stamp, which is exactly what every tile did before this existed.</p>
     *
     * @param wholeCarriages  saved whole carriages, {@code EditorTemplateLists.wholeCarriages()}
     * @param carriageShells  registered carriage shells, {@code EditorTemplateLists.carriages()}
     * @param contents        top-level contents templates, {@code EditorTemplateLists.contents()}
     */
    public static Optional<BuilderOpenRequest> requestFor(BuilderMode mode,
                                                          List<String> wholeCarriages,
                                                          List<String> carriageShells,
                                                          List<String> contents) {
        if (mode == null) {
            return Optional.empty();
        }
        return switch (mode) {
            case TRAIN_OUTSIDE -> carriage(wholeCarriages, carriageShells);
            case INSIDE_CARRIAGE -> first(contents).map(
                    id -> new BuilderOpenRequest(BuilderPhotoPaths.Kind.CONTENTS, id, null));
            // Both track modes land on the kind their Open grid opens on, so the tile and the
            // library agree about what "the default" is for that part of the line. `default` is the
            // one name every kind is guaranteed to ship.
            case TRACKS_TUNNELS, TRAIN_DIMENSIONS -> BuilderOpenRequest.forTrack(
                    BuilderOpenOptions.defaultTrackKind(mode), TrackKind.DEFAULT_NAME);
        };
    }

    /**
     * A saved whole carriage when there is one, the first registered shell otherwise.
     *
     * <p>The preference is the whole point of loading a template rather than stamping a shell:
     * {@code BuilderWorldSetup.resolveCarriage} answers a carriage id with the {@code WholeCarriage}
     * — hull <em>and</em> interior — when one exists under that name, and degrades to the shell when
     * it doesn't. So this arm arrives furnished on a world that has builds in it, and identical to
     * the old behaviour on one that doesn't.</p>
     */
    private static Optional<BuilderOpenRequest> carriage(List<String> wholeCarriages,
                                                         List<String> carriageShells) {
        return first(wholeCarriages)
                .or(() -> first(carriageShells))
                .map(id -> new BuilderOpenRequest(BuilderPhotoPaths.Kind.CARRIAGE, id, null));
    }

    /** First entry of a list that may be null, empty, or hold a blank id. */
    private static Optional<String> first(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Optional.empty();
        }
        String id = ids.get(0);
        return id == null || id.isEmpty() ? Optional.empty() : Optional.of(id);
    }
}
