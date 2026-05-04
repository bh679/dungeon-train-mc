package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
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
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.CarriageWeights;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server-side enumerator for the floating name+weight labels that hover above
 * each editor plot in a given category. Mirrors the per-category plot
 * enumeration used by {@link EditorCategory#clearAllPlots} and the
 * {@code runEnterCategory} stamping flow in {@code EditorCommand}, so the
 * labels match exactly what's stamped in the world.
 *
 * <p>Each {@link Label} anchors at the centre of the plot's footprint top,
 * lifted one block above the bedrock cage so it floats clearly above the
 * structure and reads from any vantage point in the row.</p>
 *
 * <p>Carriage parts intentionally pass {@link EditorPlotLabelsPacket#NO_WEIGHT}
 * — parts have no random-pick weight pool (their selection is driven by the
 * carriage variant's parts.json), so the renderer omits the weight line for
 * them.</p>
 */
public final class EditorPlotLabels {

    /**
     * One label entry — world anchor, short display name, weight (or NO_WEIGHT),
     * the action-targeting metadata the floating panel needs to dispatch
     * weight / save / reset / clear / contents on click, and an {@code inPlot}
     * flag that the renderer reads to decide whether to render the interactive
     * rows (weight arrows + save/reset/clear + contents button) — those only
     * show on the panel for the plot the player is currently standing in. The
     * read-only name + weight display still renders on every panel.
     *
     * <p>Empty {@code category} means "this is a parts plot — render the plain
     * name label only, no action rows" (parts have a different save/remove
     * command shape that the first iteration doesn't wire up).</p>
     */
    public record Label(
        BlockPos worldPos,
        String name,
        int weight,
        String category,
        String modelId,
        String modelName,
        boolean inPlot
    ) {
        /** Construct a Label flagged as out-of-plot — the per-category builders use this; the per-player snapshot patches the matching one to inPlot=true. */
        public Label withInPlot(boolean newInPlot) {
            return new Label(worldPos, name, weight, category, modelId, modelName, newInPlot);
        }
    }

    private EditorPlotLabels() {}

    /**
     * Every label that should render for {@code category} at the current world
     * dims. Returns a snapshot — safe to iterate without locking. Empty when
     * the category has no stamped plots ({@link EditorCategory#ARCHITECTURE}
     * today).
     */
    public static List<Label> forCategory(EditorCategory category, CarriageDims dims) {
        return switch (category) {
            case CARRIAGES -> carriageLabels(dims);
            case CONTENTS -> contentsLabels(dims);
            case TRACKS -> trackLabels(dims);
            case ARCHITECTURE -> Collections.emptyList();
        };
    }

    private static List<Label> carriageLabels(CarriageDims dims) {
        List<CarriageVariant> variants = CarriageVariantRegistry.allVariants();
        List<String> floors = CarriagePartRegistry.registeredNames(CarriagePartKind.FLOOR);
        List<String> walls = CarriagePartRegistry.registeredNames(CarriagePartKind.WALLS);
        List<String> roofs = CarriagePartRegistry.registeredNames(CarriagePartKind.ROOF);
        List<String> doors = CarriagePartRegistry.registeredNames(CarriagePartKind.DOORS);

        List<Label> out = new ArrayList<>(
            variants.size() + floors.size() + walls.size() + roofs.size() + doors.size());

        Vec3i carriageFootprint = new Vec3i(dims.length(), dims.height(), dims.width());
        CarriageWeights weights = CarriageWeights.current();
        String category = EditorCategory.CARRIAGES.name();
        for (CarriageVariant v : variants) {
            BlockPos origin = CarriageEditor.plotOrigin(v, dims);
            if (origin == null) continue;
            int w = weights.weightFor(v.id());
            out.add(new Label(anchorAbove(origin, carriageFootprint),
                v.id(), w, category, v.id(), v.id(), false));
        }

        addPartLabels(out, CarriagePartKind.FLOOR, floors, dims);
        addPartLabels(out, CarriagePartKind.WALLS, walls, dims);
        addPartLabels(out, CarriagePartKind.ROOF, roofs, dims);
        addPartLabels(out, CarriagePartKind.DOORS, doors, dims);

        return out;
    }

    private static void addPartLabels(
        List<Label> out, CarriagePartKind kind, List<String> names, CarriageDims dims
    ) {
        Vec3i footprint = kind.dims(dims);
        // {@code category="PARTS"} enables NAME → teleport on the name row of
        // every part panel. Save/Reset/Clear/Contents/weight-arrow rows stay
        // hidden because {@code hasActionRow} / {@code hasContentsButton} /
        // {@code hasWeightArrows} only fire on the actionable categories
        // (CARRIAGES / CONTENTS / TRACKS) — parts have no weight pool and a
        // different save/remove flow the floating panel doesn't wire.
        for (String name : names) {
            BlockPos origin = CarriagePartEditor.plotOrigin(kind, name, dims);
            if (origin == null) continue;
            String label = kind.id() + ":" + name;
            out.add(new Label(anchorAbove(origin, footprint), label,
                EditorPlotLabelsPacket.NO_WEIGHT, "PARTS", kind.id(), name, false));
        }
    }

    private static List<Label> contentsLabels(CarriageDims dims) {
        List<CarriageContents> all = CarriageContentsRegistry.allContents();
        List<Label> out = new ArrayList<>(all.size());
        Vec3i footprint = new Vec3i(dims.length(), dims.height(), dims.width());
        CarriageContentsWeights weights = CarriageContentsWeights.current();
        String category = EditorCategory.CONTENTS.name();
        for (CarriageContents c : all) {
            BlockPos origin = CarriageContentsEditor.plotOrigin(c, dims);
            if (origin == null) continue;
            int w = weights.weightFor(c.id());
            out.add(new Label(anchorAbove(origin, footprint),
                c.id(), w, category, c.id(), c.id(), false));
        }
        return out;
    }

    private static List<Label> trackLabels(CarriageDims dims) {
        List<Label> out = new ArrayList<>();

        // Track tile — every registered name (default + customs).
        addTrackKindLabels(out, TrackKind.TILE, dims);

        // Pillars — bottom / middle / top, each with their named variants.
        for (PillarSection s : PillarSection.values()) {
            addTrackKindLabels(out, PillarTemplateStore.pillarKind(s), dims);
        }

        // Pillar adjuncts (stairs, etc.).
        for (PillarAdjunct a : PillarAdjunct.values()) {
            addTrackKindLabels(out, PillarTemplateStore.adjunctKind(a), dims);
        }

        // Tunnels — section + portal, each with their named variants.
        for (TunnelVariant v : TunnelVariant.values()) {
            addTrackKindLabels(out, TunnelTemplateStore.tunnelKind(v), dims);
        }

        return out;
    }

    private static void addTrackKindLabels(List<Label> out, TrackKind kind, CarriageDims dims) {
        Vec3i footprint = TrackSidePlots.footprint(kind, dims);
        String category = EditorCategory.TRACKS.name();
        // For TRACKS the modelId is the kind tag (e.g. "tile", "pillar_bottom")
        // — that's what the existing weight commands key on. modelName is the
        // bare variant name segment.
        String modelId = kind.id();
        for (String name : TrackVariantRegistry.namesFor(kind)) {
            BlockPos origin = TrackSidePlots.plotOrigin(kind, name, dims);
            int w = TrackVariantWeights.weightFor(kind, name);
            out.add(new Label(anchorAbove(origin, footprint),
                name, w, category, modelId, name, false));
        }
    }

    /**
     * Centre of the footprint top, lifted one block above the bedrock cage.
     * The cage extends one block past the footprint on every face, so adding
     * {@code footprint.y + 2} clears the top of the cage with a one-block
     * gap.
     */
    private static BlockPos anchorAbove(BlockPos origin, Vec3i footprint) {
        return new BlockPos(
            origin.getX() + footprint.getX() / 2,
            origin.getY() + footprint.getY() + 2,
            origin.getZ() + footprint.getZ() / 2
        );
    }
}
