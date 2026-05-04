package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriagePartRegistry;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.PillarTemplateStore;
import games.brennan.dungeontrain.editor.TrackTemplateStore;
import games.brennan.dungeontrain.editor.TunnelTemplateStore;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;

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
 * ({@code train.CarriagePlacer}, {@code tunnel.TunnelPlacer}, etc.) and
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
    permits Template.Carriage,
            Template.Contents,
            Template.Part,
            Template.Track,
            Template.Pillar,
            Template.Adjunct,
            Template.Tunnel {

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

    /**
     * Persistence handle for save/promote operations. Phase-2 dispatch
     * sites ({@code SaveCommand}) call {@link Stores#save} /
     * {@link Stores#promote} which forward to this store with the right
     * type. Each record returns a per-discriminator singleton from the
     * corresponding {@code *TemplateStore.adapter(...)} factory; the
     * underlying static methods stay the source of truth for actual
     * I/O — the adapter is a thin wrapper that reports a uniform
     * {@link SaveResult}.
     */
    TemplateStore<? extends Template> store();

    /**
     * Discovery handle for built-ins / customs / lookup. Symmetric to
     * {@link #store()}: each record returns a per-discriminator singleton
     * from the corresponding {@code *Registry.adapter(...)} factory.
     */
    TemplateRegistry<? extends Template> registry();

    record Carriage(CarriageVariant variant) implements Template {
        public Carriage {
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

        @Override public TemplateStore<Carriage> store() { return CarriageTemplateStore.adapter(); }
        @Override public TemplateRegistry<Carriage> registry() { return CarriageVariantRegistry.adapter(); }
    }

    record Contents(CarriageContents contents) implements Template {
        public Contents {
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

        @Override public TemplateStore<Contents> store() { return CarriageContentsStore.adapter(); }
        @Override public TemplateRegistry<Contents> registry() { return CarriageContentsRegistry.adapter(); }
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
    record Part(CarriagePartKind partKind, String name) implements Template {
        public Part {
            Objects.requireNonNull(partKind, "partKind");
            Objects.requireNonNull(name, "name");
        }

        public Part(CarriagePartKind partKind) { this(partKind, TrackKind.DEFAULT_NAME); }

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

        @Override public TemplateStore<Part> store() { return CarriagePartTemplateStore.adapter(new CarriagePartTemplateId(partKind, name)); }
        @Override public TemplateRegistry<Part> registry() { return CarriagePartRegistry.adapter(new CarriagePartTemplateId(partKind, name)); }
    }

    /**
     * Track-tile variant. {@code id()} is the bare {@code "track"} kind
     * tag for command dispatch; {@code displayName()} is
     * {@code track / <name>} so the HUD shows the variant the player is
     * standing on.
     */
    record Track(String name) implements Template {
        public Track {
            Objects.requireNonNull(name, "name");
        }

        public Track() { this(TrackKind.DEFAULT_NAME); }

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

        @Override public TemplateStore<Track> store() { return TrackTemplateStore.adapter(); }
        @Override public TemplateRegistry<Track> registry() { return TrackVariantRegistry.adapterForTrack(); }
    }

    /**
     * Pillar section + variant name. {@code id()} is
     * {@code pillar_<section>} for command dispatch; {@code displayName()}
     * is {@code pillar / <section> / <name>}.
     */
    record Pillar(PillarSection section, String name) implements Template {
        public Pillar {
            Objects.requireNonNull(section, "section");
            Objects.requireNonNull(name, "name");
        }

        public Pillar(PillarSection section) { this(section, TrackKind.DEFAULT_NAME); }

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

        @Override public TemplateStore<Pillar> store() { return PillarTemplateStore.adapter(new PillarTemplateId(section, name)); }
        @Override public TemplateRegistry<Pillar> registry() { return TrackVariantRegistry.adapterForPillar(new PillarTemplateId(section, name)); }
    }

    /**
     * Pillar adjunct + variant name. {@code id()} is
     * {@code adjunct_<id>} for command dispatch (matching the token format
     * already accepted by {@code EditorCommand.tryParseAdjunct});
     * {@code displayName()} is {@code <adjunct-id> / <name>} so the HUD
     * shows {@code Tracks / stairs / default} when standing in the
     * stairs default plot.
     */
    record Adjunct(PillarAdjunct adjunct, String name) implements Template {
        public Adjunct {
            Objects.requireNonNull(adjunct, "adjunct");
            Objects.requireNonNull(name, "name");
        }

        public Adjunct(PillarAdjunct adjunct) { this(adjunct, TrackKind.DEFAULT_NAME); }

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

        @Override public TemplateStore<Adjunct> store() { return PillarTemplateStore.adapterForAdjunct(new StairsTemplateId(name)); }
        @Override public TemplateRegistry<Adjunct> registry() { return TrackVariantRegistry.adapterForAdjunct(new StairsTemplateId(name)); }
    }

    /**
     * Tunnel kind + variant name. {@code id()} is
     * {@code tunnel_<variant>} for command dispatch; {@code displayName()}
     * is {@code tunnel / <variant> / <name>}.
     */
    record Tunnel(TunnelVariant variant, String name) implements Template {
        public Tunnel {
            Objects.requireNonNull(variant, "variant");
            Objects.requireNonNull(name, "name");
        }

        public Tunnel(TunnelVariant variant) { this(variant, TrackKind.DEFAULT_NAME); }

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

        @Override public TemplateStore<Tunnel> store() { return TunnelTemplateStore.adapter(new TunnelTemplateId(variant, name)); }
        @Override public TemplateRegistry<Tunnel> registry() { return TrackVariantRegistry.adapterForTunnel(new TunnelTemplateId(variant, name)); }
    }
}
