package games.brennan.dungeontrain.template;

import java.util.Locale;

/**
 * Top-level classification of a {@link Template} — what subsystem owns the
 * persistence, registry, and placement code. One value per record permittee
 * of {@link Template}.
 *
 * <p>{@link #id()} is the stable lower-case token used for command routing
 * and on-disk subdir naming when storage layers eventually consolidate (see
 * Phase 2 of the unification plan).</p>
 */
public enum TemplateKind {
    CARRIAGE,
    CONTENTS,
    /** Shell and interior saved together as one template — see {@code train.WholeCarriage}. */
    WHOLE_CARRIAGE,
    /** A whole run of carriages saved as one template — see {@code train.CarriageGroup}. */
    CARRIAGE_GROUP,
    PART,
    TRACK,
    PILLAR,
    STAIRS,
    STAIRS_ENTRANCE,
    TUNNEL,
    /** The pocket room a portal carriage group's two corridors open into. */
    PORTAL_ROOM,
    /** The frame that dresses a dimensional carriage room — see {@code portal.chunkframe.ChunkFrame}. */
    CHUNK_FRAME;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
