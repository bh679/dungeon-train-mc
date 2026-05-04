package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One editable, saveable, in-game-placeable model — the unified identity
 * type for every dungeon-train subsystem that authors and registers
 * structure templates. Each permittee corresponds to one
 * {@link TemplateKind}.
 *
 * <p>Sealed so dispatch in {@code SaveCommand}, {@code ResetCommand}, and
 * editor flows can treat all kinds exhaustively without caring about the
 * underlying storage split. Records are immutable identifiers — placement
 * code lives in the per-subsystem static helpers
 * ({@code train.CarriageTemplate}, {@code tunnel.TunnelTemplate}, etc.) and
 * persistence lives in the corresponding {@code *TemplateStore} /
 * {@code *VariantStore} classes.
 *
 * <p>{@link #id()} is the stable command-token identifier
 * ({@code standard}, {@code pillar_top}, {@code tunnel_section},
 * {@code track}). {@link #displayName()} is the richer HUD string that
 * also includes the variant name where relevant
 * ({@code track / default}, {@code pillar / bottom / default},
 * {@code tunnel / section / default}).
 *
 * <p>Capability metadata ({@link #kind()}, {@link #type()},
 * {@link #isBuiltin()}, {@link #canPromote()}) is informational in this
 * revision — Phase 2 of the unification plan will collapse the per-kind
 * dispatch in {@code SaveCommand.promoteOne} onto these accessors. Phase 1
 * is purely additive.
 */
public sealed interface Template
    permits Template.CarriageModel,
            Template.ContentsModel,
            Template.PartModel,
            Template.TrackModel,
            Template.PillarModel,
            Template.AdjunctModel,
            Template.TunnelModel {

    /** Stable command-token identifier — used by EditorMenuScreen + commands. */
    String id();

    /** Human-readable display string for the HUD. Defaults to {@link #id()}. */
    default String displayName() { return id(); }

    /** Top-level subsystem this template belongs to. */
    TemplateKind kind();

    /**
     * Sub-type within {@link #kind()}, if applicable —
     * {@code CarriageType.STANDARD} for built-in carriages,
     * {@code CarriagePartKind.FLOOR} for the floor part, etc.
     * Returns {@code Optional.empty()} for kinds without sub-types
     * (today: track) and for {@code Custom} variants whose type is implicit.
     */
    default Optional<TemplateType> type() { return Optional.empty(); }

    /**
     * True for templates that ship inside the mod jar. Custom user-authored
     * variants under {@code config/dungeontrain/...} are not built-in. The
     * synthetic "default" name common to track / pillar / adjunct / tunnel
     * is also considered built-in (it always exists; the bundled tier
     * controls whether it has shipped geometry).
     */
    boolean isBuiltin();

    /**
     * True if this template has a bundled-resource tier that
     * {@code /dt save default} can write through to. Mirrors the per-kind
     * arms in {@code SaveCommand.promoteOne} and
     * {@code SaveCommand.promoteOneSilent}: contents and tunnel templates
     * have no bundled tier; custom carriages cannot promote (only their
     * built-ins can).
     */
    boolean canPromote();

    record CarriageModel(CarriageVariant variant) implements Template {
        public CarriageModel {
            Objects.requireNonNull(variant, "variant");
        }

        @Override
        public String id() {
            return variant.id();
        }

        @Override
        public TemplateKind kind() {
            return TemplateKind.CARRIAGE;
        }

        @Override
        public Optional<TemplateType> type() {
            if (variant instanceof CarriageVariant.Builtin builtin) {
                return Optional.of(builtin.type());
            }
            return Optional.empty();
        }

        @Override
        public boolean isBuiltin() {
            return variant.isBuiltin();
        }

        @Override
        public boolean canPromote() {
            // Custom variants have no bundled tier — only built-ins can promote.
            return variant.isBuiltin();
        }
    }

    record ContentsModel(CarriageContents contents) implements Template {
        public ContentsModel {
            Objects.requireNonNull(contents, "contents");
        }

        @Override
        public String id() {
            return contents.id();
        }

        @Override
        public TemplateKind kind() {
            return TemplateKind.CONTENTS;
        }

        @Override
        public Optional<TemplateType> type() {
            if (contents instanceof CarriageContents.Builtin builtin) {
                return Optional.of(builtin.type());
            }
            return Optional.empty();
        }

        @Override
        public boolean isBuiltin() {
            return contents.isBuiltin();
        }

        @Override
        public boolean canPromote() {
            // Contents write-through happens inside CarriageContentsEditor.save
            // when devmode is on — there is no separate bundled tier to promote.
            return false;
        }
    }

    /**
     * Carriage shell part (FLOOR / WALLS / ROOF / DOORS) keyed by name.
     * {@code id()} is {@code part_<kind>} for command dispatch;
     * {@code displayName()} is {@code part / <kind> / <name>} so the HUD
     * shows the part variant the player is standing on.
     *
     * <p>Phase 1 introduces this record into the sealed hierarchy so parts
     * are first-class templates. Existing part dispatch in
     * {@code CarriagePartEditor} continues to take {@code (kind, name)}
     * arguments directly — Phase 2 collapses it onto the unified Template
     * shape.</p>
     */
    record PartModel(CarriagePartKind partKind, String name) implements Template {
        public PartModel {
            Objects.requireNonNull(partKind, "partKind");
            Objects.requireNonNull(name, "name");
        }

        public PartModel(CarriagePartKind partKind) { this(partKind, TrackKind.DEFAULT_NAME); }

        @Override
        public String id() {
            return "part_" + partKind.id();
        }

        @Override
        public String displayName() {
            return "part / " + partKind.id() + " / " + name;
        }

        @Override
        public TemplateKind kind() {
            return TemplateKind.PART;
        }

        @Override
        public Optional<TemplateType> type() {
            return Optional.of(partKind);
        }

        @Override
        public boolean isBuiltin() {
            // Parts have no shipped bundled tier today — every part exists
            // as a user-authored template under config/dungeontrain/parts/.
            return false;
        }

        @Override
        public boolean canPromote() {
            // CarriagePartTemplateStore.promote(kind, name) exists and is
            // wired in dev mode — Phase 2 surfaces it through SaveCommand.
            return true;
        }
    }

    /**
     * Track-tile variant. {@code id()} is the bare {@code "track"} kind
     * tag for command dispatch; {@code displayName()} is
     * {@code track / <name>} so the HUD shows the variant the player is
     * standing on.
     */
    record TrackModel(String name) implements Template {
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

        @Override
        public TemplateKind kind() {
            return TemplateKind.TRACK;
        }

        @Override
        public boolean isBuiltin() {
            return TrackKind.DEFAULT_NAME.equals(name);
        }

        @Override
        public boolean canPromote() {
            return true;
        }
    }

    /**
     * Pillar section + variant name. {@code id()} is
     * {@code pillar_<section>} for command dispatch; {@code displayName()}
     * is {@code pillar / <section> / <name>}.
     */
    record PillarModel(PillarSection section, String name) implements Template {
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

        @Override
        public TemplateKind kind() {
            return TemplateKind.PILLAR;
        }

        @Override
        public Optional<TemplateType> type() {
            return Optional.of(section);
        }

        @Override
        public boolean isBuiltin() {
            return TrackKind.DEFAULT_NAME.equals(name);
        }

        @Override
        public boolean canPromote() {
            return true;
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
    record AdjunctModel(PillarAdjunct adjunct, String name) implements Template {
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

        @Override
        public TemplateKind kind() {
            // Stairs is the only adjunct today — model that explicitly so
            // Phase 2 can route it to the future stairs-specific store
            // without losing the structural distinction from pillar.
            return TemplateKind.STAIRS;
        }

        @Override
        public Optional<TemplateType> type() {
            return Optional.of(adjunct);
        }

        @Override
        public boolean isBuiltin() {
            return TrackKind.DEFAULT_NAME.equals(name);
        }

        @Override
        public boolean canPromote() {
            return true;
        }
    }

    /**
     * Tunnel kind + variant name. {@code id()} is
     * {@code tunnel_<variant>} for command dispatch; {@code displayName()}
     * is {@code tunnel / <variant> / <name>}.
     */
    record TunnelModel(TunnelVariant variant, String name) implements Template {
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

        @Override
        public TemplateKind kind() {
            return TemplateKind.TUNNEL;
        }

        @Override
        public Optional<TemplateType> type() {
            return Optional.of(variant);
        }

        @Override
        public boolean isBuiltin() {
            return TrackKind.DEFAULT_NAME.equals(name);
        }

        @Override
        public boolean canPromote() {
            // Tunnel templates have no bundled tier today.
            return false;
        }
    }
}
