package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.train.CarriagePartKind;

import java.util.Objects;

/**
 * Composite identifier for a carriage-part template — pairs the
 * {@link CarriagePartKind} discriminator (FLOOR / WALLS / ROOF / DOORS)
 * with the variant name. Lifts the {@code (CarriagePartKind, String)}
 * tuple that previously propagated through the storage layer into a
 * single typed record.
 *
 * <p>Unlike the other id records in this package, no nullary or
 * single-arg constructor is provided — parts have no synthetic
 * "default" name; every part instance carries its own author-assigned
 * name (e.g. {@code part_floor}, {@code part_walls}).
 *
 * <p>Phase-3 surface adoption only: see {@link PillarTemplateId} for the
 * broader rationale.
 */
public record CarriagePartTemplateId(CarriagePartKind kind, String name) {

    public CarriagePartTemplateId {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
    }
}
