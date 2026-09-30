package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.building.BuildingCapture;
import games.brennan.dungeontrain.building.BuildingMeta;
import games.brennan.dungeontrain.building.BuildingRegistry;
import games.brennan.dungeontrain.building.BuildingSizes;
import games.brennan.dungeontrain.building.BuildingSourceAuthoring;
import games.brennan.dungeontrain.building.BuildingStore;
import games.brennan.dungeontrain.building.BuildingWorldgen;
import games.brennan.dungeontrain.building.Buildings;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoring buildings — the Lost City / WWOO buildings of the Buildings tab ({@link Buildings}).
 *
 * <p>One row along +X from the shared editor origin, one slot per building. Every slot is as wide as the
 * largest building may be, so resizing one never moves another. A plot is the building at its own size in a
 * bedrock cage; stamping one puts its template back exactly, with air only where the template stores it.</p>
 *
 * <p>Saving captures the cage's box, trims the air outside the building's envelope ({@link BuildingCapture})
 * and writes it to the active package; worldgen then drops its cached copy ({@link BuildingWorldgen#evict}),
 * so the next Lost City chunk generated places the edit.</p>
 */
public final class BuildingEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Plots are one block of cage on each side, then the gap. */
    private static final int STRIDE_PAD = EditorLayout.GAP + 2;
    private static final int STRIDE_X = Buildings.MAX_SIZE.getX() + STRIDE_PAD;
    private static final int ROW_Z = 0;
    /**
     * The Buildings plots stand lower than the shared {@link EditorLayout#PLOT_Y} layer: a building may be
     * {@link Buildings#MAX_SIZE} (159) tall, which does not fit between y=230 and the editor world's ceiling
     * at 320. Only the resident category is ever stamped, so this row never meets another category's.
     */
    public static final int PLOT_Y = 150;
    private static final BlockState OUTLINE = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState PAD = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    /** What each player last entered, which {@code save} writes when they are not standing in a plot. */
    private static final Map<UUID, String> SESSIONS = new ConcurrentHashMap<>();

    private BuildingEditor() {}

    // ---- layout --------------------------------------------------------------

    /** The plot origin of {@code name}, or the next free slot when it is not registered. */
    public static BlockPos plotOrigin(String name) {
        List<String> names = BuildingRegistry.names();
        int index = names.indexOf(name);
        return slotOrigin(index < 0 ? names.size() : index);
    }

    /** The plot origin of registered building {@code name}, or null when no such building is registered. */
    public static BlockPos registeredPlotOrigin(String name) {
        int index = BuildingRegistry.names().indexOf(name);
        return index < 0 ? null : slotOrigin(index);
    }

    private static BlockPos slotOrigin(int index) {
        return new BlockPos(index * STRIDE_X, PLOT_Y, ROW_Z);
    }

    /** The building whose plot (cage included) holds {@code pos}, or empty. Resident category only. */
    public static Optional<String> plotContaining(BlockPos pos) {
        if (EditorStampedCategoryState.current().orElse(null) != EditorCategory.BUILDINGS) return Optional.empty();
        List<String> names = BuildingRegistry.names();
        int index = Math.floorDiv(pos.getX() + 1, STRIDE_X);
        if (index < 0 || index >= names.size()) return Optional.empty();
        String name = names.get(index);
        BlockPos o = slotOrigin(index);
        Vec3i s = BuildingSizes.sizeOf(name);
        boolean inside = pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + s.getX()
            && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + s.getY() + 2
            && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + s.getZ();
        return inside ? Optional.of(name) : Optional.empty();
    }

    /** What {@code player} is working on: the plot they stand in, else the one they last entered. */
    public static Optional<String> current(ServerPlayer player) {
        Optional<String> here = plotContaining(player.blockPosition());
        return here.isPresent() ? here : Optional.ofNullable(SESSIONS.get(player.getUUID()));
    }

    // ---- enter / save --------------------------------------------------------

    /**
     * Stamp {@code name}'s plot and put {@code player} on it. {@code copyOf} seeds a new building from an
     * existing one; null stamps what is saved, or an empty pad for a new name.
     */
    public static void enter(ServerPlayer player, ServerLevel level, String name, String copyOf) {
        CarriageEditor.rememberReturn(player);
        BuildingRegistry.register(name);
        SESSIONS.put(player.getUUID(), name);
        if (copyOf != null) {
            BuildingSizes.setPending(name, BuildingSizes.sizeOf(copyOf));
        }
        BlockPos origin = plotOrigin(name);
        stampPlot(level, name, origin, BuildingStore.readTag(copyOf != null ? copyOf : name).orElse(null));
        EditorPlotArrival.land(player, level, origin, BuildingSizes.sizeOf(name), true,
            EditorPlotArrival.Inside.CENTRE, null);
        LOGGER.info("[DungeonTrain] Building editor: {} entered {} at {}{}", player.getName().getString(),
            name, origin, copyOf != null ? " (copy of " + copyOf + ")" : "");
    }

    /**
     * Put {@code player} at {@code name}'s plot as it stands — nothing restamped, so unsaved edits stay: on
     * the roof in front of its menu, or in the middle of it when {@code centre}.
     */
    public static void walkTo(ServerPlayer player, ServerLevel level, String name, boolean centre) {
        CarriageEditor.rememberReturn(player);
        SESSIONS.put(player.getUUID(), name);
        EditorPlotArrival.land(player, level, plotOrigin(name), BuildingSizes.sizeOf(name), !centre,
            EditorPlotArrival.Inside.CENTRE, null);
    }

    /**
     * Capture {@code name}'s plot and save it — to the source tree too in dev mode.
     *
     * @return true when the source tree was also written
     */
    public static boolean save(ServerPlayer player, ServerLevel level, String name) throws IOException {
        Vec3i size = BuildingSizes.sizeOf(name);
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, plotOrigin(name), size, false, Blocks.STRUCTURE_VOID);
        CompoundTag tag = BuildingCapture.trimOuterAir(template.save(new CompoundTag()));
        boolean toSource = EditorDevMode.isEnabled();
        BuildingStore.save(name, tag, toSource);
        if (!BuildingStore.isShipped(name)) {
            // The weight travels with the building — a pack or a download carries both files.
            BuildingMeta.save(name, BuildingMeta.load(name), toSource);
        } else if (toSource) {
            BuildingSourceAuthoring.markAuthored(name);
        }
        BuildingRegistry.register(name);
        BuildingSizes.settle(name, size);
        BuildingWorldgen.evict(level.getServer(), name);
        captureSnapshot(level, name, plotOrigin(name));
        SESSIONS.put(player.getUUID(), name);
        // …and, when they have opted in, to the player's relay profile. Hooked here rather than at
        // the callers because both ways in land on this method — see EditorRelaySave.
        games.brennan.dungeontrain.editor.relay.EditorRelaySave.afterSave(player, new Template.Building(name));
        return toSource;
    }

    /** Set new building {@code name}'s roster weight. Refused for shipped buildings, whose weight is the set's. */
    public static int setWeight(ServerLevel level, String name, int weight) throws IOException {
        if (BuildingStore.isShipped(name)) {
            throw new IOException("'" + name + "' is a shipped building — its weight is fixed by the Lost City set.");
        }
        BuildingMeta meta = BuildingMeta.load(name).withWeight(weight);
        BuildingMeta.save(name, meta, EditorDevMode.isEnabled());
        BuildingWorldgen.evict(level.getServer(), name);
        return meta.weight();
    }

    /**
     * Grow or shrink {@code name}'s plot by {@code delta} along {@code axis} — the +X/+Y/+Z face moves, so
     * what the author built stays where it is. Blocks left outside a shrunk box are cleared. Unsaved until
     * {@code save}.
     */
    public static Vec3i resize(ServerLevel level, String name, Direction.Axis axis, int delta) {
        BlockPos origin = registeredPlotOrigin(name);
        Vec3i before = BuildingSizes.sizeOf(name);
        if (origin == null) return before;
        Vec3i wanted = switch (axis) {
            case X -> new Vec3i(before.getX() + delta, before.getY(), before.getZ());
            case Y -> new Vec3i(before.getX(), before.getY() + delta, before.getZ());
            case Z -> new Vec3i(before.getX(), before.getY(), before.getZ() + delta);
        };
        paintCage(level, origin, before, Blocks.AIR.defaultBlockState());
        Vec3i after = BuildingSizes.setPending(name, wanted);
        clearOutside(level, origin, before, after);
        paintCage(level, origin, after, OUTLINE);
        return after;
    }

    // ---- stamping ------------------------------------------------------------

    /** Every building plot, as queued jobs — what entering the Buildings tab fills. */
    public static List<EditorStampQueue.Job> stampAllPlotJobs(ServerLevel level) {
        List<EditorStampQueue.Job> jobs = new ArrayList<>();
        for (String name : BuildingRegistry.names()) {
            jobs.add(new EditorStampQueue.Job("stamp building " + name, () -> stampPlot(level, name)));
        }
        return jobs;
    }

    /** Stamp {@code name}'s plot from what is saved. */
    public static void stampPlot(ServerLevel level, String name) {
        BlockPos origin = registeredPlotOrigin(name);
        if (origin == null) return;
        stampPlot(level, name, origin, BuildingStore.readTag(name).orElse(null));
    }

    /** Put {@code name}'s plot back to what is saved — {@code /dt reset}. */
    public static void restamp(ServerLevel level, String name) {
        BlockPos origin = registeredPlotOrigin(name);
        if (origin == null) return;
        BuildingSizes.forget(name);
        stampPlot(level, name, origin, BuildingStore.readTag(name).orElse(null));
    }

    /** Erase {@code name}'s plot, cage included, at the largest size a building can be. */
    public static void clearPlot(ServerLevel level, String name) {
        BlockPos origin = registeredPlotOrigin(name);
        if (origin == null) return;
        eraseSlot(level, origin);
        EditorPlotSnapshots.clear(snapshotKey(name));
    }

    /** Empty {@code name}'s box — {@code editor clear} — keeping its cage and its size. Unsaved until a save. */
    public static void clearBlocks(ServerLevel level, String name) {
        BlockPos origin = registeredPlotOrigin(name);
        if (origin == null) return;
        Vec3i size = BuildingSizes.sizeOf(name);
        fillBox(level, origin, origin.offset(size).offset(-1, -1, -1), Blocks.AIR.defaultBlockState());
    }

    /** Erase every building plot. */
    public static void clearAllPlots(ServerLevel level) {
        for (String name : BuildingRegistry.names()) clearPlot(level, name);
    }

    /**
     * Delete the player's copy of {@code name} — and, in dev mode, a new building's source copy. A shipped
     * building goes back to the jar's; a new one leaves the roster and its plot. Re-lays the row, since the
     * slots after a removed building move up.
     *
     * @return false when there was nothing of the author's to delete
     */
    public static boolean delete(ServerLevel level, String name, boolean fromSource) throws IOException {
        boolean deleted = BuildingStore.deleteFiles(name, fromSource);
        deleted |= BuildingMeta.delete(name);
        if (!deleted && BuildingStore.isBundled(name)) return false;
        clearAllPlots(level);
        BuildingSizes.forget(name);
        BuildingRegistry.reload();
        BuildingWorldgen.evict(level.getServer(), name);
        for (EditorStampQueue.Job job : stampAllPlotJobs(level)) job.work().run();
        SESSIONS.values().removeIf(name::equals);
        return true;
    }

    /** The dirty-check baseline key for {@code name}'s plot. */
    public static String snapshotKey(String name) {
        return EditorPlotSnapshots.key(PlotCategory.BUILDINGS.id(), Buildings.MODEL_ID + ":" + name);
    }

    /** Stamp {@code structure} (or a bare pad when null) on {@code name}'s plot, and take it as the saved baseline. */
    private static void stampPlot(ServerLevel level, String name, BlockPos origin, CompoundTag structure) {
        Vec3i size = BuildingSizes.sizeOf(name);
        eraseSlot(level, origin);
        paintCage(level, origin, size, OUTLINE);
        if (structure == null) {
            fillBox(level, origin, origin.offset(size.getX() - 1, 0, size.getZ() - 1), PAD);
        } else {
            StructureTemplate template = new StructureTemplate();
            template.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), structure);
            template.placeInWorld(level, origin, origin, new StructurePlaceSettings().setIgnoreEntities(true),
                level.getRandom(), CarriageStampGuard.STAMP_FLAGS);
        }
        captureSnapshot(level, name, origin);
    }

    /** The whole slot a building could fill, cage and label headroom included. */
    private static void eraseSlot(ServerLevel level, BlockPos origin) {
        Vec3i max = Buildings.MAX_SIZE;
        fillBox(level, origin.offset(-1, -1, -1), origin.offset(max.getX(), max.getY() + 2, max.getZ()),
            Blocks.AIR.defaultBlockState());
    }

    private static void captureSnapshot(ServerLevel level, String name, BlockPos origin) {
        Vec3i s = BuildingSizes.sizeOf(name);
        EditorPlotSnapshots.capture(snapshotKey(name), level, origin, s.getX(), s.getY(), s.getZ());
    }

    /** Clear what a shrink leaves outside the new box. */
    private static void clearOutside(ServerLevel level, BlockPos origin, Vec3i before, Vec3i after) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos far = origin.offset(before).offset(-1, -1, -1);
        if (after.getX() < before.getX()) fillBox(level, origin.offset(after.getX(), 0, 0), far, air);
        if (after.getY() < before.getY()) fillBox(level, origin.offset(0, after.getY(), 0), far, air);
        if (after.getZ() < before.getZ()) fillBox(level, origin.offset(0, 0, after.getZ()), far, air);
    }

    /** The twelve edges of the box one block outside the plot, in {@code state}. */
    private static void paintCage(ServerLevel level, BlockPos origin, Vec3i size, BlockState state) {
        int x0 = origin.getX() - 1, y0 = origin.getY() - 1, z0 = origin.getZ() - 1;
        int x1 = origin.getX() + size.getX(), y1 = origin.getY() + size.getY(), z1 = origin.getZ() + size.getZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int edges = (x == x0 || x == x1 ? 1 : 0) + (y == y0 || y == y1 ? 1 : 0) + (z == z0 || z == z1 ? 1 : 0);
                    if (edges >= 2) level.setBlock(p.set(x, y, z), state, QUIET);
                }
            }
        }
    }

    private static void fillBox(ServerLevel level, BlockPos min, BlockPos max, BlockState state) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    p.set(x, y, z);
                    BlockState current = level.getBlockState(p);
                    if (current == state) continue;
                    if (current.hasBlockEntity()) level.removeBlockEntity(p);
                    level.setBlock(p, state, QUIET);
                }
            }
        }
    }
}
