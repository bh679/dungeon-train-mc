package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriagePartKind;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Where a template's photo lives: beside the template, same name, {@code .png}.
 *
 * <p>The paths come from the stores rather than being rebuilt here, so a photo can't drift away from
 * the data it describes — including into a package's working directory, which is where
 * {@code UserContentPaths} points when one is active.</p>
 */
public final class BuilderPhotoPaths {

    /** Which store owns the template, as it travels on the wire. */
    public enum Kind {
        CARRIAGE("carriage"),
        /**
         * A whole run of carriages saved as one template — what Train Outside authors, where the
         * composition (which carriage follows which) is part of the build rather than an accident of
         * how three separate templates happened to be listed.
         */
        CARRIAGE_GROUP("carriage_group"),
        CONTENTS("contents"),
        PART("part"),
        /**
         * A track-side template — track tile, pillar section, tunnel, or stairs adjunct.
         *
         * <p>Needs a {@link TrackKind} the way {@link #PART} needs a {@link CarriagePartKind}: the
         * eight kinds each have their own directory, and {@code default} exists in all of them.</p>
         */
        TRACK("track"),
        /**
         * A portal room. Also a track-side template on disk — {@code portals/room} — but its own kind
         * rather than a {@link #TRACK} with a kind attached, because the Open grid browses rooms by
         * what they do at their walls and never by the eight track kinds.
         */
        PORTAL_ROOM("portal_room");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Optional<Kind> fromId(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String needle = raw.trim().toLowerCase(Locale.ROOT);
            for (Kind k : values()) {
                if (k.id.equals(needle)) {
                    return Optional.of(k);
                }
            }
            return Optional.empty();
        }
    }

    private static final String PNG = ".png";

    private BuilderPhotoPaths() {}

    /** As {@link #photoFor(Kind, String, CarriagePartKind, TrackKind)}, for a non-track kind. */
    public static Optional<Path> photoFor(Kind kind, String id, CarriagePartKind partKind) {
        return photoFor(kind, id, partKind, null);
    }

    /**
     * The photo path for a saved template, or empty when the kind or its arguments don't resolve.
     *
     * @param partKind  only read for {@link Kind#PART}; each kind of part has its own directory
     * @param trackKind only read for {@link Kind#TRACK}, for the same reason
     */
    public static Optional<Path> photoFor(Kind kind, String id, CarriagePartKind partKind,
                                          TrackKind trackKind) {
        if (kind == null || id == null || id.isEmpty()) {
            return Optional.empty();
        }
        return switch (kind) {
            case CARRIAGE -> Optional.of(withPng(CarriageTemplateStore.fileForId(id)));
            case CARRIAGE_GROUP -> Optional.of(withPng(CarriageGroupTemplateStore.fileForId(id)));
            case CONTENTS -> Optional.of(withPng(CarriageContentsStore.fileForId(id)));
            case PART -> partKind == null
                    ? Optional.empty()
                    : Optional.of(withPng(CarriagePartTemplateStore.fileFor(partKind, id)));
            case TRACK -> trackKind == null
                    ? Optional.empty()
                    : Optional.of(withPng(TrackVariantStore.fileFor(trackKind, id)));
            case PORTAL_ROOM -> Optional.of(withPng(
                    TrackVariantStore.fileFor(TrackKind.PORTAL_ROOM, id)));
        };
    }

    /** As {@link #sourcePhotoFor(Kind, String, CarriagePartKind, TrackKind)}, for a non-track kind. */
    public static Optional<Path> sourcePhotoFor(Kind kind, String id, CarriagePartKind partKind) {
        return sourcePhotoFor(kind, id, partKind, null);
    }

    /** The dev-mode source-tree twin, so an authored photo ships with the template it describes. */
    public static Optional<Path> sourcePhotoFor(Kind kind, String id, CarriagePartKind partKind,
                                                TrackKind trackKind) {
        if (kind == null || id == null || id.isEmpty()) {
            return Optional.empty();
        }
        return switch (kind) {
            case CARRIAGE -> CarriageTemplateStore.sourceTreeAvailable()
                    ? Optional.of(withPng(CarriageTemplateStore.sourceFileForId(id)))
                    : Optional.empty();
            // No source-tree twin: groups are authored in a builder world and the mod ships none, so
            // there is no bundled copy for a photo to sit beside. Same answer CONTENTS gives when its
            // own source tree is absent.
            case CARRIAGE_GROUP -> Optional.empty();
            case CONTENTS -> CarriageContentsStore.sourceTreeAvailable()
                    ? Optional.of(withPng(CarriageContentsStore.sourceFileForId(id)))
                    : Optional.empty();
            case PART -> partKind != null && CarriagePartTemplateStore.sourceTreeAvailable()
                    ? Optional.of(withPng(CarriagePartTemplateStore.sourceFileFor(partKind, id)))
                    : Optional.empty();
            case TRACK -> trackKind != null && TrackVariantStore.sourceTreeAvailable()
                    ? Optional.of(withPng(TrackVariantStore.sourceFileFor(trackKind, id)))
                    : Optional.empty();
            case PORTAL_ROOM -> TrackVariantStore.sourceTreeAvailable()
                    ? Optional.of(withPng(TrackVariantStore.sourceFileFor(TrackKind.PORTAL_ROOM, id)))
                    : Optional.empty();
        };
    }

    /**
     * Swap the extension for {@code .png}.
     *
     * <p>Trims from the last dot <em>in the file name</em>, not in the whole path — a config
     * directory with a dot in it (or a package directory named after a version) would otherwise take
     * the cut and produce a path that isn't in the template directory at all.</p>
     */
    static Path withPng(Path templateFile) {
        String name = templateFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot <= 0 ? name : name.substring(0, dot);
        return templateFile.resolveSibling(base + PNG);
    }
}
