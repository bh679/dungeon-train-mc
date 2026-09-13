package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
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
 *   <li>{@link #PORTALS} — the pocket room a portal carriage group's two
 *       corridors open into. Its own category because it is the one piece of
 *       a portal that is not carriage-shaped: the corridor
 *       ({@code portal}) and the cart between the corridors
 *       ({@code portal_middle}) are both carriage variants and live under
 *       {@link #CARRIAGES}.</li>
 *   <li>{@link #ARCHITECTURE} — placeholder, no models yet (walls, floor,
 *       roof coming later).</li>
 * </ul>
 */
public enum EditorCategory {
    CARRIAGES("Carriages"),
    CONTENTS("Contents"),
    TRACKS("Tracks"),
    PORTALS("Portals"),
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
            case PORTALS -> portalModels();
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
        String roomName = PortalRoomEditor.plotContaining(pos, dims);
        if (roomName != null) {
            return Optional.of(new Located(PORTALS, new Template.PortalRoom(roomName)));
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

    /**
     * Every registered portal room, {@code default} first — the same
     * {@link TrackVariantRegistry} name ordering the track-side kinds use.
     */
    private static List<Template> portalModels() {
        List<String> names = TrackVariantRegistry.namesFor(TrackKind.PORTAL_ROOM);
        List<Template> out = new ArrayList<>(names.size());
        for (String name : names) {
            out.add(new Template.PortalRoom(name));
        }
        return out;
    }

    /** A category + which specific model the player is standing in. */
    public record Located(EditorCategory category, Template model) {}

    /**
     * Erase every known editor plot in every category — footprints + barrier
     * cages all go back to air. Called when the player exits the editor and
     * when switching categories so stale models don't pile up at the plot floor.
     *
     * <p><b>Not cheap.</b> Every registered carriage, contents template, part, track tile, pillar,
     * tunnel and portal room has a plot, and each erase writes air unconditionally — a Z-span of
     * thousands of blocks at {@link EditorLayout#PLOT_Y}, several hundred chunk columns, every one
     * forced to load. On a world where nothing was ever stamped that whole sweep is wasted, so it is
     * skipped until {@link DungeonTrainWorldData#editorPlotsStamped()} says a plot may hold blocks
     * — which this method itself records, since every stamp is preceded by a call here.</p>
     *
     * <p>Runs every erase now. A category entry uses {@link #clearAllPlotJobs} instead and lets
     * {@link EditorStampQueue} spread the erases across ticks.</p>
     */
    public static void clearAllPlots(ServerLevel overworld, CarriageDims dims) {
        for (EditorStampQueue.Job job : clearAllPlotJobs(overworld, dims)) {
            job.work().run();
        }
    }

    /**
     * The state half of {@link #clearAllPlots} now — labels, strays, undo history, the queue, the
     * stamped flag — and the block half as jobs, one per plot, for the caller to run or queue.
     *
     * <p>The state resets cannot wait: the labels are read on the next tick and must already know
     * the old category is gone, and any fill still in flight would otherwise stamp into plots this
     * clear is about to erase. Empty on a fresh world (see {@link #clearAllPlots}).</p>
     */
    public static List<EditorStampQueue.Job> clearAllPlotJobs(ServerLevel overworld, CarriageDims dims) {
        return clearAllPlotJobs(overworld, dims, null);
    }

    /**
     * {@link #clearAllPlotJobs(ServerLevel, CarriageDims)} minus the erases of {@code keep}'s own
     * plots. For a category entry: every stamp in a category erases its own footprint before it
     * places, so erasing those plots separately would only double the work — and, queued behind the
     * first plot the entry stamps synchronously, would wipe that plot out from under the player.
     */
    public static List<EditorStampQueue.Job> clearAllPlotJobs(ServerLevel overworld, CarriageDims dims,
                                                             EditorCategory keep) {
        // A fill still running would stamp into plots this clear tears down — and would then be
        // torn down itself by the erases below, in whichever order the two happened to interleave.
        EditorStampQueue.cancel();
        // Tearing down every plot also invalidates the floating plot labels —
        // VariantOverlayRenderer reads this state on the next tick and pushes
        // an empty snapshot so the labels disappear in lockstep with the
        // structures.
        EditorStampedCategoryState.clear();
        // Nothing is stamped any more, so there is nothing left for a block to be outside of —
        // every ghost would now be pointing at a plot that no longer exists.
        EditorStrayBlocks.clear();
        // Every plot below is about to be torn down, so an undo step that placed blocks would now
        // write into an empty plot floor. Staleness would catch that on use; dropping it here is
        // the honest signal. Menu changes are kept: a weight or a stage link is a file on disk and
        // undoes the same whether its template is stamped in the world or not.
        EditorEditHistory.clearWorldBackedSteps();
        EditorEditRecorder.discardPending();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.editorPlotsStamped()) {
            // Fresh world: no plot has ever held a block, so there is nothing to erase. Whatever
            // the caller stamps next is the first thing in the sky — mark it so the next clear runs.
            data.markEditorPlotsStamped();
            return List.of();
        }
        List<EditorStampQueue.Job> jobs = new ArrayList<>();
        if (keep != CARRIAGES) {
            for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
                jobs.add(new EditorStampQueue.Job("erase carriage " + v.id(),
                    () -> CarriageEditor.clearPlot(overworld, v, dims)));
            }
            // Parts live adjacent to carriages (Z=80+ rows) but span no other
            // category, so we clear them alongside everything else when switching. One job: the
            // parts clear erases whole rows, which does not split cleanly per plot.
            jobs.add(new EditorStampQueue.Job("erase carriage parts",
                () -> CarriagePartEditor.clearAllPlots(overworld, dims)));
        }
        if (keep != CONTENTS) {
            for (CarriageContents c : CarriageContentsRegistry.allContents()) {
                jobs.add(new EditorStampQueue.Job("erase contents " + c.id(),
                    () -> CarriageContentsEditor.clearPlot(overworld, c, dims)));
            }
        }
        if (keep != TRACKS) {
            jobs.add(new EditorStampQueue.Job("erase track plots", () -> TrackEditor.clearPlot(overworld, dims)));
            for (PillarSection s : PillarSection.values()) {
                jobs.add(new EditorStampQueue.Job("erase pillar " + s,
                    () -> PillarEditor.clearPlot(overworld, s, dims)));
            }
            for (PillarAdjunct a : PillarAdjunct.values()) {
                jobs.add(new EditorStampQueue.Job("erase adjunct " + a,
                    () -> PillarEditor.clearPlotAdjunct(overworld, a, dims)));
            }
            for (TunnelVariant t : TunnelVariant.values()) {
                jobs.add(new EditorStampQueue.Job("erase tunnel " + t,
                    () -> TunnelEditor.clearPlot(overworld, t)));
            }
        }
        if (keep != PORTALS) {
            // One job: the room clear also sweeps the column for what earlier layouts left.
            jobs.add(new EditorStampQueue.Job("erase portal rooms",
                () -> PortalRoomEditor.clearAllPlots(overworld, dims)));
        }
        return jobs;
    }
}
