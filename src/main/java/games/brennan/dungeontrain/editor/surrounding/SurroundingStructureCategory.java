package games.brennan.dungeontrain.editor.surrounding;

import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageVariantPartsStore;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.PillarTemplateStore;
import games.brennan.dungeontrain.editor.TrackTemplateStore;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.TrackPlacer;
import games.brennan.dungeontrain.train.CarriagePartAssignment;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Which non-active structures belong around the plot currently being edited, and how to resolve them
 * to a list of {@link PlacementSpec}s.
 *
 * <p>Every resolver below reuses an existing placer/store's own geometry and template lookup — none of
 * them duplicate placement math. Each spec's {@link PlacementSpec#worldOffset()} is relative to the
 * located plot's own {@code editorPlotOrigin}; {@link SurroundingStructureManager} adds that origin
 * before stamping or capturing.</p>
 */
public enum SurroundingStructureCategory {
    TRACK,
    PILLAR,
    DIMENSION_REPEAT,
    FLATBED_END,
    CARRIAGE_SHELL,
    CARRIAGE_INTERIOR_WALL;

    /** Stable lower-case token for commands ({@code /dt editor surrounding track ghost}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<SurroundingStructureCategory> fromId(String raw) {
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(SurroundingStructureCategory.valueOf(raw.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Resolve this category's placements for the plot the player is currently standing in. Returns an
     * empty list when the category has nothing to show for the located plot (e.g. {@code PILLAR} while
     * editing a portal room).
     */
    public List<PlacementSpec> resolve(ServerLevel level, CarriageDims dims, EditorCategory.Located located) {
        return switch (this) {
            case TRACK -> resolveTrack(level, dims);
            case PILLAR -> resolvePillar(level, dims);
            case DIMENSION_REPEAT -> resolveDimensionRepeat(level, dims, located);
            case FLATBED_END -> resolveFlatbedEnd(level, dims, located);
            case CARRIAGE_SHELL -> resolveCarriageShell(level, dims, located);
            case CARRIAGE_INTERIOR_WALL -> resolveCarriageInteriorWall(level, dims, located);
        };
    }

    /**
     * A single representative track tile directly beneath the plot, sourced from
     * {@link TrackTemplateStore} — the same store {@link Template.Track#restampPlot} uses.
     *
     * <p>TODO(surrounding-structures): a real train re-stamps this tile every
     * {@link TrackPlacer#TILE_LENGTH} blocks for the whole run (see {@code TrackGenerator}); this
     * preview shows one tile rather than tiling across the plot's full length.</p>
     */
    private static List<PlacementSpec> resolveTrack(ServerLevel level, CarriageDims dims) {
        BlockPos offset = new BlockPos(0, -TrackPlacer.HEIGHT, 0);
        return List.of(new PlacementSpec(TRACK, offset, Mirror.NONE,
            () -> TrackTemplateStore.get(level, dims)));
    }

    /**
     * A default-variant pillar column (bottom → middle → top, matching {@link PillarSection}'s own
     * ground-up stacking order) just outside the plot's own footprint on Z, sourced from
     * {@link PillarTemplateStore} — the same store {@link Template.Pillar#restampPlot} uses.
     */
    private static List<PlacementSpec> resolvePillar(ServerLevel level, CarriageDims dims) {
        List<PlacementSpec> out = new ArrayList<>(3);
        int zOffset = dims.width() + 1;
        int y = -TrackPlacer.HEIGHT
            - PillarSection.BOTTOM.height() - PillarSection.MIDDLE.height() - PillarSection.TOP.height();
        for (PillarSection section : new PillarSection[]{
                PillarSection.BOTTOM, PillarSection.MIDDLE, PillarSection.TOP}) {
            int sectionY = y;
            y += section.height();
            out.add(new PlacementSpec(PILLAR, new BlockPos(0, sectionY, zOffset), Mirror.NONE,
                () -> PillarTemplateStore.get(level, section, dims)));
        }
        return out;
    }

    /**
     * TODO(surrounding-structures): the repeat cadence for dimension-band decoration (nether / void
     * band transitions) lives deep in {@code TrainAssembler} / {@code TrainCarriageAppender}'s
     * real-train assembly pipeline, keyed off live train state (carriage index, current dimension
     * phase) that doesn't exist for an editor plot in isolation. Extracting it cleanly is out of scope
     * for this pass — ship as a no-op category rather than block the rest of the feature. Revisit once
     * there is a standalone "band cadence for position N" query to call into.
     */
    private static List<PlacementSpec> resolveDimensionRepeat(
        ServerLevel level, CarriageDims dims, EditorCategory.Located located
    ) {
        return List.of();
    }

