package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant;

import java.util.Locale;
import java.util.Objects;

/**
 * One editable model inside an {@link EditorCategory} — a carriage variant,
 * a carriage-interior contents variant, a pillar section + variant name, a
 * tunnel kind + variant name, or the track tile + variant name. Sealed so
 * the dispatch in {@code SaveCommand} / {@code ResetCommand} stays
 * exhaustive.
 *
 * <p>{@link #id()} is the stable command-token identifier
 * ({@code standard}, {@code pillar_top}, {@code tunnel_section},
 * {@code track}). {@link #displayName()} is the richer HUD string that
 * also includes the variant name where relevant
 * ({@code track / default}, {@code pillar / bottom / default},
 * {@code tunnel / section / default}). The HUD shows
 * {@code <category> / <displayName>}, so a player standing in the pillar
 * BOTTOM "stone" plot sees {@code Tracks / pillar / bottom / stone}.</p>
 */
public sealed interface EditorModel
    permits EditorModel.CarriageModel, EditorModel.ContentsModel, EditorModel.PillarModel, EditorModel.AdjunctModel, EditorModel.TunnelModel, EditorModel.TrackModel {

    /** Stable command-token identifier — used by EditorMenuScreen + commands. */
    String id();

    /** Human-readable display string for the HUD. Defaults to {@link #id()}. */
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

    record ContentsModel(CarriageContents contents) implements EditorModel {
        public ContentsModel {
            Objects.requireNonNull(contents, "contents");
        }

        @Override
        public String id() {
            return contents.id();
        }
    }

    /**
     * Track-tile variant. {@code id()} is the bare {@code "track"} kind
     * tag for command dispatch; {@code displayName()} is
     * {@code track / <name>} so the HUD shows the variant the player is
     * standing on.
     */
    record TrackModel(String name) implements EditorModel {
        public TrackModel {
            Objects.requireNonNull(name, "name");
        }

        public TrackModel() { this(TrackKind.DEFAULT_NAME); }

        @Override
        public String id() {
            return "track";
        }

        @Override
        public String displayName() {
            return "track / " + name;
        }
    }

    /**
     * Pillar section + variant name. {@code id()} is
     * {@code pillar_<section>} for command dispatch; {@code displayName()}
     * is {@code pillar / <section> / <name>}.
     */
    record PillarModel(PillarSection section, String name) implements EditorModel {
        public PillarModel {
            Objects.requireNonNull(section, "section");
            Objects.requireNonNull(name, "name");
        }

        public PillarModel(PillarSection section) { this(section, TrackKind.DEFAULT_NAME); }

        @Override
        public String id() {
            return "pillar_" + section.id();
        }

        @Override
        public String displayName() {
            return "pillar / " + section.id() + " / " + name;
        }
    }

    /**
     * Pillar adjunct + variant name. {@code id()} is
     * {@code adjunct_<id>} for command dispatch (matching the token format
     * already accepted by {@code EditorCommand.tryParseAdjunct});
     * {@code displayName()} is {@code <adjunct-id> / <name>} so the HUD
     * shows {@code Tracks / stairs / default} when standing in the
     * stairs default plot.
     */
    record AdjunctModel(PillarAdjunct adjunct, String name) implements EditorModel {
        public AdjunctModel {
            Objects.requireNonNull(adjunct, "adjunct");
            Objects.requireNonNull(name, "name");
        }

        public AdjunctModel(PillarAdjunct adjunct) { this(adjunct, TrackKind.DEFAULT_NAME); }

        @Override
        public String id() {
            return "adjunct_" + adjunct.id();
        }

        @Override
        public String displayName() {
            return adjunct.id() + " / " + name;
        }
    }

    /**
     * Tunnel kind + variant name. {@code id()} is
     * {@code tunnel_<variant>} for command dispatch; {@code displayName()}
     * is {@code tunnel / <variant> / <name>}.
     */
    record TunnelModel(TunnelVariant variant, String name) implements EditorModel {
        public TunnelModel {
            Objects.requireNonNull(variant, "variant");
            Objects.requireNonNull(name, "name");
        }

        public TunnelModel(TunnelVariant variant) { this(variant, TrackKind.DEFAULT_NAME); }

        @Override
        public String id() {
            return "tunnel_" + variant.name().toLowerCase(Locale.ROOT);
        }

        @Override
        public String displayName() {
            return "tunnel / " + variant.name().toLowerCase(Locale.ROOT) + " / " + name;
        }
    }
}
