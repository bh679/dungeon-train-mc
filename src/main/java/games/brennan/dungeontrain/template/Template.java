package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.editor.CarriageContentsEditor;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.CarriagePartEditor;
import games.brennan.dungeontrain.editor.CarriagePartRegistry;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.PillarEditor;
import games.brennan.dungeontrain.editor.PillarTemplateStore;
import games.brennan.dungeontrain.editor.TrackEditor;
import games.brennan.dungeontrain.editor.TrackPlotLocator;
import games.brennan.dungeontrain.editor.TrackTemplateStore;
import games.brennan.dungeontrain.editor.TunnelEditor;
import games.brennan.dungeontrain.editor.TunnelTemplateStore;
import games.brennan.dungeontrain.net.EditorStatusPacket;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageContentsWeights;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.CarriageWeights;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

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

    /**
     * Pick weight for the editor HUD overlay. Returns
     * {@link EditorStatusPacket#NO_WEIGHT} for kinds without a weight pool
     * (today: parts). Phase-3 collapse target — replaces the per-kind
     * {@code instanceof} chain in {@code VariantOverlayRenderer.weightFor}.
     */
    int weight();

    /**
     * Bare variant-name segment for the editor HUD and command splicing —
     * what {@code /dt editor weight <kind> <name> ...} expects. Defaults
     * to {@link #id()} so future kinds without a separate name field stay
     * NPE-safe; overridden on records that carry an explicit name.
     */
    default String variantName() { return id(); }

    /**
     * Re-stamp this template's editor plot from the normal tier resolution
     * (config → bundled → fallback). Default no-op for kinds without a
     * dedicated editor (parts use a dedicated entry path). Phase-3
     * collapse target — replaces the per-kind chain in
     * {@code ResetCommand.resetToSaved}.
     */
    default void restampPlot(ServerLevel level, CarriageDims dims) {}

    /**
     * True iff this template has a bundled-tier copy that
     * {@code /dt reset default} can re-stamp from. Distinct from
     * {@link #canPromote()} (which gates writes): both happen to be
     * {@code false} for contents and tunnel today, but the read/write
     * distinction is preserved against future divergence. Default delegates
     * to {@link #canPromote()}.
     */
    default boolean hasBundledTier() { return canPromote(); }

    /**
     * Bundled {@link StructureTemplate} for {@code /dt reset default}.
     * Empty when no bundled copy exists for this id (custom carriage
     * with no shipped tier; contents and tunnel always empty).
     */
    Optional<StructureTemplate> bundled(ServerLevel level, CarriageDims dims);

    /**
     * Editor-plot world origin for this template — the position
     * {@code /dt editor X} teleports to and the corner {@code /dt reset
     * default} stamps the bundled copy at. Returns {@code null} when this
     * template's plot is not registered in the editor world today
     * (e.g. unknown contents, missing carriage variant).
     */
    BlockPos editorPlotOrigin(ServerLevel level, CarriageDims dims);

    /**
     * Erase the editor plot before re-stamping the bundled default.
     * Default no-op; only carriages override (their hardcoded fallback
     * leaves stale air patches that the bundled stamp won't overwrite).
     */
    default void eraseEditorPlot(ServerLevel level, BlockPos origin, CarriageDims dims) {}

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

        @Override public int weight() { return CarriageWeights.current().weightFor(variant.id()); }
        @Override public String variantName() { return variant.id(); }
        @Override public void restampPlot(ServerLevel level, CarriageDims dims) {
            CarriageEditor.stampPlot(level, variant, dims);
        }
        @Override public Optional<StructureTemplate> bundled(ServerLevel level, CarriageDims dims) {
            return CarriageTemplateStore.getBundled(level, variant, dims);
        }
        @Override public BlockPos editorPlotOrigin(ServerLevel level, CarriageDims dims) {
            return CarriageEditor.plotOrigin(variant, dims);
        }
        @Override public void eraseEditorPlot(ServerLevel level, BlockPos origin, CarriageDims dims) {
            // Carriage hardcoded fallback leaves stale air patches that the
            // bundled stamp won't overwrite — explicit eraseAt before the
            // bundled placeInWorld preserves /dt reset default's fidelity.
            CarriagePlacer.eraseAt(level, origin, dims);
        }
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

        @Override public int weight() { return CarriageContentsWeights.current().weightFor(contents.id()); }
        @Override public String variantName() { return contents.id(); }
        @Override public void restampPlot(ServerLevel level, CarriageDims dims) {
            CarriageContentsEditor.stampPlot(level, contents, dims);
        }
        @Override public Optional<StructureTemplate> bundled(ServerLevel level, CarriageDims dims) {
            // Contents have no separate bundled tier — write-through happens
            // inside CarriageContentsEditor.save when devmode is on. Mirrors
            // the canPromote() = false semantics.
            return Optional.empty();
        }
        @Override public BlockPos editorPlotOrigin(ServerLevel level, CarriageDims dims) {
            return CarriageContentsEditor.plotOrigin(contents, dims);
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

        @Override public int weight() {
            // Parts have no weight pool today — VariantOverlayRenderer
            // handles parts via its own synthetic status path and never
            // calls Template.weight() on a Part. Keep the sentinel so
            // future callers don't NPE.
            return EditorStatusPacket.NO_WEIGHT;
        }
        @Override public String variantName() { return name; }
        @Override public Optional<StructureTemplate> bundled(ServerLevel level, CarriageDims dims) {
            // Parts have no shipped bundled tier today — every part exists
            // as a user-authored template under config/dungeontrain/parts/.
            return Optional.empty();
        }
        @Override public BlockPos editorPlotOrigin(ServerLevel level, CarriageDims dims) {
            return CarriagePartEditor.plotOrigin(partKind, name, dims);
        }
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

        @Override public int weight() { return TrackVariantWeights.weightFor(TrackKind.TILE, name); }
        @Override public String variantName() { return name; }
        @Override public void restampPlot(ServerLevel level, CarriageDims dims) {
            TrackEditor.stampPlot(level, dims);
        }
        @Override public Optional<StructureTemplate> bundled(ServerLevel level, CarriageDims dims) {
            return TrackTemplateStore.getBundled(level, dims);
        }
        @Override public BlockPos editorPlotOrigin(ServerLevel level, CarriageDims dims) {
            return TrackEditor.plotOrigin(dims);
        }
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

        @Override public int weight() {
            return TrackVariantWeights.weightFor(TrackPlotLocator.pillarKind(section), name);
        }
        @Override public String variantName() { return name; }
        @Override public void restampPlot(ServerLevel level, CarriageDims dims) {
            PillarEditor.stampPlot(level, section, dims);
        }
        @Override public Optional<StructureTemplate> bundled(ServerLevel level, CarriageDims dims) {
            return PillarTemplateStore.getBundled(level, section, dims);
        }
        @Override public BlockPos editorPlotOrigin(ServerLevel level, CarriageDims dims) {
            return PillarEditor.plotOrigin(section, dims);
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

        @Override public int weight() {
            return TrackVariantWeights.weightFor(PillarTemplateStore.adjunctKind(adjunct), name);
        }
        @Override public String variantName() { return name; }
        @Override public void restampPlot(ServerLevel level, CarriageDims dims) {
            PillarEditor.stampPlot(level, adjunct, dims);
        }
        @Override public Optional<StructureTemplate> bundled(ServerLevel level, CarriageDims dims) {
            return PillarTemplateStore.getBundledAdjunct(level, adjunct);
        }
        @Override public BlockPos editorPlotOrigin(ServerLevel level, CarriageDims dims) {
            return PillarEditor.plotOriginAdjunct(adjunct, name, dims);
        }
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

        @Override public int weight() {
            return TrackVariantWeights.weightFor(TrackPlotLocator.tunnelKind(variant), name);
        }
        @Override public String variantName() { return name; }
        @Override public void restampPlot(ServerLevel level, CarriageDims dims) {
            TunnelEditor.stampPlot(level, variant);
        }
        @Override public Optional<StructureTemplate> bundled(ServerLevel level, CarriageDims dims) {
            // Tunnel templates have no bundled tier today.
            return Optional.empty();
        }
        @Override public BlockPos editorPlotOrigin(ServerLevel level, CarriageDims dims) {
            return TunnelEditor.plotOrigin(variant);
        }
    }
}
