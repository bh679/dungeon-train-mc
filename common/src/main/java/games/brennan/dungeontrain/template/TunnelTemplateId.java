package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;

import java.util.Objects;

/**
 * Composite identifier for a tunnel template — pairs the
 * {@link TunnelVariant} discriminator (SECTION / PORTAL) with the variant
 * name. Lifts the {@code (TunnelVariant, String)} tuple that previously
 * propagated through the storage layer into a single typed record.
 *
 * <p>Phase-3 surface adoption only: see {@link PillarTemplateId} for the
 * broader rationale.
 */
public record TunnelTemplateId(TunnelVariant variant, String name) {

    public TunnelTemplateId(TunnelVariant variant) {
        this(variant, TrackKind.DEFAULT_NAME);
    }

    public TunnelTemplateId {
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(name, "name");
    }
}
