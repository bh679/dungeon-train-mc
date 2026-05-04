package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
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
    /**
     * Contents row Z-origin — sourced from {@link
     * EditorLayout#CONTENTS_FIRST_Z}. Sits in its own Z range past the
     * CARRIAGES view (which extends through the parts grid), so the
     * contents row never overlaps a parts plot in plan view and the FLOOR
     * parts editor's air column can't pick up stale contents-interior
     * blocks from a prior CONTENTS visit.
     */
    private static final int PLOT_Z = EditorLayout.CONTENTS_FIRST_Z;
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
        CarriagePlacer.eraseAt(overworld, origin, dims);
        CarriageContentsPlacer.eraseAt(overworld, origin, dims);
        CarriagePlacer.placeAt(overworld, origin, DEFAULT_SHELL, dims);
        CarriageContentsPlacer.placeAt(overworld, origin, contents, dims);
        setOutline(overworld, origin, OUTLINE_BLOCK, dims);

        // Snapshot the freshly-stamped INTERIOR for the dirty-check baseline.
        // Save's captureTemplate captures only the interior (size = dims-2),
        // so the snapshot must use the same region — comparing the live
        // interior to a snapshot of just the interior keeps shell blocks
        // (which the contents save deliberately excludes) out of the diff.
        BlockPos interiorOrigin = origin.offset(1, 1, 1);
        net.minecraft.core.Vec3i interior = CarriageContentsPlacer.interiorSize(dims);
        EditorPlotSnapshots.capture(
            EditorPlotSnapshots.key("contents", contents.id()),
            overworld, interiorOrigin, interior.getX(), interior.getY(), interior.getZ()
        );
    }

    /**
     * Erase the plot for {@code contents} — shell + interior back to air,
     * barrier cage removed. Called by {@code EditorCategory.clearAllPlots}
     * when switching categories.
     */
    public static void clearPlot(ServerLevel overworld, CarriageContents contents, CarriageDims dims) {
        BlockPos origin = plotOrigin(contents, dims);
        if (origin == null) return;
        CarriagePlacer.eraseAt(overworld, origin, dims);
        CarriageContentsPlacer.eraseAt(overworld, origin, dims);
        // Drop the dirty-check baseline — same reasoning as CarriageEditor.clearPlot.
        EditorPlotSnapshots.clear(EditorPlotSnapshots.key("contents", contents.id()));
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

        CarriagePlacer.eraseAt(overworld, origin, dims);
        // Also discard any entities left from a previous edit session
        // (armor stands / item frames / paintings don't get cleared by the
        // block-only erase above). Must run before the shell + contents stamp
        // so the freshly stamped NBT entities don't get caught up in this.
        CarriageContentsPlacer.eraseAt(overworld, origin, dims);
        // Stamp the shell first — this fills floor/walls/ceiling as context.
        // Uses the 4-arg placeAt so variant-block sidecar entries don't get
        // applied here (the author is editing contents, not the shell).
        CarriagePlacer.placeAt(overworld, origin, shell, dims);
        // Stamp the current contents template on top of the air interior.
        CarriageContentsPlacer.placeAt(overworld, origin, contents, dims);
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

        StructureTemplate template = CarriageContentsPlacer.captureTemplate(overworld, origin, dims);
        CarriageContentsStore.save(contents, template);

        // Refresh the dirty-check baseline so the just-saved state reads as
        // clean on the next /dt editor unsaved-list query.
        BlockPos interiorOrigin = origin.offset(1, 1, 1);
        net.minecraft.core.Vec3i interiorSnapshotSize = CarriageContentsPlacer.interiorSize(dims);
        EditorPlotSnapshots.capture(
            EditorPlotSnapshots.key("contents", contents.id()),
            overworld, interiorOrigin,
            interiorSnapshotSize.getX(), interiorSnapshotSize.getY(), interiorSnapshotSize.getZ()
        );

        LOGGER.info("[DungeonTrain] Contents editor save: {} -> {} template interior={}x{}x{}",
            player.getName().getString(), contents.id(),
            Math.max(0, dims.length() - 2), Math.max(0, dims.height() - 2), Math.max(0, dims.width() - 2));

        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            CarriageContentsStore.saveToSource(contents, template);
            // Promote the variants sidecar too — without this, shift-right-click
            // variant authoring stayed in run/config and was lost on worktree
            // delete (the bug PR #79's vase update silently shipped without).
            net.minecraft.core.Vec3i interiorSize = CarriageContentsPlacer.interiorSize(dims);
            CarriageContentsVariantBlocks sidecar =
                CarriageContentsVariantBlocks.loadFor(contents, interiorSize);
            sidecar.saveToSource(contents);
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
    /**
     * Create a brand-new custom contents {@code target} with an empty interior
     * — registered, allocated, caged, with the {@link #DEFAULT_SHELL} stamped
     * for context but no contents template applied. The author builds the
     * interior from scratch.
     */
    public static BlockPos createBlank(ServerPlayer player, CarriageContents.Custom target) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        if (!CarriageContentsRegistry.register(target)) {
            throw new IOException("Contents '" + target.id() + "' is already registered.");
        }

        BlockPos targetOrigin = plotOrigin(target, dims);
        if (targetOrigin == null) {
            CarriageContentsRegistry.unregister(target.id());
            throw new IOException("Failed to allocate plot for '" + target.id() + "'.");
        }

        CarriagePlacer.eraseAt(overworld, targetOrigin, dims);
        CarriageContentsPlacer.eraseAt(overworld, targetOrigin, dims);
        CarriagePlacer.placeAt(overworld, targetOrigin, DEFAULT_SHELL, dims);

        StructureTemplate template = CarriageContentsPlacer.captureTemplate(overworld, targetOrigin, dims);
        CarriageContentsStore.save(target, template);

        setOutline(overworld, targetOrigin, OUTLINE_BLOCK, dims);

        LOGGER.info("[DungeonTrain] Contents editor createBlank: {} created '{}' at {}",
            player.getName().getString(), target.id(), targetOrigin);
        return targetOrigin;
    }

    public static BlockPos duplicate(ServerPlayer player, CarriageContents source, CarriageContents.Custom target) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        if (!CarriageContentsRegistry.register(target)) {
            throw new IOException("Contents '" + target.id() + "' is already registered.");
        }

        BlockPos targetOrigin = plotOrigin(target, dims);
        if (targetOrigin == null) {
            CarriageContentsRegistry.unregister(target.id());
            throw new IOException("Failed to allocate plot for '" + target.id() + "'.");
        }

        // Stamp the default shell as context, then stamp the source contents
        // on top. Capture the interior region and save under the new id.
        CarriagePlacer.eraseAt(overworld, targetOrigin, dims);
        CarriageContentsPlacer.eraseAt(overworld, targetOrigin, dims);
        CarriagePlacer.placeAt(overworld, targetOrigin, DEFAULT_SHELL, dims);
        CarriageContentsPlacer.placeAt(overworld, targetOrigin, source, dims);

        StructureTemplate template = CarriageContentsPlacer.captureTemplate(overworld, targetOrigin, dims);
        CarriageContentsStore.save(target, template);

        // Copy the source's variants sidecar onto the duplicate so authors get
        // the random-pick set "for free" — same pattern as CarriageEditor.
        net.minecraft.core.Vec3i interiorSize = CarriageContentsPlacer.interiorSize(dims);
        CarriageContentsVariantBlocks sourceSidecar = CarriageContentsVariantBlocks.loadFor(source, interiorSize);
        if (!sourceSidecar.isEmpty()) {
            CarriageContentsVariantBlocks copy = CarriageContentsVariantBlocks.empty();
            for (CarriageVariantBlocks.Entry e : sourceSidecar.entries()) {
                copy.put(e.localPos(), e.states());
            }
            copy.save(target);
        }

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
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(current, dims);
        if (origin == null) throw new IOException("Unknown contents '" + current.id() + "'.");

        StructureTemplate template = CarriageContentsPlacer.captureTemplate(overworld, origin, dims);

        String oldId;
        if (current instanceof CarriageContents.Custom currentCustom) {
            if (!CarriageContentsRegistry.register(renamed)) {
                throw new IOException("Name '" + renamed.id() + "' is already taken.");
            }
            oldId = currentCustom.name();
            CarriageContentsStore.save(renamed, template);
            CarriageContentsVariantBlocks.rename(oldId, renamed.id());
            CarriageContentsRegistry.unregister(oldId);
            CarriageContentsStore.delete(currentCustom);
            CarriageContentsVariantBlocks.invalidate(oldId);
            LOGGER.info("[DungeonTrain] Contents editor saveAs (custom→custom): {} renamed '{}' -> '{}'",
                player.getName().getString(), oldId, renamed.id());
        } else if (current instanceof CarriageContents.Builtin builtin) {
            if (!CarriageContentsRegistry.register(renamed)) {
                throw new IOException("Name '" + renamed.id() + "' is already taken.");
            }
            oldId = builtin.id();
            CarriageContentsStore.save(renamed, template);
            CarriageContentsVariantBlocks.rename(oldId, renamed.id());
            CarriageContentsStore.delete(builtin);
            CarriageContentsVariantBlocks.invalidate(oldId);
            LOGGER.info("[DungeonTrain] Contents editor saveAs (builtin→custom): {} saved edits of '{}' as new custom '{}', built-in reverts to fallback",
                player.getName().getString(), oldId, renamed.id());
        } else {
            return renamed;
        }

        // Dev-mode write-through: ship the renamed template + sidecar in the
        // next build, and delete the outgoing-name source files so the rename
        // doesn't leave a stale bundled resource. Soft-fail on any source-tree
        // error — config-dir state is the source of truth.
        if (EditorDevMode.isEnabled()) {
            try {
                CarriageContentsStore.saveToSource(renamed, template);
                net.minecraft.core.Vec3i interiorSize = CarriageContentsPlacer.interiorSize(dims);
                CarriageContentsVariantBlocks newSidecar =
                    CarriageContentsVariantBlocks.loadFor(renamed, interiorSize);
                newSidecar.saveToSource(renamed);

                java.nio.file.Path oldNbtSrc = CarriageContentsStore.sourceFileForId(oldId);
                java.nio.file.Files.deleteIfExists(oldNbtSrc);
                java.nio.file.Path oldVariantsSrc = CarriageContentsVariantBlocks.sourcePathForId(oldId);
                if (oldVariantsSrc != null) java.nio.file.Files.deleteIfExists(oldVariantsSrc);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Contents editor saveAs: source write/delete failed for {} -> {}: {}",
                    oldId, renamed.id(), e.toString());
            }
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