    /**
     * Flatbed end-caps. There is no distinct "flatbed" template concept — a flatbed is a
     * {@link CarriageType#FLATBED} carriage variant whose {@code parts.json} assigns (usually empty)
     * WALLS/ROOF and real DOORS end-caps — so this reuses {@link #carriageShellSpecs} filtered to the
     * DOORS kind, which is what actually reads as "the flatbed's ends" in the assembled train.
     */
    private static List<PlacementSpec> resolveFlatbedEnd(
        ServerLevel level, CarriageDims dims, EditorCategory.Located located
    ) {
        CarriageVariant variant = carriageVariantOf(located);
        if (variant == null) return List.of();
        boolean isFlatbed = variant instanceof CarriageVariant.Builtin b && b.type() == CarriageType.FLATBED;
        if (!isFlatbed) return List.of();
        return carriageShellSpecs(level, dims, variant, located, CarriagePartKind.DOORS);
    }

    /**
     * The carriage shell (FLOOR/WALLS/ROOF/DOORS) for the carriage the located plot belongs to, minus
     * whichever kind is the currently active plot itself (so the preview never overlaps the real plot
     * being edited).
     */
    private static List<PlacementSpec> resolveCarriageShell(
        ServerLevel level, CarriageDims dims, EditorCategory.Located located
    ) {
        CarriageVariant variant = carriageVariantOf(located);
        if (variant == null) return List.of();
        return carriageShellSpecs(level, dims, variant, located, null);
    }

    /**
     * Only relevant when the located plot is a {@link Template.Contents} (a CONTENTS template) — the
     * interior-facing walls/floor of the carriage the contents will actually be placed inside.
     *
     * <p>TODO(surrounding-structures): contents templates are reusable across every carriage variant
     * whose {@link CarriageDims} match (there is no procgen-interior package, and no per-contents
     * "which carriage" link in {@code CarriageContents}), so there is no single correct carriage to
     * preview here. This previews against the {@code standard} built-in as the most representative
     * shell rather than showing nothing.</p>
     */
    private static List<PlacementSpec> resolveCarriageInteriorWall(
        ServerLevel level, CarriageDims dims, EditorCategory.Located located
    ) {
        if (!(located.model() instanceof Template.Contents)) return List.of();
        Optional<CarriageVariant> representative = CarriageVariantRegistry.find(CarriageType.STANDARD.id());
        if (representative.isEmpty()) return List.of();
        return carriageShellSpecs(level, dims, representative.get(), located, null);
    }

    /**
     * Shared helper: WALLS/FLOOR (interior-facing) + optionally ROOF/DOORS stamps for {@code variant},
     * sourced from {@link CarriageVariantPartsStore} (the parts assignment) and
     * {@link CarriagePartTemplateStore} (the actual template), exactly like the real carriage-part
     * placement pipeline ({@code CarriagePartPlacer} / {@code Template.Part#placeAt}) — just without
     * writing the world here.
     *
     * @param onlyKind when non-null, restrict to that single {@link CarriagePartKind} (used by
     *                 {@link #resolveFlatbedEnd}); when null, every kind except the one the located
     *                 plot is itself standing in.
     */
    private static List<PlacementSpec> carriageShellSpecs(
        ServerLevel level, CarriageDims dims, CarriageVariant variant,
        EditorCategory.Located located, CarriagePartKind onlyKind
    ) {
        Optional<CarriagePartAssignment> assignmentOpt = CarriageVariantPartsStore.get(variant);
        if (assignmentOpt.isEmpty()) return List.of();
        CarriagePartAssignment assignment = assignmentOpt.get();

        CarriagePartKind activePartKind = (located.model() instanceof Template.Part p) ? p.partKind() : null;

        List<PlacementSpec> out = new ArrayList<>();
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            if (onlyKind != null && kind != onlyKind) continue;
            if (kind == activePartKind) continue;
            String name = assignment.pick(kind, 0L, 0);
            if (name == null || CarriagePartKind.NONE.equals(name)) continue;
            String resolvedName = name;
            for (CarriagePartKind.Placement placement : kind.placements(dims)) {
                out.add(new PlacementSpec(CARRIAGE_SHELL, placement.originOffset(), placement.mirror(),
                    () -> CarriagePartTemplateStore.get(level, kind, resolvedName, dims)));
            }
        }
        return out;
    }

    /**
     * The {@link CarriageVariant} the located plot belongs to — direct for {@link Template.Carriage}
     * and {@link Template.Part}, {@code null} otherwise (contents/track/pillar/tunnel/portal plots
     * have no owning carriage variant in the editor grid).
     */
    private static CarriageVariant carriageVariantOf(EditorCategory.Located located) {
        if (located.model() instanceof Template.Carriage c) return c.variant();
        // Parts plots don't carry the variant directly — CarriagePartRegistry has no reverse lookup
        // from (kind, name) back to "which variant assigned this part", since a part can be shared by
        // several variants. Fall back to null; CARRIAGE_SHELL/FLATBED_END simply show nothing while
        // standing in a parts plot rather than guessing an owner.
        return null;
    }
}
