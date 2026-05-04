package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.track.variant.TrackKind;

import java.util.Objects;

/**
 * Identifier for a stairs (pillar-adjunct) template — currently a single
 * named variant per id since {@link games.brennan.dungeontrain.track.PillarAdjunct#STAIRS}
 * is the only adjunct kind. Modelled as a single-field record so that, if
 * future adjunct kinds appear, this can grow a discriminator without
 * disturbing existing call sites.
 *
 * <p>Phase-3 surface adoption only: storage-layer adapter factories accept
 * this record. See {@link PillarTemplateId} for the broader rationale.
 */
public record StairsTemplateId(String name) {

    public StairsTemplateId() {
        this(TrackKind.DEFAULT_NAME);
    }

    public StairsTemplateId {
        Objects.requireNonNull(name, "name");
    }
}
