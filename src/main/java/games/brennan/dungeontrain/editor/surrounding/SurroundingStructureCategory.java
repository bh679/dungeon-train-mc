package games.brennan.dungeontrain.editor.surrounding;

import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageVariantPartsStore;
import games.brennan.dungeontrain.editor.PillarTemplateStore;
import games.brennan.dungeontrain.editor.TrackTemplateStore;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.TrackGenerator;
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
 * Which structures belong around the build the Train Builder currently has open, and how to resolve
 * each to a list of {@link PlacementSpec}s.
 *
 * <p>Everything here is <em>context</em>: the structures a finished build will sit against but which
 * aren't themselves being edited. Every resolver reuses an existing placer/store's own geometry and
 * template lookup — none duplicate placement math — and each spec's
 * {@link PlacementSpec#worldOffset()} is relative to the {@link BuilderPlot#origin()}.
 * {@link SurroundingStructureManager} adds that origin before stamping or capturing.</p>
 *
 * <p>Each category tiles across the build's own dimensions, so a long train previews the whole run of
 * structure it will stand on rather than a single representative tile at one end.</p>
 */
public enum SurroundingStructureCategory {
    TRACK,
    PILLAR,
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
     * Resolve this category's placements around the open build. Returns an empty list when the
     * category has nothing to show for it — pillars under a portal room, a carriage shell while the
     * open build <em>is</em> that shell.
     */
    public List<PlacementSpec> resolve(ServerLevel level, BuilderPlot plot) {
        return switch (this) {
            case TRACK -> resolveTrack(level, plot);
            case PILLAR -> resolvePillar(level, plot);
            case FLATBED_END -> resolveFlatbedEnd(level, plot);
            case CARRIAGE_SHELL -> resolveCarriageShell(level, plot);
            case CARRIAGE_INTERIOR_WALL -> resolveCarriageInteriorWall(level, plot);
        };
    }

    /**
     * The track beneath the build, tiled the way a real run lays it: one {@link TrackTemplateStore}
     * tile every {@link TrackPlacer#TILE_LENGTH} blocks of X for the parked train's whole length.
     *
     * <p>The builder world already stamps a real track through its corridor, so this is the preview
     * of the line <em>as the generator would tile it</em> — which is what tells you whether the build
     * sits right on it. Set this category to {@code none} when the stamped corridor is enough.</p>
     */
    private static List<PlacementSpec> resolveTrack(ServerLevel level, BuilderPlot plot) {
        CarriageDims dims = plot.dims();
        List<PlacementSpec> out = new ArrayList<>();
        for (int x = 0; x < plot.trainLength(); x += TrackPlacer.TILE_LENGTH) {
            out.add(new PlacementSpec(TRACK, new BlockPos(x, -TrackPlacer.HEIGHT, 0), Mirror.NONE,
                () -> TrackTemplateStore.get(level, dims)));
        }
        return out;
    }

    /**
     * Default-variant pillar columns just outside the build's footprint on Z, sourced from
     * {@link PillarTemplateStore} — the same store {@link Template.Pillar#restampPlot} uses — repeated
     * along the train at the generator's own cadence ({@link TrackGenerator#BASE_PILLAR_SPACING}).
     *
     * <p>Each column stacks bottom → middle → top, matching {@link PillarSection}'s own ground-up
     * order. A builder world is flat, so the generator's height-scaled spacing never kicks in and the
     * base spacing is the correct one to mirror.</p>
     */
    private static List<PlacementSpec> resolvePillar(ServerLevel level, BuilderPlot plot) {
        CarriageDims dims = plot.dims();
        List<PlacementSpec> out = new ArrayList<>();
        int zOffset = dims.width() + 1;
        int baseY = -TrackPlacer.HEIGHT
            - PillarSection.BOTTOM.height() - PillarSection.MIDDLE.height() - PillarSection.TOP.height();
        for (int x = 0; x < plot.trainLength(); x += TrackGenerator.BASE_PILLAR_SPACING) {
            int y = baseY;
            for (PillarSection section : new PillarSection[]{
                    PillarSection.BOTTOM, PillarSection.MIDDLE, PillarSection.TOP}) {
                int sectionY = y;
                y += section.height();
                out.add(new PlacementSpec(PILLAR, new BlockPos(x, sectionY, zOffset), Mirror.NONE,
                    () -> PillarTemplateStore.get(level, section, dims)));
            }
        }
        return out;
    }

    /**
     * The flatbed end-caps at each end of the parked train — "what the train looks like from outside"
     * at its extremities.
     *
     * <p>There is no distinct flatbed template concept: a flatbed is a {@link CarriageType#FLATBED}
     * variant whose parts assign (usually empty) WALLS/ROOF and real DOORS end-caps, so this reuses
     * {@link #carriageShellSpecs} filtered to DOORS, placed at the first and last carriage.</p>
     */
    private static List<PlacementSpec> resolveFlatbedEnd(ServerLevel level, BuilderPlot plot) {
        if (plot.carriages() <= 0) return List.of();
        Optional<CarriageVariant> flatbed = CarriageVariantRegistry.find(CarriageType.FLATBED.id());
        if (flatbed.isEmpty()) return List.of();

        List<PlacementSpec> out = new ArrayList<>();
        int lastX = (plot.carriages() - 1) * plot.dims().length();
        for (int x : lastX == 0 ? new int[]{0} : new int[]{0, lastX}) {
            for (PlacementSpec spec : carriageShellSpecs(level, plot, flatbed.get(), CarriagePartKind.DOORS)) {
                out.add(spec.offsetBy(x));
            }
        }
        return out;
    }

    /**
     * The carriage shell (FLOOR/WALLS/ROOF/DOORS) around every parked carriage, minus whichever part
     * kind is the open build itself — so the preview never fights the thing being edited.
     *
     * <p>Shown for the outside-facing modes. When the build <em>is</em> a whole carriage there is no
     * separate shell to show, so this stays empty.</p>
     */
    private static List<PlacementSpec> resolveCarriageShell(ServerLevel level, BuilderPlot plot) {
        if (plot.carriages() <= 0) return List.of();
        if (plot.mode() != BuilderMode.TRAIN_OUTSIDE) return List.of();
        CarriageVariant variant = openVariant(plot);
        if (variant == null) return List.of();
        return everyCarriage(plot, carriageShellSpecs(level, plot, variant, null));
    }

    /**
     * The interior-facing walls and floor of the carriage the build will sit inside — the
     * {@link BuilderMode#INSIDE_CARRIAGE} case, where what you're judging is how contents read against
     * the shell around them.
     */
    private static List<PlacementSpec> resolveCarriageInteriorWall(ServerLevel level, BuilderPlot plot) {
        if (plot.carriages() <= 0) return List.of();
        if (plot.mode() != BuilderMode.INSIDE_CARRIAGE) return List.of();
        CarriageVariant variant = openVariant(plot);
        if (variant == null) return List.of();
        return everyCarriage(plot, carriageShellSpecs(level, plot, variant, null));
    }

    /** Repeat one carriage's worth of specs across every parked carriage. */
    private static List<PlacementSpec> everyCarriage(BuilderPlot plot, List<PlacementSpec> perCarriage) {
        if (perCarriage.isEmpty()) return List.of();
        List<PlacementSpec> out = new ArrayList<>(perCarriage.size() * plot.carriages());
        for (int i = 0; i < plot.carriages(); i++) {
            int x = i * plot.dims().length();
            for (PlacementSpec spec : perCarriage) {
                out.add(spec.offsetBy(x));
            }
        }
        return out;
    }

    /**
     * The variant the world was stamped from, which is the shell the build belongs to. Falls back to
     * the builtin STANDARD when the world doesn't name one, so a preview still appears rather than
     * silently nothing.
     */
    private static CarriageVariant openVariant(BuilderPlot plot) {
        return CarriageVariantRegistry.find(CarriageType.STANDARD.id()).orElse(null);
    }

    /**
     * Shared helper: the part stamps for {@code variant}, sourced from
     * {@link CarriageVariantPartsStore} (the parts assignment) and {@link CarriagePartTemplateStore}
     * (the actual template), exactly like the real carriage-part placement pipeline
     * ({@code CarriagePartPlacer}) — just without writing the world here.
     *
     * @param onlyKind when non-null, restrict to that single {@link CarriagePartKind}; when null,
     *                 every kind except the one the open build is itself editing
     */
    private static List<PlacementSpec> carriageShellSpecs(
        ServerLevel level, BuilderPlot plot, CarriageVariant variant, CarriagePartKind onlyKind
    ) {
        Optional<CarriagePartAssignment> assignmentOpt = CarriageVariantPartsStore.get(variant);
        if (assignmentOpt.isEmpty()) return List.of();
        CarriagePartAssignment assignment = assignmentOpt.get();
        CarriageDims dims = plot.dims();

        List<PlacementSpec> out = new ArrayList<>();
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            if (onlyKind != null && kind != onlyKind) continue;
            // The part being edited is the build, not context around it.
            if (onlyKind == null && kind.id().equalsIgnoreCase(plot.partKindId())) continue;
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
}
