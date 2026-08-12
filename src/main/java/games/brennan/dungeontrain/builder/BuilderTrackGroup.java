package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.variant.TrackKind;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The sub-options under <b>Tracks &amp; Tunnels</b> — the track-side answer to
 * {@link BuilderNewOptions.SubType}.
 *
 * <p>{@link TrackKind} is a storage enum: eight flat constants that say where a template lives on
 * disk. That is the right shape for a store and the wrong shape for a menu, because four of those
 * constants are one thing seen from different angles — a builder thinks "a pillar", then which
 * section of it, not "a pillar_middle". These groups are that first thought, and the kinds
 * underneath are the second.</p>
 *
 * <p>Which group a kind belongs to is a judgement about what a builder is looking at, so it is
 * recorded here rather than derived from {@link TrackKind#subdir()}: stairs live under
 * {@code pillars/} on disk because they bolt onto a pillar, but you author them as stairs.</p>
 *
 * <p>Pure — no client imports, no registry reads — because the Open screen picks the group and the
 * server has to read the kind back off the wire without either side importing the other.</p>
 */
public enum BuilderTrackGroup {

    /** The open-air line the train runs on. One kind, so its grid lists templates directly. */
    TRACKS("tracks", List.of(TrackKind.TILE)),

    /** The columns that carry the line, ground-up — the order they stack in the world. */
    PILLARS("pillars", List.of(TrackKind.PILLAR_BOTTOM, TrackKind.PILLAR_MIDDLE, TrackKind.PILLAR_TOP)),

    /** What the line runs through, then the mouth it runs through to get there. */
    TUNNELS("tunnels", List.of(TrackKind.TUNNEL_SECTION, TrackKind.TUNNEL_PORTAL)),

    /** The climb up a pillar, and the doorway at the bottom of it. */
    STAIRS("stairs", List.of(TrackKind.ADJUNCT_STAIRS, TrackKind.ADJUNCT_STAIRS_ENTRANCE));

    private final String id;
    private final List<TrackKind> kinds;

    BuilderTrackGroup(String id, List<TrackKind> kinds) {
        this.id = id;
        this.kinds = kinds;
    }

    /** Stable lower-case token — used in logs and on the wire. */
    public String id() {
        return id;
    }

    /** Translation key for the cycle-button label. */
    public String labelKey() {
        return "gui.dungeontrain.builder.track_group." + id;
    }

    /** The kinds this group offers, in the order the grid shows them. Never empty. */
    public List<TrackKind> kinds() {
        return kinds;
    }

    /**
     * Whether this group holds exactly one kind.
     *
     * <p>Decides whether the Open grid needs a kind level at all: with one kind there is nothing to
     * choose between, so the grid skips straight to that kind's named templates rather than showing
     * a single tile whose only purpose is to be clicked through.</p>
     */
    public boolean isSingleKind() {
        return kinds.size() == 1;
    }

    /** The kind a single-kind group stands for — its first kind either way. */
    public TrackKind soleKind() {
        return kinds.get(0);
    }

    /** The group {@code kind} is listed under. Total: every {@link TrackKind} belongs to one. */
    public static BuilderTrackGroup of(TrackKind kind) {
        for (BuilderTrackGroup group : values()) {
            if (group.kinds.contains(kind)) {
                return group;
            }
        }
        // Unreachable while the four groups cover the enum; a new TrackKind lands here rather than
        // throwing, so an unfinished kind degrades to "listed under Tracks" instead of crashing a menu.
        return TRACKS;
    }

    public static Optional<BuilderTrackGroup> fromId(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        for (BuilderTrackGroup group : values()) {
            if (group.id.equals(needle)) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }
}
