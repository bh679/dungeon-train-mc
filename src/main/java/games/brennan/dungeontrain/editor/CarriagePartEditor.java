package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriagePartTemplate;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Editor plots for the four {@link CarriagePartKind}s, laid out as a 2D grid
 * alongside the carriage plots so {@code /dt editor carriages} shows every
 * author-able part at a glance.
 *
 * <p>Grid layout (overworld, {@code Y=250}) — everything packs tightly with
 * a {@link #PLOT_GAP}-block gap between consecutive plot footprints:
 * <ul>
 *   <li>The first row ({@link CarriagePartKind#FLOOR}) starts at
 *       {@link #FIRST_PLOT_Z}; each subsequent kind row starts after the
 *       previous row's Z extent plus the gap — so a compact grid at default
 *       dims fits entirely within ~40 blocks of the carriage row.</li>
 *   <li>Within a row, each registered part name takes an X slot that spans
 *       the kind's X extent plus the gap, so named slots line up without
 *       wasted dead space.</li>
 * </ul>
 *
 * <p>Integration with the editor category system: {@link #stampAllPlots} is
 * called from {@code EditorCommand.runEnterCategory(CARRIAGES)} to paint every
 * plot when the player enters the category, and {@link #clearAllPlots} is hung
 * off {@link EditorCategory#clearAllPlots} so switching categories leaves no
 * stale barriers or templates behind.</p>
 *
 * <p>Per-player session (the {@code (kind, name)} the player is editing) is
 * still tracked in {@link #PART_SESSIONS} so {@code /editor part save} knows
 * the filename to write to. The return-position session is shared with
 * {@link CarriageEditor#rememberReturn} so a single {@code /editor exit}
 * unwinds regardless of which sub-editor the player entered last.</p>
 */
public final class CarriagePartEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** First X slot on any kind row. */
    private static final int FIRST_PLOT_X = 0;

    private static final int PLOT_Y = 250;

    /**
     * First Z row — sourced from {@link EditorLayout#PARTS_FIRST_Z}.
     * Sits inside the CARRIAGES view's Z range, right after the carriage
     * row, with the contents and tracks views shifted to disjoint Z
     * regions past {@link EditorLayout#CARRIAGES_VIEW_MAX_Z} so no other
     * editor's {@code plotContaining} can ever claim a position inside a
     * parts plot.
     */
    private static final int FIRST_PLOT_Z = EditorLayout.PARTS_FIRST_Z;

    /**
     * Block gap between consecutive plot footprints on both X (name slots
     * within a row) and Z (kind rows). Keeps cage barriers visibly separated
     * while letting the grid pack tight at default dims.
     */
    private static final int PLOT_GAP = 5;

    private static final BlockState OUTLINE_BLOCK = Blocks.BEDROCK.defaultBlockState();

    /** (kind, current-name) the player is editing. Empty when the player has never entered a part plot in this session. */
    public record PartSession(CarriagePartKind kind, String name) {}

    /** Where in the world a specific (kind, name) plot can be found. Returned by {@link #plotContaining}. */
    public record PlotLocation(CarriagePartKind kind, String name) {}

    private static final Map<UUID, PartSession> PART_SESSIONS = new HashMap<>();

    private CarriagePartEditor() {}

    /**
     * Plot origin for {@code (kind, name)} at the given world dims. Returns
     * {@code null} when the name isn't currently registered — callers
     * creating a brand-new part should register first, or use
     * {@link #nextFreePlotOrigin} to grab the next free slot on the kind's
     * row.
     *
     * <p>X and Z both step by (kind footprint extent + {@link #PLOT_GAP}) so
     * plots line up with a consistent 5-block gap regardless of dims.</p>
     */
    public static BlockPos plotOrigin(CarriagePartKind kind, String name, CarriageDims dims) {
        int index = nameIndex(kind, name);
        if (index < 0) return null;
        return new BlockPos(
            FIRST_PLOT_X + index * xSlotStride(kind, dims),
            PLOT_Y,
            rowStartZ(kind, dims)
        );
    }

    /**
     * Plot origin for {@code kind} with a generic name that isn't registered
     * yet — used by {@code /editor part enter <kind> <new_name>} before the
     * first save. Picks the next free X slot on the kind's row so the new
     * plot doesn't clobber an existing one.
     */
    public static BlockPos nextFreePlotOrigin(CarriagePartKind kind, CarriageDims dims) {
        int index = CarriagePartRegistry.registeredNames(kind).size();
        return new BlockPos(
            FIRST_PLOT_X + index * xSlotStride(kind, dims),
            PLOT_Y,
            rowStartZ(kind, dims)
        );
    }

    private static int nameIndex(CarriagePartKind kind, String name) {
        List<String> names = CarriagePartRegistry.registeredNames(kind);
        return names.indexOf(name);
    }

    /** X stride between adjacent name slots on the kind's row: kind X extent + gap. */
    private static int xSlotStride(CarriagePartKind kind, CarriageDims dims) {
        return kind.dims(dims).getX() + PLOT_GAP;
    }

    /** Z offset at which {@code kind}'s row begins, accumulated across prior rows' Z extents + gaps. */
    private static int rowStartZ(CarriagePartKind kind, CarriageDims dims) {
        int z = FIRST_PLOT_Z;
        for (CarriagePartKind k : CarriagePartKind.values()) {
            if (k == kind) return z;
            z += k.dims(dims).getZ() + PLOT_GAP;
        }
        return z;
    }

    /** Resolve the plot the player is standing in, or {@code null} if outside every part plot (1-block outline margin included). */
    public static PlotLocation plotContaining(BlockPos pos, CarriageDims dims) {
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            for (String name : CarriagePartRegistry.registeredNames(kind)) {
                BlockPos o = plotOrigin(kind, name, dims);
                if (o == null) continue;
                Vec3i size = kind.dims(dims);
                // +2 Y headroom above cage top — see CarriageEditor.plotContaining.
                if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + size.getX()
                    && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + size.getY() + 2
                    && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + size.getZ()) {
                    return new PlotLocation(kind, name);
                }
            }
        }
        return null;
    }

    /** Back-compat: returns just the kind the player is standing in, null if none. Same input check as {@link #plotContaining}. */
    public static CarriagePartKind plotKindContaining(BlockPos pos, CarriageDims dims) {
        PlotLocation loc = plotContaining(pos, dims);
        return loc == null ? null : loc.kind();
    }

    public static Optional<PartSession> currentSession(ServerPlayer player) {
        return Optional.ofNullable(PART_SESSIONS.get(player.getUUID()));
    }

    /**
     * Outcome of {@link #save} — config-dir write always happens (or throws);
     * the source-tree write is opt-in via {@link EditorDevMode} and reported
     * separately. Copy of {@link CarriageEditor.SaveResult}'s shape so command
     * dispatchers can treat both uniformly.
     */
    public record SaveResult(boolean sourceAttempted, boolean sourceWritten, String sourceError) {
        public static SaveResult skipped() { return new SaveResult(false, false, null); }
        public static SaveResult written() { return new SaveResult(true, true, null); }
        public static SaveResult failed(String error) { return new SaveResult(true, false, error); }
    }

    /**
     * Teleport {@code player} to the plot for {@code (kind, name)}, remembering
     * the return position (shared with {@link CarriageEditor}). Picks
     * {@link #plotOrigin} for an already-registered name or
     * {@link #nextFreePlotOrigin} for a fresh one. Stamps the current template
     * (or a stone-brick starter if nothing's on disk yet) and wraps the cage.
     */
    public static void enter(ServerPlayer player, CarriagePartKind kind, String name) {
        enter(player, kind, name, true);
    }

    public static void enter(ServerPlayer player, CarriagePartKind kind, String name, boolean onTop) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(kind, name, dims);
        if (origin == null) origin = nextFreePlotOrigin(kind, dims);

        CarriageEditor.rememberReturn(player);
        PART_SESSIONS.put(player.getUUID(), new PartSession(kind, name));
        // Register the name immediately so plotContaining (used by
        // EditorStatusHudOverlay, the editor menu auto-stack, and the parts
        // grid stamping) recognises the new plot before the first save.
        // Server-restart cleanup is automatic: CarriagePartRegistry.reload()
        // rebuilds from disk, so an unsaved name drops naturally.
        CarriagePartRegistry.register(kind, name);

        CarriagePartTemplate.eraseAt(overworld, origin, kind, dims);
        stampCurrent(overworld, origin, kind, name, dims);
        setOutline(overworld, origin, kind, dims);

        Vec3i size = kind.dims(dims);
        double tx = origin.getX() + size.getX() / 2.0;
        double ty = onTop
            ? origin.getY() + size.getY() + 1.0
            : origin.getY() + 1.0;
        double tz = origin.getZ() + size.getZ() / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        LOGGER.info("[DungeonTrain] Part editor enter: {} -> {}:{} plot at {} size={}x{}x{} ({})",
            player.getName().getString(), kind.id(), name, origin,
            size.getX(), size.getY(), size.getZ(), onTop ? "top" : "inside");
    }

    /**
     * Source token accepted by {@link #createFrom}: empty starter, copy of the
     * part the player is currently in, or copy of the canonical baseline (the
     * part literally named {@code "standard"}, falling back to the first
     * registered name for the kind).
     */
    public enum NewSource { BLANK, CURRENT, STANDARD }

    /**
     * Create a brand-new part {@code name} of {@code kind} seeded according
     * to {@code source}, save its template to disk, and teleport the player
     * into the new plot. Throws when the name is already registered, when
     * {@link NewSource#CURRENT} is requested but the player is not standing
     * in a part of {@code kind}, or when {@link NewSource#STANDARD} cannot
     * resolve a fallback (e.g. roof has no bundled parts).
     */
    public static BlockPos createFrom(ServerPlayer player, CarriagePartKind kind, NewSource source, String name) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        if (CarriagePartRegistry.isKnown(kind, name)) {
            throw new IOException("Part '" + kind.id() + ":" + name + "' is already registered.");
        }

        StructureTemplate seed = switch (source) {
            case BLANK -> null;
            case CURRENT -> {
                PlotLocation loc = plotContaining(player.blockPosition(), dims);
                if (loc == null) {
                    throw new IOException("Stand inside a " + kind.id() + " plot to copy it.");
                }
                if (loc.kind() != kind) {
                    throw new IOException("Current plot is " + loc.kind().id()
                        + ", not " + kind.id() + ". Stand in a " + kind.id() + " plot to copy.");
                }
                BlockPos srcOrigin = plotOrigin(kind, loc.name(), dims);
                if (srcOrigin == null) {
                    throw new IOException("Could not locate plot for source '" + kind.id() + ":" + loc.name() + "'.");
                }
                yield captureTemplate(overworld, srcOrigin, kind, dims);
            }
            case STANDARD -> {
                String stdName = resolveStandardName(kind);
                if (stdName == null) {
                    throw new IOException("No standard " + kind.id() + " template available to copy.");
                }
                Optional<StructureTemplate> stored = CarriagePartTemplateStore.get(overworld, kind, stdName, dims);
                if (stored.isEmpty()) {
                    throw new IOException("Standard " + kind.id() + " template '" + stdName + "' is missing on disk.");
                }
                yield stored.get();
            }
        };

        // Allocate the next free slot before registering so the index lands at
        // the end of the list (allocation depends on registeredNames().size()).
        BlockPos targetOrigin = nextFreePlotOrigin(kind, dims);
        CarriagePartRegistry.register(kind, name);

        CarriagePartTemplate.eraseAt(overworld, targetOrigin, kind, dims);
        if (seed != null) {
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            seed.placeInWorld(overworld, targetOrigin, targetOrigin, settings, overworld.getRandom(), 3);
        } else {
            stampStarter(overworld, targetOrigin, kind, dims);
        }
        setOutline(overworld, targetOrigin, kind, dims);

        StructureTemplate captured = captureTemplate(overworld, targetOrigin, kind, dims);
        CarriagePartTemplateStore.save(kind, name, captured);

        CarriageEditor.rememberReturn(player);
        PART_SESSIONS.put(player.getUUID(), new PartSession(kind, name));

        Vec3i size = kind.dims(dims);
        double tx = targetOrigin.getX() + size.getX() / 2.0;
        double ty = targetOrigin.getY() + 1.0;
        double tz = targetOrigin.getZ() + size.getZ() / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        LOGGER.info("[DungeonTrain] Part editor createFrom: {} -> {}:{} (source={}) plot at {}",
            player.getName().getString(), kind.id(), name, source, targetOrigin);
        return targetOrigin;
    }

    /**
     * Resolves the seed name for {@link NewSource#STANDARD}: prefer a part
     * literally called {@code "standard"}; otherwise the first registered name
     * for the kind. Returns null if the kind has no registered parts at all
     * (e.g. roof on a fresh install).
     */
    private static String resolveStandardName(CarriagePartKind kind) {
        List<String> names = CarriagePartRegistry.registeredNames(kind);
        if (names.contains("standard")) return "standard";
        if (names.isEmpty()) return null;
        return names.get(0);
    }

    /**
     * Capture the footprint at the plot for {@code (kind, name)} into a fresh
     * {@link StructureTemplate} and persist it via
     * {@link CarriagePartTemplateStore}. Registers the name so future
     * completions and the category-stamp pass include it. When
     * {@link EditorDevMode} is on, also writes through to the source tree.
     */
    public static SaveResult save(ServerPlayer player, CarriagePartKind kind, String name) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        // New names use the "next free" slot that `enter` would have picked —
        // same slot that was stamped if the player entered via the usual path.
        BlockPos origin = plotOrigin(kind, name, dims);
        if (origin == null) origin = nextFreePlotOrigin(kind, dims);

        StructureTemplate template = captureTemplate(overworld, origin, kind, dims);
        CarriagePartTemplateStore.save(kind, name, template);
        CarriagePartRegistry.register(kind, name);

        PART_SESSIONS.put(player.getUUID(), new PartSession(kind, name));

        LOGGER.info("[DungeonTrain] Part editor save: {} -> {}:{} template",
            player.getName().getString(), kind.id(), name);

        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            CarriagePartTemplateStore.saveToSource(kind, name, template);
            // Promote the variants sidecar too — without this, shift-right-click
            // variant authoring stayed in run/config and was lost on worktree
            // delete (the same defect the contents editor had pre-0.66.1).
            Vec3i partSize = kind.dims(dims);
            CarriagePartVariantBlocks sidecar = CarriagePartVariantBlocks.loadFor(kind, name, partSize);
            sidecar.saveToSource(kind, name);
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Part editor save: source write failed for {}:{}: {}",
                kind.id(), name, e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    /**
     * Rename the part {@code (kind, oldName)} to {@code (kind, newName)} —
     * captures the live geometry at the old plot, persists it under the new
     * name, deletes the old name's config file (and source-tree copy in
     * devmode), unregisters the old name unless it has a bundled fallback,
     * relocates the rendered plot, and teleports the player into it.
     *
     * <p>Plot positions are derived from registry order, so the new plot
     * lands at a different X slot than the old one. The player follows.</p>
     *
     * @throws IOException if the old plot can't be located or the config-dir
     *                     write fails. Source-tree write/delete failures are
     *                     reported via the returned {@link SaveResult}.
     */
    public static SaveResult saveAs(ServerPlayer player, CarriagePartKind kind, String oldName, String newName) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        BlockPos oldOrigin = plotOrigin(kind, oldName, dims);
        if (oldOrigin == null) throw new IOException("Unknown part '" + kind.id() + ":" + oldName + "'.");

        StructureTemplate template = captureTemplate(overworld, oldOrigin, kind, dims);

        CarriagePartTemplateStore.save(kind, newName, template);
        CarriagePartRegistry.register(kind, newName);

        clearPlot(overworld, kind, oldName, dims);
        CarriagePartTemplateStore.delete(kind, oldName);
        CarriagePartRegistry.unregister(kind, oldName);

        BlockPos newOrigin = plotOrigin(kind, newName, dims);
        if (newOrigin != null) {
            CarriagePartTemplate.eraseAt(overworld, newOrigin, kind, dims);
            stampCurrent(overworld, newOrigin, kind, newName, dims);
            setOutline(overworld, newOrigin, kind, dims);

            Vec3i size = kind.dims(dims);
            double tx = newOrigin.getX() + size.getX() / 2.0;
            double ty = newOrigin.getY() + 1.0;
            double tz = newOrigin.getZ() + size.getZ() / 2.0;
            player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());
        }

        PART_SESSIONS.put(player.getUUID(), new PartSession(kind, newName));

        LOGGER.info("[DungeonTrain] Part editor saveAs: {} renamed {}:{} -> {}:{}",
            player.getName().getString(), kind.id(), oldName, kind.id(), newName);

        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            CarriagePartTemplateStore.saveToSource(kind, newName, template);
            // Variants sidecar — write under the new name and clean up the old
            // name's source file so the rename leaves no stale bundled resource.
            Vec3i partSize = kind.dims(dims);
            CarriagePartVariantBlocks newSidecar = CarriagePartVariantBlocks.loadFor(kind, newName, partSize);
            newSidecar.saveToSource(kind, newName);
            try {
                Files.deleteIfExists(CarriagePartTemplateStore.sourceFileFor(kind, oldName));
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Part editor saveAs: source-tree delete failed for {}:{}: {}",
                    kind.id(), oldName, e.toString());
            }
            try {
                java.nio.file.Path oldVariantsSrc = CarriagePartVariantBlocks.sourcePathFor(kind, oldName);
                if (oldVariantsSrc != null) Files.deleteIfExists(oldVariantsSrc);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Part editor saveAs: variants source-tree delete failed for {}:{}: {}",
                    kind.id(), oldName, e.toString());
            }
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Part editor saveAs: source write failed for {}:{}: {}",
                kind.id(), newName, e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    /**
     * Stamp the template for {@code (kind, name)} at its designated plot slot,
     * with the barrier cage drawn around it. Used by
     * {@code EditorCommand.runEnterCategory(CARRIAGES)} to paint every part
     * plot so all authorable parts are visible at once. No-op when the name
     * isn't registered.
     */
    public static void stampPlot(ServerLevel level, CarriagePartKind kind, String name, CarriageDims dims) {
        BlockPos origin = plotOrigin(kind, name, dims);
        if (origin == null) return;
        CarriagePartTemplate.eraseAt(level, origin, kind, dims);
        stampCurrent(level, origin, kind, name, dims);
        setOutline(level, origin, kind, dims);
    }

    /**
     * Stamp every registered part plot across all four kinds. Called by the
     * CARRIAGES editor-category entry so switching into the category paints
     * the full 2D parts grid alongside the carriage plots.
     */
    public static void stampAllPlots(ServerLevel level, CarriageDims dims) {
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            for (String name : CarriagePartRegistry.registeredNames(kind)) {
                stampPlot(level, kind, name, dims);
            }
        }
    }

    /**
     * Erase a single plot's footprint + cage back to air. Called on category
     * switch so the barrier grid doesn't persist when the player moves to
     * TRACKS / ARCHITECTURE.
     */
    public static void clearPlot(ServerLevel level, CarriagePartKind kind, String name, CarriageDims dims) {
        BlockPos origin = plotOrigin(kind, name, dims);
        if (origin == null) return;
        CarriagePartTemplate.eraseAt(level, origin, kind, dims);
        clearOutline(level, origin, kind, dims);
    }

    /** Erase every registered part plot. Hung off {@link EditorCategory#clearAllPlots}. */
    public static void clearAllPlots(ServerLevel level, CarriageDims dims) {
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            for (String name : CarriagePartRegistry.registeredNames(kind)) {
                clearPlot(level, kind, name, dims);
            }
        }
    }

    /**
     * Stamp the currently-saved template for {@code name} into the plot, or
     * (if the stored NBT is empty / missing) a stone-brick starter so the
     * first-time author always has a placement surface.
     */
    private static void stampCurrent(ServerLevel level, BlockPos origin, CarriagePartKind kind, String name, CarriageDims dims) {
        Optional<StructureTemplate> stored = CarriagePartTemplateStore.get(level, kind, name, dims);
        if (stored.isPresent()) {
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            stored.get().placeInWorld(level, origin, origin, settings, level.getRandom(), 3);
        }
        if (plotIsEmpty(level, origin, kind, dims)) {
            stampStarter(level, origin, kind, dims);
        }
    }

    private static boolean plotIsEmpty(ServerLevel level, BlockPos origin, CarriagePartKind kind, CarriageDims dims) {
        Vec3i size = kind.dims(dims);
        for (int dx = 0; dx < size.getX(); dx++) {
            for (int dy = 0; dy < size.getY(); dy++) {
                for (int dz = 0; dz < size.getZ(); dz++) {
                    if (!level.getBlockState(origin.offset(dx, dy, dz)).isAir()) return false;
                }
            }
        }
        return true;
    }

    private static void stampStarter(ServerLevel level, BlockPos origin, CarriagePartKind kind, CarriageDims dims) {
        BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
        Vec3i size = kind.dims(dims);
        for (int dx = 0; dx < size.getX(); dx++) {
            for (int dy = 0; dy < size.getY(); dy++) {
                for (int dz = 0; dz < size.getZ(); dz++) {
                    level.setBlock(origin.offset(dx, dy, dz), brick, 3);
                }
            }
        }
        LOGGER.info("[DungeonTrain] Part editor stamped starter bricks for empty {} plot ({}x{}x{})",
            kind.id(), size.getX(), size.getY(), size.getZ());
    }

    private static StructureTemplate captureTemplate(ServerLevel level, BlockPos origin, CarriagePartKind kind, CarriageDims dims) {
        StructureTemplate template = new StructureTemplate();
        Vec3i size = kind.dims(dims);
        template.fillFromWorld(level, origin, size, false, Blocks.AIR);
        return template;
    }

    /** Barrier cage around the 1-outside-footprint bounding box. */
    private static void setOutline(ServerLevel level, BlockPos origin, CarriagePartKind kind, CarriageDims dims) {
        applyOutline(level, origin, kind, dims, OUTLINE_BLOCK);
    }

    /** Erase the barrier cage drawn by {@link #setOutline} — sets every cage edge back to air. */
    private static void clearOutline(ServerLevel level, BlockPos origin, CarriagePartKind kind, CarriageDims dims) {
        applyOutline(level, origin, kind, dims, Blocks.AIR.defaultBlockState());
    }

    private static void applyOutline(ServerLevel level, BlockPos origin, CarriagePartKind kind, CarriageDims dims, BlockState state) {
        Vec3i size = kind.dims(dims);
        int x0 = origin.getX() - 1;
        int y0 = origin.getY() - 1;
        int z0 = origin.getZ() - 1;
        int x1 = origin.getX() + size.getX();
        int y1 = origin.getY() + size.getY();
        int z1 = origin.getZ() + size.getZ();

        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int extremes = (x == x0 || x == x1 ? 1 : 0)
                        + (y == y0 || y == y1 ? 1 : 0)
                        + (z == z0 || z == z1 ? 1 : 0);
                    if (extremes < 2) continue;
                    level.setBlock(new BlockPos(x, y, z), state, 3);
                }
            }
        }
    }

    /** Clear the per-player session map. Wired into server stop via {@link CarriagePartRegistry}. */
    public static synchronized void clearSessions() {
        PART_SESSIONS.clear();
    }
}
