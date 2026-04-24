package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant;

import java.util.Locale;
import java.util.Objects;

/**
 * One editable model inside an {@link EditorCategory} — a carriage variant,
 * a carriage-interior contents variant, a pillar section, a tunnel
 * variant, or the single track tile. Sealed so the dispatch in
 * {@code SaveCommand} / {@code ResetCommand} stays exhaustive.
 *
 * <p>{@link #id()} is the stable token used in command arguments + logs
 * ({@code standard}, {@code pillar_top}, {@code tunnel_section}).
 * {@link #displayName()} is what the status HUD shows to the player.</p>
 */
public sealed interface EditorModel
    permits EditorModel.CarriageModel, EditorModel.ContentsModel, EditorModel.PillarModel, EditorModel.TunnelModel, EditorModel.TrackModel {

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

    /**
     * Interior contents of a carriage. Parallel to {@link CarriageModel},
     * but plotted in a separate row (z=80, see
     * {@link CarriageContentsEditor}) and persisted through the contents
     * store. The id matches the underlying {@link CarriageContents#id()}
     * so command args and display strings don't need translation.
     */
    record ContentsModel(CarriageContents contents) implements EditorModel {
        public ContentsModel {
            Objects.requireNonNull(contents, "contents");
        }

        @Override
        public String id() {
            return contents.id();
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

    /**
     * The single open-air track tile template — one per world, no variants.
     * Wraps no state; the record form is used purely for sealed-hierarchy
     * dispatch.
     */
    record TrackModel() implements EditorModel {
        @Override
        public String id() {
            return "track";
        }
    }
}
