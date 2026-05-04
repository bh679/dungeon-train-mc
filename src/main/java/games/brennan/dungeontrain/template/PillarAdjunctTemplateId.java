package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.variant.TrackKind;

import java.util.Objects;

/**
 * Composite identifier for a pillar-adjunct template — pairs the
 * {@link PillarAdjunct} discriminator with the variant name. Lifts the
 * {@code (PillarAdjunct, String)} tuple that previously propagated through
 * the storage layer into a single typed record so adapter signatures and
 * EnumMap-shaped lookups can pass one value instead of two.
 *
 * <p>Phase-4 expansion of the original Phase-3 single-field
 * {@code StairsTemplateId} — adding the adjunct discriminator keeps the
 * record symmetric with {@link PillarTemplateId}, {@link TunnelTemplateId},
 * and {@link CarriagePartTemplateId}, and lets call sites at the editor
 * command and storage helper layers pass one value instead of two.
 */
public record PillarAdjunctTemplateId(PillarAdjunct adjunct, String name) {

    public PillarAdjunctTemplateId(PillarAdjunct adjunct) {
        this(adjunct, TrackKind.DEFAULT_NAME);
    }

    public PillarAdjunctTemplateId {
        Objects.requireNonNull(adjunct, "adjunct");
        Objects.requireNonNull(name, "name");
    }
}
