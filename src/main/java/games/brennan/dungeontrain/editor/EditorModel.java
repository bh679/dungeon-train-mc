package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant;

import java.util.Locale;
import java.util.Objects;

/**
 * One editable model inside an {@link EditorCategory} — a carriage variant, a
 * pillar section, or a tunnel variant. Sealed so the dispatch in
 * {@code SaveCommand} / {@code ResetCommand} stays exhaustive.
 *
 * <p>{@link #id()} is the stable token used in command arguments + logs
 * ({@code standard}, {@code pillar_top}, {@code tunnel_section}).
 * {@link #displayName()} is what the status HUD shows to the player.</p>
 */
public sealed interface EditorModel
    permits EditorModel.CarriageModel, EditorModel.PillarModel, EditorModel.TunnelModel {

    /** Stable lower-case identifier — matches the token used in commands. */
    String id();

    /** Human-readable name for HUD + command output. Currently equal to {@link #id()}. */
    default String displayName() { return id(); }

    record CarriageModel(CarriageVariant variant) implements EditorModel {
        public CarriageModel {
            Objects.requireNonNull(variant, "variant");
        }

        @Override
        public String id() {
            return variant.id();
        }
    }

    record PillarModel(PillarSection section) implements EditorModel {
        public PillarModel {
            Objects.requireNonNull(section, "section");
        }

        @Override
        public String id() {
            return "pillar_" + section.id();
        }
    }

    record TunnelModel(TunnelVariant variant) implements EditorModel {
        public TunnelModel {
            Objects.requireNonNull(variant, "variant");
        }

        @Override
        public String id() {
            return "tunnel_" + variant.name().toLowerCase(Locale.ROOT);
        }
    }
}
