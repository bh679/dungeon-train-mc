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
    PART,
    TRACK,
    PILLAR,
    STAIRS,
    TUNNEL;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
