package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.builder.BuilderMode;

import java.util.Optional;

/**
 * Which editor category each tile on the Train Builder picker stands for.
 *
 * <p>The picker's four tiles are the builder-facing names for four of the technical editor's
 * categories — {@link BuilderMode} has said so in prose since it was written, and the title
 * screen's <b>Train Editor</b> button now opens the same picker, so the mapping has to be
 * something code can read.</p>
 *
 * <p>Its own class rather than a method on {@link BuilderMode}: that enum is deliberately free of
 * editor and client imports so it stays cheap to unit-test, and this mapping is only interesting
 * to the editor.</p>
 */
public final class BuilderModeCategory {

    private BuilderModeCategory() {}

    /** The category {@code /dungeontrain editor <id>} should land in for this tile. */
    public static EditorCategory of(BuilderMode mode) {
        return switch (mode) {
            case WHOLE_CARRIAGES -> EditorCategory.WHOLE;
            case TRAIN_OUTSIDE -> EditorCategory.CARRIAGES;
            case INSIDE_CARRIAGE -> EditorCategory.CONTENTS;
            case TRACKS_TUNNELS -> EditorCategory.TRACKS;
            case TRAIN_DIMENSIONS -> EditorCategory.PORTALS;
        };
    }

    /**
     * The tile that stands for this category, or empty for a category the picker does not show
     * (architecture is stamped alongside the others, never chosen).
     */
    public static Optional<BuilderMode> modeOf(EditorCategory category) {
        for (BuilderMode mode : BuilderMode.values()) {
            if (of(mode) == category) return Optional.of(mode);
        }
        return Optional.empty();
    }
}
