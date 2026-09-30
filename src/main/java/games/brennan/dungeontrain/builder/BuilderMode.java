package games.brennan.dungeontrain.builder;

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

    /**
     * Whole carriages — rooms and groups saved as one build, the editor's WHOLE category. First
     * because the Whole section leads the editor's row. Authored on the same platform as Train
     * Outside, so a builder world in this mode lays out like that one.
     */
    WHOLE_CARRIAGES("whole_carriages", BuilderWorldLayout.OUTSIDE_CARRIAGES),
    TRAIN_OUTSIDE("train_outside", BuilderWorldLayout.OUTSIDE_CARRIAGES),
    INSIDE_CARRIAGE("inside_carriage", BuilderWorldLayout.INSIDE_CARRIAGES),
    TRACKS_TUNNELS("tracks_tunnels", 0),
    TRAIN_DIMENSIONS("train_dimensions", 0),
    /**
     * The Lost City / WWOO buildings — the editor's BUILDINGS category. Editor-only: there is no builder
     * world for a building, so the builder-world flow shows it disabled ({@link #hasBuilderWorld}).
     */
    BUILDINGS("buildings", 0);

    /**
     * The picker's tile order — the two big tiles first, then the short "advanced" ones. Separate from
     * declaration order, which the builder world's mode strip and saved state keep.
     */
    public static final java.util.List<BuilderMode> NAV_ORDER = java.util.List.of(
        WHOLE_CARRIAGES, TRAIN_DIMENSIONS, BUILDINGS, TRAIN_OUTSIDE, INSIDE_CARRIAGE, TRACKS_TUNNELS);

    /** The modes a Train Builder world can be in, in declaration order — its strip and its cycle button. */
    public static final java.util.List<BuilderMode> BUILDER_MODES = java.util.Arrays.stream(values())
        .filter(BuilderMode::hasBuilderWorld).toList();

    private final String id;
    private final int carriageCount;

    BuilderMode(String id, int carriageCount) {
        this.id = id;
        this.carriageCount = carriageCount;
    }

    /**
     * How many empty carriages get parked on the track when this mode's world is created.
     *
     * <p>Shaping the world to the job: <b>Train Outside</b> gets a run of carriages with flatbed
     * pads so you can judge the silhouette from the platform, <b>Inside Carriage</b> gets exactly
     * one because that's all you can stand in, and the track/portal modes get none — a train would
     * only be in the way.</p>
     */
    public int carriageCount() {
        return carriageCount;
    }

    /** Whether this is one of the picker's big tiles; the rest are the shorter "advanced" row. */
    public boolean primary() {
        return this == WHOLE_CARRIAGES || this == TRAIN_DIMENSIONS;
    }

    /** Whether a Train Builder world can be made in this mode. Buildings are authored in the editor only. */
    public boolean hasBuilderWorld() {
        return this != BUILDINGS;
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
     * Translation key for the sentence or two that says what this mode is for.
     *
     * <p>Shown as the tile's tooltip on the title-screen picker and as the body of the editor's
     * Nav tab — the same words in both places, so the picker teaches the vocabulary the editor
     * then uses.</p>
     */
    public String descriptionKey() {
        return labelKey() + ".description";
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

    /** {@link #fromId}, refusing a mode with no builder world — what a builder packet may ask for. */
    public static Optional<BuilderMode> fromBuilderId(String raw) {
        return fromId(raw).filter(BuilderMode::hasBuilderWorld);
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
