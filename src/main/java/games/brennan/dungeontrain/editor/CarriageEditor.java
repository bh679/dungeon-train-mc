package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageTemplate;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Editor plots for {@link CarriageVariant}s — fixed high-Y overworld locations
 * where players build their own carriage variants. Plots are arranged along
 * +X so every variant is visible at once; the list auto-expands as custom
 * variants are registered.
 *
 * Session state (pre-enter position + dimension + look angles) is kept
 * per-player in RAM. Lost on server restart, which is acceptable for an
 * OP-only dev tool.
 */
public final class CarriageEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PLOT_Y = 250;
    private static final int PLOT_Z = 0;
    private static final int FIRST_PLOT_X = 0;

    private static final BlockState OUTLINE_BLOCK = Blocks.BEDROCK.defaultBlockState();

    public record Session(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private CarriageEditor() {}

    /**
     * Record {@code player}'s current dimension + position + look as the
     * return-to location for {@code /dungeontrain editor exit}. No-op if the
     * player already has a saved session, so re-entering an editor plot (even
     * across carriage and pillar editors) keeps the first entry as the anchor.
     *
     * <p>Package-private so {@link PillarEditor} can share the session map
     * without duplicating the exit plumbing.</p>
     */
    static void rememberReturn(ServerPlayer player) {
        SESSIONS.putIfAbsent(player.getUUID(), new Session(
            player.level().dimension(),
            player.position(),
            player.getYRot(),
            player.getXRot()
        ));
    }

    /**
     * Outcome of {@link #save} — config-dir write always happens (or throws);
     * the source-tree write is opt-in via {@link EditorDevMode} and reported
     * separately so the caller can surface partial success. Source-tree writes
     * only apply to {@link CarriageVariant.Builtin}s; custom variants never
     * ship bundled so source-tree is always {@link #skipped()} for them.
     */
    public record SaveResult(boolean sourceAttempted, boolean sourceWritten, String sourceError) {
        public static SaveResult skipped() { return new SaveResult(false, false, null); }
        public static SaveResult written() { return new SaveResult(true, true, null); }
        public static SaveResult failed(String error) { return new SaveResult(true, false, error); }
    }

    /**
     * Plot origin for {@code variant}. Step along {@code +X} is
     * {@code CarriageDims.length() + EditorLayout.GAP} so adjacent plots
     * always have a uniform {@link EditorLayout#GAP}-block air gap between
     * footprints — matching the rule used in {@link CarriagePartEditor},
     * {@link TrackSidePlots}, and {@link CarriageContentsEditor}. Returns
     * {@code null} if the variant is not registered.
     */
    public static BlockPos plotOrigin(CarriageVariant variant, CarriageDims dims) {
        List<CarriageVariant> all = CarriageVariantRegistry.allVariants();
        String target = variant.id();
        int index = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(target)) {
                index = i;
                break;
            }
        }
        if (index < 0) return null;
        int step = dims.length() + EditorLayout.GAP;
        return new BlockPos(FIRST_PLOT_X + index * step, PLOT_Y, PLOT_Z);
    }

    /**
     * Legacy single-arg overload that defaults to a min-length carriage. Kept
     * so callers without {@link CarriageDims} on hand still compile; new code
     * should pass dims so plot positions match the live world's settings.
     */
    public static BlockPos plotOrigin(CarriageVariant variant) {
        return plotOrigin(variant, CarriageDims.clamp(
            CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT));
    }

    /**
     * Returns the variant whose plot contains {@code pos} (within the
     * footprint plus 1-block outline margin), or {@code null} if none.
     */
    public static CarriageVariant plotContaining(BlockPos pos, CarriageDims dims) {
        for (CarriageVariant variant : CarriageVariantRegistry.allVariants()) {
            BlockPos o = plotOrigin(variant, dims);
            if (o == null) continue;
            if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + dims.length()
                && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + dims.height()
                && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + dims.width()) {
                return variant;
            }
        }
        return null;
    }

    /**
     * Teleport {@code player} to the plot for {@code variant}: save return
     * position, clear the footprint, stamp the current template (or fallback
     * geometry) so the player sees what would spawn today, then place the
     * barrier-block cage around the footprint.
     */
    public static void enter(ServerPlayer player, CarriageVariant variant) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(variant, dims);
        if (origin == null) {
            LOGGER.warn("[DungeonTrain] Editor enter: unknown variant '{}'", variant.id());
            return;
        }

        rememberReturn(player);
        stampPlot(overworld, variant, dims);

        double tx = origin.getX() + dims.length() / 2.0;
        double ty = origin.getY() + 1.0;
        double tz = origin.getZ() + dims.width() / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        LOGGER.info("[DungeonTrain] Editor enter: {} -> {} plot at {} dims={}x{}x{}",
            player.getName().getString(), variant.id(), origin, dims.length(), dims.width(), dims.height());
    }

    /**
     * Erase, place, and cage the plot for {@code variant} without teleporting
     * anyone. Used by {@link #enter} and by category-wide stamps
     * ({@code /dt editor carriages}) that need every plot visible at once.
     * Idempotent — calling it twice against the same variant re-applies the
     * current stored template.
     */
    public static void stampPlot(ServerLevel overworld, CarriageVariant variant, CarriageDims dims) {
        BlockPos origin = plotOrigin(variant, dims);
        if (origin == null) return;

        // Drop any stale cached sidecar so each stamp picks up manual JSON
        // edits made since the last load. Session editing then works against
        // the freshly-loaded map; `editor save` persists the result.
        CarriageVariantBlocks.invalidate(variant.id());

        CarriageTemplate.eraseAt(overworld, origin, dims);
        CarriageTemplate.placeAt(overworld, origin, variant, dims);
        setOutline(overworld, origin, OUTLINE_BLOCK, dims);
    }

    /**
     * Erase the plot for {@code variant} — footprint cleared to air and the
     * barrier cage around it removed. Used when switching categories (leaves
     * no stale carriages visible once the player moves on to tracks) and on
     * {@code /dt editor exit} (tidy the sky-plots when nobody is editing).
     */
    public static void clearPlot(ServerLevel overworld, CarriageVariant variant, CarriageDims dims) {
        BlockPos origin = plotOrigin(variant, dims);
        if (origin == null) return;
        CarriageTemplate.eraseAt(overworld, origin, dims);
        setOutline(overworld, origin, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), dims);
    }

    /**
     * Capture the {@code length × height × width} region at the plot for
     * {@code variant} into a fresh {@link StructureTemplate} and persist it
     * via {@link CarriageTemplateStore}. Air positions are excluded so the
     * saved template only describes placed blocks. When {@link EditorDevMode}
     * is on and the variant is a built-in, the same template is also written
     * through to the source tree so it ships with the next mod build.
     */
    public static SaveResult save(ServerPlayer player, CarriageVariant variant) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(variant, dims);
        if (origin == null) throw new IOException("Unknown variant '" + variant.id() + "'.");

        StructureTemplate template = captureTemplate(overworld, origin, dims);
        CarriageTemplateStore.save(variant, template);

        // Variant sidecar: snapshot whatever the in-memory cache holds (the
        // `editor variant set/clear` commands mutate it eagerly during the
        // session). Empty maps are written as a deleted file so removing every
        // entry doesn't leave a stale sidecar on disk.
        CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(variant, dims);
        sidecar.save(variant);

        LOGGER.info("[DungeonTrain] Editor save: {} -> {} template dims={}x{}x{} ({} variant entries)",
            player.getName().getString(), variant.id(), dims.length(), dims.width(), dims.height(),
            sidecar.size());

        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        if (!(variant instanceof CarriageVariant.Builtin builtin)) return SaveResult.skipped();
        try {
            CarriageTemplateStore.saveToSource(builtin.type(), template);
            sidecar.saveToSource(builtin.type());
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Editor save: source write failed for {}: {}", variant.id(), e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    /**
     * Create a new custom variant {@code target} whose template is a duplicate
     * of {@code source}'s current geometry. Registers the variant so it
     * immediately shows up in {@link CarriageVariantRegistry} and gets its own
     * plot on subsequent lookups.
     *
     * <p>Fails if {@code source} resolves to no template (i.e. a custom with a
     * missing file). Built-in sources always succeed because they fall back
     * through the three-tier store to bundled or hardcoded geometry.
     */
    public static BlockPos duplicate(ServerPlayer player, CarriageVariant source, CarriageVariant.Custom target) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        if (!CarriageVariantRegistry.register(target)) {
            throw new IOException("Variant '" + target.id() + "' is already registered.");
        }

        BlockPos targetOrigin = plotOrigin(target);
        if (targetOrigin == null) {
            CarriageVariantRegistry.unregister(target.id());
            throw new IOException("Failed to allocate plot for '" + target.id() + "'.");
        }

        CarriageTemplate.eraseAt(overworld, targetOrigin, dims);
        CarriageTemplate.placeAt(overworld, targetOrigin, source, dims);

        StructureTemplate template = captureTemplate(overworld, targetOrigin, dims);
        if (template.getSize().equals(Vec3i.ZERO)) {
            CarriageVariantRegistry.unregister(target.id());
            throw new IOException("Source '" + source.id() + "' produced no geometry.");
        }
        CarriageTemplateStore.save(target, template);

        // Copy the source's variant sidecar into the new variant so the
        // duplicate shares the "pick from these blocks" authoring.
        CarriageVariantBlocks sourceSidecar = CarriageVariantBlocks.loadFor(source, dims);
        if (!sourceSidecar.isEmpty()) {
            CarriageVariantBlocks copy = CarriageVariantBlocks.empty();
            for (CarriageVariantBlocks.Entry e : sourceSidecar.entries()) {
                copy.put(e.localPos(), e.states());
            }
            copy.save(target);
        }

        setOutline(overworld, targetOrigin, OUTLINE_BLOCK, dims);

        LOGGER.info("[DungeonTrain] Editor duplicate: {} created '{}' from '{}' at {}",
            player.getName().getString(), target.id(), source.id(), targetOrigin);
        return targetOrigin;
    }

    /**
     * Save the plot's current geometry under a new name. Follows the
     * rename-on-save rules documented in {@code EditorCommand}: protected
     * built-ins ({@code standard}, {@code flatbed}) cannot be renamed; other
     * built-ins revert to hardcoded fallback and the edited geometry is saved
     * as a new custom; custom sources are moved to the new name.
     *
     * <p>Returns the new variant identifier.
     */
    public static CarriageVariant.Custom saveAs(ServerPlayer player, CarriageVariant current, CarriageVariant.Custom renamed) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        BlockPos origin = plotOrigin(current);
        if (origin == null) throw new IOException("Unknown variant '" + current.id() + "'.");
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        StructureTemplate template = captureTemplate(overworld, origin, dims);

        if (current instanceof CarriageVariant.Custom currentCustom) {
            if (!CarriageVariantRegistry.register(renamed)) {
                throw new IOException("Name '" + renamed.id() + "' is already taken.");
            }
            CarriageTemplateStore.save(renamed, template);
            CarriageVariantBlocks.rename(currentCustom.name(), renamed.id());
            CarriageVariantRegistry.unregister(currentCustom.name());
            CarriageTemplateStore.delete(currentCustom);
            CarriageVariantBlocks.invalidate(currentCustom.name());
            LOGGER.info("[DungeonTrain] Editor saveAs (custom→custom): {} renamed '{}' -> '{}'",
                player.getName().getString(), currentCustom.name(), renamed.id());
        } else if (current instanceof CarriageVariant.Builtin builtin) {
            if (!CarriageVariantRegistry.register(renamed)) {
                throw new IOException("Name '" + renamed.id() + "' is already taken.");
            }
            CarriageTemplateStore.save(renamed, template);
            CarriageVariantBlocks.rename(builtin.id(), renamed.id());
            CarriageTemplateStore.delete(builtin);
            CarriageVariantBlocks.invalidate(builtin.id());
            LOGGER.info("[DungeonTrain] Editor saveAs (builtin→custom): {} saved edits of '{}' as new custom '{}', built-in reverts to fallback",
                player.getName().getString(), builtin.id(), renamed.id());
        }

        return renamed;
    }

    /** Restore player to pre-enter position/dimension. Returns false if no session. */
    public static boolean exit(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session == null) return false;
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        ServerLevel dim = server.getLevel(session.dimension());
        if (dim == null) return false;
        player.teleportTo(dim, session.pos().x, session.pos().y, session.pos().z,
            session.yaw(), session.pitch());
        VariantOverlayRenderer.forget(player);
        return true;
    }

    private static StructureTemplate captureTemplate(ServerLevel level, BlockPos origin, CarriageDims dims) {
        StructureTemplate template = new StructureTemplate();
        Vec3i size = new Vec3i(dims.length(), dims.height(), dims.width());
        template.fillFromWorld(level, origin, size, false, Blocks.AIR);
        return template;
    }

    /**
     * Draw the cage: barrier blocks along the 12 edges of the bounding box
     * that sits 1 block outside the {@code length × height × width} footprint.
     * Faces are left empty so the player can fly in and out freely; barriers
     * are invisible in survival and render as translucent red in
     * creative/spectator.
     */
    private static void setOutline(ServerLevel level, BlockPos origin, BlockState state, CarriageDims dims) {
        int x0 = origin.getX() - 1;
        int y0 = origin.getY() - 1;
        int z0 = origin.getZ() - 1;
        int x1 = origin.getX() + dims.length();
        int y1 = origin.getY() + dims.height();
        int z1 = origin.getZ() + dims.width();

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
}
