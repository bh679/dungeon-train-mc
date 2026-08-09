package games.brennan.dungeontrain.client.builder;

import java.util.Locale;
import java.util.Optional;

/**
 * The four options on the Train Builder picker screen.
 *
 * <p>These are the <em>builder-facing</em> names for what the technical Train Editor calls
 * carriages / contents / tracks / portals. The editor's vocabulary only makes sense if you
 * already know the codebase, so the builder speaks in terms of what you can see:</p>
 *
 * <ul>
 *   <li>{@link #TRAIN_OUTSIDE} — the carriage shell you see from the platform
 *       ({@code EditorCategory.CARRIAGES})</li>
 *   <li>{@link #INSIDE_CARRIAGE} — what fills a carriage once you step in
 *       ({@code EditorCategory.CONTENTS})</li>
 *   <li>{@link #TRACKS_TUNNELS} — the line the train runs on and what it runs through
 *       ({@code EditorCategory.TRACKS})</li>
 *   <li>{@link #TRAIN_DIMENSIONS} — the rooms behind a tunnel portal
 *       ({@code TunnelPlacer.TunnelVariant.PORTAL}, which lives inside TRACKS today)</li>
 * </ul>
 *
 * <p>The mapping above is recorded deliberately: each builder mode currently launches a flat
 * world and a "coming soon" stub, and the follow-up task that builds the four editors needs to
 * know which existing editor surface each one is meant to replace.</p>
 *
 * <p>Deliberately free of client-only imports so it stays unit-testable.</p>
 */
public enum BuilderMode {

    TRAIN_OUTSIDE("train_outside"),
    INSIDE_CARRIAGE("inside_carriage"),
    TRACKS_TUNNELS("tracks_tunnels"),
    TRAIN_DIMENSIONS("train_dimensions");

    private final String id;

    BuilderMode(String id) {
        this.id = id;
    }

    /** Stable lower-case token — used in world names, logs, and (later) commands. */
    public String id() {
        return id;
    }

    /** Translation key for the tile label and the "coming soon" line. */
    public String labelKey() {
        return "gui.dungeontrain.builder." + id;
    }

    /**
     * Texture path (namespace-relative) for this tile's image.
     *
     * <p>The PNGs are not shipped yet — {@link BuilderTileButton} falls back to a tinted panel
     * when the resource is absent, so a missing image degrades to a plain labelled tile rather
     * than the missing-texture checkerboard.</p>
     */
    public String texturePath() {
        return "textures/gui/builder/" + id + ".png";
    }

    public static Optional<BuilderMode> fromId(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        for (BuilderMode mode : values()) {
            if (mode.id.equals(needle)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
