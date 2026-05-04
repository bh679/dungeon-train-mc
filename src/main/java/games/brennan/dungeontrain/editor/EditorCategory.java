package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Top-level grouping for the editor. Each category exposes an ordered list of
 * {@link Template}s so commands like {@code /dt editor <category>} and
 * {@code /dt save all} can iterate without caring about the underlying
 * storage split between carriages, pillars, and tunnels.
 *
 * <ul>
 *   <li>{@link #CARRIAGES} — every registered {@link CarriageVariant}.</li>
 *   <li>{@link #CONTENTS} — every registered {@link CarriageContents}.</li>
 *   <li>{@link #TRACKS} — the open-air track tile, then pillars
 *       ({@code bottom → middle → top}), then tunnels
 *       ({@code section → portal}).</li>
 *   <li>{@link #ARCHITECTURE} — placeholder, no models yet (walls, floor,
 *       roof coming later).</li>
 * </ul>
 */
public enum EditorCategory {
    CARRIAGES("Carriages"),
    CONTENTS("Contents"),
    TRACKS("Tracks"),
    ARCHITECTURE("Architecture");

    private final String displayName;

    EditorCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Stable lower-case token used in commands ({@code /dt editor tracks}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Every model in this category, in the order a player walks through them. */
    public List<Template> models() {
        return switch (this) {
            case CARRIAGES -> carriageModels();
            case CONTENTS -> contentsModels();
            case TRACKS -> trackModels();
            case ARCHITECTURE -> List.of();
        };
    }

    /** The landing model when a player runs {@code /dt editor <category>}. */
    public Optional<Template> firstModel() {
        List<Template> models = models();
        return models.isEmpty() ? Optional.empty() : Optional.of(models.get(0));
    }

    /** Parse a command argument back to a category. Case-insensitive. */
    public static Optional<EditorCategory> fromId(String raw) {
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(EditorCategory.valueOf(raw.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Resolve which category + model the player's block position falls inside.
     * Checks carriage plots first, then carriage parts (which sit inside the
     * CARRIAGES Z range), then contents, then track tile, then pillars, then
     * tunnels.
     *
     * <p>Phase 2 added the parts arm — before it, walking into a part plot
     * and running {@code /dt save} fell through to the "Not in an editor
     * plot" failure or the carriage-shell save (depending on Z), neither
     * correct.</p>
     */
    public static Optional<Located> locate(ServerPlayer player, CarriageDims dims) {
        BlockPos pos = player.blockPosition();
        CarriageVariant carriage = CarriageEditor.plotContaining(pos, dims);
        if (carriage != null) {
            return Optional.of(new Located(CARRIAGES, new Template.Carriage(carriage)));
        }
        CarriagePartEditor.PlotLocation partLoc = CarriagePartEditor.plotContaining(pos, dims);
        if (partLoc != null) {
            return Optional.of(new Located(CARRIAGES,
                new Template.Part(partLoc.kind(), partLoc.name())));
        }
        CarriageContents contents = CarriageContentsEditor.plotContaining(pos, dims);
        if (contents != null) {
            return Optional.of(new Located(CONTENTS, new Template.Contents(contents)));
        }
        String trackName = TrackEditor.resolveName(pos, dims);
        if (trackName != null) {
            return Optional.of(new Located(TRACKS, new Template.Track(trackName)));
        }
        PillarEditor.SectionPlot pillarLoc = PillarEditor.plotContaining(pos, dims);
        if (pillarLoc != null) {
            return Optional.of(new Located(TRACKS,
                new Template.Pillar(pillarLoc.section(), pillarLoc.name())));
        }
        PillarEditor.AdjunctPlot adjunctLoc = PillarEditor.plotContainingAdjunct(pos, dims);
        if (adjunctLoc != null) {
            return Optional.of(new Located(TRACKS,
                new Template.Adjunct(adjunctLoc.adjunct(), adjunctLoc.name())));
        }
        TunnelEditor.TunnelPlot tunnelLoc = TunnelEditor.plotContainingNamed(pos);
        if (tunnelLoc != null) {
            return Optional.of(new Located(TRACKS,
                new Template.Tunnel(tunnelLoc.variant(), tunnelLoc.name())));
        }
        return Optional.empty();
    }

    private static List<Template> carriageModels() {
        List<CarriageVariant> variants = CarriageVariantRegistry.allVariants();
        List<Template> out = new ArrayList<>(variants.size() + 16);
        // Shells first — preserves the existing landing-model behaviour
        // (firstModel returns the first carriage variant, used by /dt editor
        // carriages teleport).
        for (CarriageVariant v : variants) {
            out.add(new Template.Carriage(v));
        }
        // Phase-4 Goal 3: parts join the iteration so /dt save all from a
        // carriage shell also covers the parts grid. Kind-major ordering
        // (FLOOR all variants, then WALLS, etc.) mirrors the physical row
        // layout in CarriagePartEditor's grid.
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            for (String name : CarriagePartRegistry.registeredNames(kind)) {
                out.add(new Template.Part(kind, name));
            }
        }
        return out;
    }

    private static List<Template> contentsModels() {
        List<CarriageContents> all = CarriageContentsRegistry.allContents();
        List<Template> out = new ArrayList<>(all.size());
        for (CarriageContents c : all) {
            out.add(new Template.Contents(c));
        }
        return out;
    }

    private static List<Template> trackModels() {
        // Phase-4 Bug A fix: enumerate every registered name per kind, not
        // just the synthetic default. Pre-Phase-4 only default-named variants
        // appeared here, so /dt save all from the tracks editor silently
        // skipped every custom-named pillar / adjunct / tunnel / track tile
        // the player had authored. TrackVariantRegistry.namesFor guarantees
        // DEFAULT_NAME is first, so the existing row order is preserved as a
        // degenerate case (one entry per kind on a fresh install).
        List<Template> out = new ArrayList<>();
        // Track tile first — it's the "default" track model, most used.
        for (String name : TrackVariantRegistry.namesFor(TrackKind.TILE)) {
            out.add(new Template.Track(name));
        }
        // Ground-up pillar ordering mirrors physical stacking.
        for (PillarSection section : new PillarSection[]{
                PillarSection.BOTTOM, PillarSection.MIDDLE, PillarSection.TOP}) {
            TrackKind kind = PillarTemplateStore.pillarKind(section);
            for (String name : TrackVariantRegistry.namesFor(kind)) {
                out.add(new Template.Pillar(section, name));
            }
        }
        // Pillar adjuncts (stairs) sit alongside the pillar column physically;
        // expose them as their own row of variants right after the pillars.
        for (PillarAdjunct a : PillarAdjunct.values()) {
            TrackKind kind = PillarTemplateStore.adjunctKind(a);
            for (String name : TrackVariantRegistry.namesFor(kind)) {
                out.add(new Template.Adjunct(a, name));
            }
        }
        for (TunnelVariant v : TunnelVariant.values()) {
            TrackKind kind = TunnelTemplateStore.tunnelKind(v);
            for (String name : TrackVariantRegistry.namesFor(kind)) {
                out.add(new Template.Tunnel(v, name));
            }
        }
        return out;
    }

    /** A category + which specific model the player is standing in. */
    public record Located(EditorCategory category, Template model) {}

    /**
     * Erase every known editor plot in every category — footprints + barrier
     * cages all go back to air. Called when the player exits the editor and
     * when switching categories so stale models don't pile up at Y=250. Cheap
     * — the total is a handful of plots ({@code CarriageVariantRegistry} size
     * + 3 pillars + 2 tunnels).
     */
    public static void clearAllPlots(ServerLevel overworld, CarriageDims dims) {
        // Tearing down every plot also invalidates the floating plot labels —
        // VariantOverlayRenderer reads this state on the next tick and pushes
        // an empty snapshot so the labels disappear in lockstep with the
        // structures.
        EditorStampedCategoryState.clear();
        for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
            CarriageEditor.clearPlot(overworld, v, dims);
        }
        for (CarriageContents c : CarriageContentsRegistry.allContents()) {
            CarriageContentsEditor.clearPlot(overworld, c, dims);
        }
        TrackEditor.clearPlot(overworld, dims);
        for (PillarSection s : PillarSection.values()) {
            PillarEditor.clearPlot(overworld, s, dims);
        }
        for (PillarAdjunct a : PillarAdjunct.values()) {
            PillarEditor.clearPlotAdjunct(overworld, a, dims);
        }
        for (TunnelVariant t : TunnelVariant.values()) {
            TunnelEditor.clearPlot(overworld, t);
        }
        // Parts live adjacent to carriages (Z=80+ rows) but span no other
        // category, so we clear them alongside everything else when switching.
        CarriagePartEditor.clearAllPlots(overworld, dims);
    }
}
