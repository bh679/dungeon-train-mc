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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Editor plots for the four {@link CarriagePartKind}s — one fixed plot per
 * kind, stacked on the +Z axis past the carriage and pillar rows so every
 * authoring surface is visible from a single vantage.
 *
 * <p>The name of the part currently in each plot is tracked per-player in
 * {@link #PART_SESSIONS} so {@code /dungeontrain editor part save} knows what
 * filename to write to. The return-position session is shared with
 * {@link CarriageEditor#rememberReturn} so a single {@code /editor exit}
 * restores regardless of which editor the player entered last.</p>
 *
 * <p>Plot layout (overworld, {@code Y=250}):
 * <ul>
 *   <li>{@link CarriagePartKind#FLOOR}  — {@code Z=80}  (footprint {@code (L-2)×1×(W-2)})</li>
 *   <li>{@link CarriagePartKind#WALLS}  — {@code Z=120} (footprint {@code (L-2)×H×1})</li>
 *   <li>{@link CarriagePartKind#ROOF}   — {@code Z=160} (footprint {@code (L-2)×1×(W-2)})</li>
 *   <li>{@link CarriagePartKind#DOORS}  — {@code Z=200} (footprint {@code 1×H×W})</li>
 * </ul>
 * All four share {@code X=0}; the {@code Z} offsets leave clearance for the
 * widest carriage/pillar rows at max dims (32×24×32) and keep the rows
 * visibly separated.</p>
 */
public final class CarriagePartEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PLOT_X = 0;
    private static final int PLOT_Y = 250;
    private static final int FIRST_PLOT_Z = 80;
    /** Z-step between consecutive part rows. Wide enough for max dims (width=32) plus 8-block breathing room. */
    private static final int PLOT_STEP_Z = 40;

    private static final BlockState OUTLINE_BLOCK = Blocks.BARRIER.defaultBlockState();

    /** (kind, current-name) the player is editing. Empty when the player has never entered a part plot in this session. */
    public record PartSession(CarriagePartKind kind, String name) {}

    private static final Map<UUID, PartSession> PART_SESSIONS = new HashMap<>();

    private CarriagePartEditor() {}

    /**
     * Plot origin for {@code kind} — fixed across the world lifetime. Each
     * plot's footprint starts at this origin and extends along {@code +X}
     * (length), {@code +Y} (height), and {@code +Z} (width) by the per-kind
     * {@link CarriagePartKind#dims(CarriageDims)} extents.
     */
    public static BlockPos plotOrigin(CarriagePartKind kind) {
        return new BlockPos(PLOT_X, PLOT_Y, FIRST_PLOT_Z + PLOT_STEP_Z * kind.ordinal());
    }

    /**
     * Which kind's plot (if any) contains {@code pos}, within the footprint plus
     * a 1-block outline margin. Returns {@code null} if {@code pos} is outside
     * every part plot.
     */
    public static CarriagePartKind plotContaining(BlockPos pos, CarriageDims dims) {
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            BlockPos o = plotOrigin(kind);
            Vec3i size = kind.dims(dims);
            if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + size.getX()
                && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + size.getY()
                && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + size.getZ()) {
                return kind;
            }
        }
        return null;
    }

    /**
     * The part name the player is currently editing in the given kind's plot,
     * or empty if they have not yet entered this plot in the current session.
     */
    public static Optional<PartSession> currentSession(ServerPlayer player) {
        return Optional.ofNullable(PART_SESSIONS.get(player.getUUID()));
    }

    /**
     * Outcome of {@link #save} — config-dir write always happens (or throws);
     * the source-tree write is opt-in via {@link EditorDevMode} and reported
     * separately. Copy of {@link CarriageEditor.SaveResult}'s shape so
     * command dispatchers can treat both uniformly.
     */
    public record SaveResult(boolean sourceAttempted, boolean sourceWritten, String sourceError) {
        public static SaveResult skipped() { return new SaveResult(false, false, null); }
        public static SaveResult written() { return new SaveResult(true, true, null); }
        public static SaveResult failed(String error) { return new SaveResult(true, false, error); }
    }

    /**
     * Teleport {@code player} to the plot for {@code kind}, remember the
     * return position (shared with {@link CarriageEditor}), erase the
     * footprint, stamp whichever template currently backs {@code name} (empty
     * cage if neither config nor bundled tier has it), and wrap the cage with
     * barrier blocks. The current (kind, name) is stored in
     * {@link #PART_SESSIONS} so a subsequent {@code save} knows where to write.
     */
    public static void enter(ServerPlayer player, CarriagePartKind kind, String name) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(kind);

        CarriageEditor.rememberReturn(player);
        PART_SESSIONS.put(player.getUUID(), new PartSession(kind, name));

        CarriagePartTemplate.eraseAt(overworld, origin, kind, dims);
        stampCurrent(overworld, origin, kind, name, dims);
        setOutline(overworld, origin, kind, dims);

        Vec3i size = kind.dims(dims);
        double tx = origin.getX() + size.getX() / 2.0;
        double ty = origin.getY() + 1.0;
        double tz = origin.getZ() + size.getZ() / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        LOGGER.info("[DungeonTrain] Part editor enter: {} -> {}:{} plot at {} size={}x{}x{}",
            player.getName().getString(), kind.id(), name, origin,
            size.getX(), size.getY(), size.getZ());
    }

    /**
     * Capture the footprint at the plot for the player's current session into
     * a fresh {@link StructureTemplate} and persist it via
     * {@link CarriagePartTemplateStore}. The name is registered in
     * {@link CarriagePartRegistry} so future completions include it. When
     * {@link EditorDevMode} is on, the template is also written to the source
     * tree so it ships with the next build.
     *
     * <p>If {@code newName} is non-null, saves under the new name (registering
     * it) rather than the session's current name; callers should ensure the
     * session is then updated to the new name so subsequent edits go to the
     * same file.</p>
     */
    public static SaveResult save(ServerPlayer player, CarriagePartKind kind, String name) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(kind);

        StructureTemplate template = captureTemplate(overworld, origin, kind, dims);
        CarriagePartTemplateStore.save(kind, name, template);
        CarriagePartRegistry.register(kind, name);

        PART_SESSIONS.put(player.getUUID(), new PartSession(kind, name));

        LOGGER.info("[DungeonTrain] Part editor save: {} -> {}:{} template",
            player.getName().getString(), kind.id(), name);

        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            CarriagePartTemplateStore.saveToSource(kind, name, template);
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Part editor save: source write failed for {}:{}: {}",
                kind.id(), name, e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    /**
     * Stamp the currently-saved template for {@code name} into the plot. When
     * neither the template exists on disk nor the stored NBT contains any
     * blocks, fall back to {@link #stampStarter} so the plot is never left as
     * pure air — without a surface to anchor against, the first-time author
     * can't right-click to place blocks, which is what leaves
     * {@code wood.nbt}-style empty-save templates circulating.
     */
    private static void stampCurrent(ServerLevel level, BlockPos origin, CarriagePartKind kind, String name, CarriageDims dims) {
        Optional<StructureTemplate> stored = CarriagePartTemplateStore.get(level, kind, name, dims);
        if (stored.isPresent()) {
            // The part template's native footprint is already kind.dims(dims) sized,
            // so a straight unmirrored stamp at the plot origin fills it exactly.
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            stored.get().placeInWorld(level, origin, origin, settings, level.getRandom(), 3);
        }
        // A template that exists but contains zero non-air blocks still lands
        // an empty stamp, so we re-check after placing — any time the plot is
        // still all air, stamp a starter surface.
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

    /**
     * Fill the plot's full footprint with stone bricks as a first-time author
     * starter. The player can break / replace individual blocks to customise,
     * and a straight {@code /editor save} captures the starter as-is — gives
     * the monolithic fallback in {@code tryComposeFromParts} a non-empty
     * template to stamp, avoiding the zero-block carriage entirely.
     */
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
                    level.setBlock(new BlockPos(x, y, z), OUTLINE_BLOCK, 3);
                }
            }
        }
    }

    /** Clear the per-player session map. Wired into server stop via {@link CarriagePartRegistry}. */
    public static synchronized void clearSessions() {
        PART_SESSIONS.clear();
    }
}
