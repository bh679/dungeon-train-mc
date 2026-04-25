package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageContentsTemplate;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageTemplate;
import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

/**
 * Editor plots for {@link CarriageContents} — fixed overworld locations at
 * {@code z = 80} (offset from the carriage row at {@code z = 0}, pillar row at
 * {@code z = 40}) where OPs build interior layouts. Each plot stamps a chosen
 * carriage shell as non-editable context so the author can see how the
 * contents fit inside walls + floor + ceiling; only the interior volume is
 * captured on save.
 *
 * <p>Reuses the {@link CarriageEditor} session map via
 * {@link CarriageEditor#rememberReturn} so a single
 * {@code /dungeontrain editor exit} command restores the player regardless of
 * which editor they entered. Same pattern as {@link PillarEditor}.</p>
 */
public final class CarriageContentsEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PLOT_Y = 250;
    /** Contents row Z-origin. Carriages occupy {@code Z=0} (max width 32),
     *  so {@code 32 + EditorLayout.GAP = 37} keeps the contents row clear of
     *  any carriage plot at any width. */
    private static final int PLOT_Z = CarriageDims.MAX_WIDTH + EditorLayout.GAP;
    private static final int FIRST_PLOT_X = 0;

    private static final BlockState OUTLINE_BLOCK = Blocks.BEDROCK.defaultBlockState();

    /** Fallback shell variant stamped as context when the user doesn't specify one. */
    private static final CarriageVariant DEFAULT_SHELL = CarriageVariant.of(CarriageType.STANDARD);

    public record SaveResult(boolean sourceAttempted, boolean sourceWritten, String sourceError) {
        public static SaveResult skipped() { return new SaveResult(false, false, null); }
        public static SaveResult written() { return new SaveResult(true, true, null); }
        public static SaveResult failed(String error) { return new SaveResult(true, false, error); }
    }

    private CarriageContentsEditor() {}

    /**
     * Re-stamp the plot for {@code contents} in-place: fresh shell, fresh
     * contents, fresh barrier cage. Used by {@code runEnterCategory(CONTENTS)}
     * to materialise every registered contents plot at once so the player can
     * walk between them, mirroring the carriages/tracks category enter flow.
     */
    public static void stampPlot(ServerLevel overworld, CarriageContents contents, CarriageDims dims) {
        BlockPos origin = plotOrigin(contents, dims);
        if (origin == null) return;
        CarriageTemplate.eraseAt(overworld, origin, dims);
        CarriageContentsTemplate.eraseAt(overworld, origin, dims);
        CarriageTemplate.placeAt(overworld, origin, DEFAULT_SHELL, dims);
        CarriageContentsTemplate.placeAt(overworld, origin, contents, dims);
        setOutline(overworld, origin, OUTLINE_BLOCK, dims);
    }

    /**
     * Erase the plot for {@code contents} — shell + interior back to air,
     * barrier cage removed. Called by {@code EditorCategory.clearAllPlots}
     * when switching categories.
     */
    public static void clearPlot(ServerLevel overworld, CarriageContents contents, CarriageDims dims) {
        BlockPos origin = plotOrigin(contents, dims);
        if (origin == null) return;
        CarriageTemplate.eraseAt(overworld, origin, dims);
        CarriageContentsTemplate.eraseAt(overworld, origin, dims);
        setOutline(overworld, origin, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), dims);
    }

    /**
     * Plot origin for {@code contents}. Step along {@code +X} is
     * {@code dims.length() + EditorLayout.GAP} so adjacent plots have a
     * uniform {@link EditorLayout#GAP}-block air gap, matching every other
     * editor.
     */
    public static BlockPos plotOrigin(CarriageContents contents, CarriageDims dims) {
        List<CarriageContents> all = CarriageContentsRegistry.allContents();
        String target = contents.id();
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

    /** Legacy single-arg overload that defaults to a min-length carriage. */
    public static BlockPos plotOrigin(CarriageContents contents) {
        return plotOrigin(contents, CarriageDims.clamp(
            CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT));
    }

    /**
     * Returns the contents whose plot contains {@code pos} (within the
     * footprint plus 1-block outline margin), or {@code null} if none. Matches
     * the signature of {@link CarriageEditor#plotContaining} so
     * {@code EditorCommand} can dispatch on the same {@link CarriageDims}.
     */
    public static CarriageContents plotContaining(BlockPos pos, CarriageDims dims) {
        for (CarriageContents contents : CarriageContentsRegistry.allContents()) {
            BlockPos o = plotOrigin(contents, dims);
            if (o == null) continue;
            if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + dims.length()
                && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + dims.height()
                && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + dims.width()) {
                return contents;
            }
        }
        return null;
    }

    /**
     * Teleport {@code player} to the plot for {@code contents}: save return
     * position, clear the footprint, stamp the chosen {@code shellVariant}
     * (visual context — walls/floor/ceiling), then stamp the current contents
     * template on top. Finally draw the barrier cage and teleport inside.
     *
     * <p>The shell blocks are not protected — if the author breaks a wall it
     * won't affect the saved contents template (save captures only the
     * interior volume). Re-entering will re-stamp the shell.</p>
     */
    public static void enter(ServerPlayer player, CarriageContents contents, CarriageVariant shellVariant) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(contents, dims);
        if (origin == null) {
            LOGGER.warn("[DungeonTrain] Contents editor enter: unknown contents '{}'", contents.id());
            return;
        }
        CarriageVariant shell = shellVariant != null ? shellVariant : DEFAULT_SHELL;

        CarriageEditor.rememberReturn(player);

        CarriageTemplate.eraseAt(overworld, origin, dims);
        // Also discard any entities left from a previous edit session
        // (armor stands / item frames / paintings don't get cleared by the
        // block-only erase above). Must run before the shell + contents stamp
        // so the freshly stamped NBT entities don't get caught up in this.
        CarriageContentsTemplate.eraseAt(overworld, origin, dims);
        // Stamp the shell first — this fills floor/walls/ceiling as context.
        // Uses the 4-arg placeAt so variant-block sidecar entries don't get
        // applied here (the author is editing contents, not the shell).
        CarriageTemplate.placeAt(overworld, origin, shell, dims);
        // Stamp the current contents template on top of the air interior.
        CarriageContentsTemplate.placeAt(overworld, origin, contents, dims);
        setOutline(overworld, origin, OUTLINE_BLOCK, dims);

        double tx = origin.getX() + dims.length() / 2.0;
        double ty = origin.getY() + 1.0;
        double tz = origin.getZ() + dims.width() / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        LOGGER.info("[DungeonTrain] Contents editor enter: {} -> {} (shell={}) plot at {} dims={}x{}x{}",
            player.getName().getString(), contents.id(), shell.id(), origin,
            dims.length(), dims.width(), dims.height());
    }

    /**
     * Capture the interior volume at the plot for {@code contents} into a
     * fresh {@link StructureTemplate} and persist it via
     * {@link CarriageContentsStore}. Shell blocks are outside the captured
     * region so are naturally excluded — no shell-protection logic needed at
     * save time. When {@link EditorDevMode} is on, the template is also
     * written to the source tree so it ships with the next build.
     */
    public static SaveResult save(ServerPlayer player, CarriageContents contents) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(contents, dims);
        if (origin == null) throw new IOException("Unknown contents '" + contents.id() + "'.");

        StructureTemplate template = CarriageContentsTemplate.captureTemplate(overworld, origin, dims);
        CarriageContentsStore.save(contents, template);

        LOGGER.info("[DungeonTrain] Contents editor save: {} -> {} template interior={}x{}x{}",
            player.getName().getString(), contents.id(),
            Math.max(0, dims.length() - 2), Math.max(0, dims.height() - 2), Math.max(0, dims.width() - 2));

        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            CarriageContentsStore.saveToSource(contents, template);
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Contents editor save: source write failed for {}: {}", contents.id(), e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    /**
     * Create a new custom contents {@code target} whose template is a
     * duplicate of {@code source}'s current geometry. Registers the contents
     * immediately so it gets its own plot on subsequent lookups.
     */
    public static BlockPos duplicate(ServerPlayer player, CarriageContents source, CarriageContents.Custom target) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        if (!CarriageContentsRegistry.register(target)) {
            throw new IOException("Contents '" + target.id() + "' is already registered.");
        }

        BlockPos targetOrigin = plotOrigin(target);
        if (targetOrigin == null) {
            CarriageContentsRegistry.unregister(target.id());
            throw new IOException("Failed to allocate plot for '" + target.id() + "'.");
        }

        // Stamp the default shell as context, then stamp the source contents
        // on top. Capture the interior region and save under the new id.
        CarriageTemplate.eraseAt(overworld, targetOrigin, dims);
        CarriageContentsTemplate.eraseAt(overworld, targetOrigin, dims);
        CarriageTemplate.placeAt(overworld, targetOrigin, DEFAULT_SHELL, dims);
        CarriageContentsTemplate.placeAt(overworld, targetOrigin, source, dims);

        StructureTemplate template = CarriageContentsTemplate.captureTemplate(overworld, targetOrigin, dims);
        CarriageContentsStore.save(target, template);

        setOutline(overworld, targetOrigin, OUTLINE_BLOCK, dims);

        LOGGER.info("[DungeonTrain] Contents editor duplicate: {} created '{}' from '{}' at {}",
            player.getName().getString(), target.id(), source.id(), targetOrigin);
        return targetOrigin;
    }

    /**
     * Save the plot's current interior under a new name — mirrors the
     * rename-on-save behaviour of {@link CarriageEditor#saveAs}. Built-in
     * {@code default} cannot be renamed; customs are moved to the new name.
     */
    public static CarriageContents.Custom saveAs(ServerPlayer player, CarriageContents current, CarriageContents.Custom renamed) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        BlockPos origin = plotOrigin(current);
        if (origin == null) throw new IOException("Unknown contents '" + current.id() + "'.");
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        StructureTemplate template = CarriageContentsTemplate.captureTemplate(overworld, origin, dims);

        if (current instanceof CarriageContents.Custom currentCustom) {
            if (!CarriageContentsRegistry.register(renamed)) {
                throw new IOException("Name '" + renamed.id() + "' is already taken.");
            }
            CarriageContentsStore.save(renamed, template);
            CarriageContentsRegistry.unregister(currentCustom.name());
            CarriageContentsStore.delete(currentCustom);
            LOGGER.info("[DungeonTrain] Contents editor saveAs (custom→custom): {} renamed '{}' -> '{}'",
                player.getName().getString(), currentCustom.name(), renamed.id());
        } else if (current instanceof CarriageContents.Builtin builtin) {
            if (!CarriageContentsRegistry.register(renamed)) {
                throw new IOException("Name '" + renamed.id() + "' is already taken.");
            }
            CarriageContentsStore.save(renamed, template);
            CarriageContentsStore.delete(builtin);
            LOGGER.info("[DungeonTrain] Contents editor saveAs (builtin→custom): {} saved edits of '{}' as new custom '{}', built-in reverts to fallback",
                player.getName().getString(), builtin.id(), renamed.id());
        }

        return renamed;
    }

    /**
     * Barrier cage: 12 edges of a bounding box 1 block outside the
     * {@code length × height × width} footprint. Matches
     * {@link CarriageEditor#setOutline} exactly so the cage geometry is
     * consistent across both editors. Faces are left empty so the player can
     * fly in and out freely.
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

    /**
     * Helper used by {@code editor contents new <name> [shell_variant]}:
     * resolve the shell context variant for a new-contents call, falling back
     * to {@link #DEFAULT_SHELL} if {@code shellId} is null or missing.
     */
    public static CarriageVariant resolveShellOrDefault(String shellId) {
        if (shellId == null) return DEFAULT_SHELL;
        return CarriageVariantRegistry.find(shellId).orElse(DEFAULT_SHELL);
    }
}
