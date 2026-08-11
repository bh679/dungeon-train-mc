package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
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
        CONTENTS("contents"),
        PART("part");

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

    /**
     * The photo path for a saved template, or empty when the kind or its arguments don't resolve.
     *
     * @param partKind only read for {@link Kind#PART}; each kind of part has its own directory
     */
    public static Optional<Path> photoFor(Kind kind, String id, CarriagePartKind partKind) {
        if (kind == null || id == null || id.isEmpty()) {
            return Optional.empty();
        }
        return switch (kind) {
            case CARRIAGE -> Optional.of(withPng(CarriageTemplateStore.fileForId(id)));
            case CONTENTS -> Optional.of(withPng(CarriageContentsStore.fileForId(id)));
            case PART -> partKind == null
                    ? Optional.empty()
                    : Optional.of(withPng(CarriagePartTemplateStore.fileFor(partKind, id)));
        };
    }

    /** The dev-mode source-tree twin, so an authored photo ships with the template it describes. */
    public static Optional<Path> sourcePhotoFor(Kind kind, String id, CarriagePartKind partKind) {
        if (kind == null || id == null || id.isEmpty()) {
            return Optional.empty();
        }
        return switch (kind) {
            case CARRIAGE -> CarriageTemplateStore.sourceTreeAvailable()
                    ? Optional.of(withPng(CarriageTemplateStore.sourceFileForId(id)))
                    : Optional.empty();
            case CONTENTS -> CarriageContentsStore.sourceTreeAvailable()
                    ? Optional.of(withPng(CarriageContentsStore.sourceFileForId(id)))
                    : Optional.empty();
            case PART -> partKind != null && CarriagePartTemplateStore.sourceTreeAvailable()
                    ? Optional.of(withPng(CarriagePartTemplateStore.sourceFileFor(partKind, id)))
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
