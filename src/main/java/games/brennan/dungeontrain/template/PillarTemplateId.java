package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;

import java.util.Objects;

/**
 * Composite identifier for a pillar template — pairs the
 * {@link PillarSection} discriminator with the variant name. Lifts the
 * {@code (PillarSection, String)} tuple that previously propagated through
 * the storage layer into a single typed record so adapter signatures and
 * EnumMap-shaped lookups can pass one value instead of two.
 *
 * <p>Phase-3 surface adoption only: the storage-layer Phase-2 adapter
 * factories accept this record as their discriminator; the broader
 * placement / generator call sites still pass {@code (section, name)}
 * separately. Phase 4 will sweep those.
 */
public record PillarTemplateId(PillarSection section, String name) {

    public PillarTemplateId(PillarSection section) {
        this(section, TrackKind.DEFAULT_NAME);
    }

    public PillarTemplateId {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(name, "name");
    }
}
