package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.TrackPalette;
import games.brennan.dungeontrain.train.CarriageDims;
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
import java.util.Optional;

/**
 * Editor plots for {@link PillarSection} templates — fixed overworld
 * locations at {@code Z=30} (offset from the carriage row at {@code Z=0}) where
 * OPs can build their own top cap, repeating middle, and ground base.
 *
 * <p>Reuses the {@link CarriageEditor} session map via
 * {@link CarriageEditor#rememberReturn} so a single {@code /dungeontrain editor exit}
 * command restores the player regardless of which editor they entered. No
 * persistent state lives here — sessions survive only until server stop.</p>
 */
public final class PillarEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PLOT_Y = 250;
    /** Pillar row Z-origin. Carriage plots sit at Z=0 with width up to 32,
     *  so 40 keeps a clean gap even at max carriage width. */
    private static final int PLOT_Z = 40;
    private static final int PLOT_STEP_X = 20;
    private static final int FIRST_PLOT_X = 0;

    private static final BlockState OUTLINE_BLOCK = Blocks.BARRIER.defaultBlockState();

    public record SaveResult(boolean sourceAttempted, boolean sourceWritten, String sourceError) {
        public static SaveResult skipped() { return new SaveResult(false, false, null); }
        public static SaveResult written() { return new SaveResult(true, true, null); }
        public static SaveResult failed(String error) { return new SaveResult(true, false, error); }
    }

    private PillarEditor() {}

    /**
     * Plot origin for {@code section} — {@link PillarSection#TOP} at X=0,
     * {@link PillarSection#MIDDLE} at X=20, {@link PillarSection#BOTTOM} at X=40.
     */
    public static BlockPos plotOrigin(PillarSection section) {
        int index = section.ordinal();
        return new BlockPos(FIRST_PLOT_X + index * PLOT_STEP_X, PLOT_Y, PLOT_Z);
    }

    /**
     * Find the section whose 1×H×W plot contains {@code pos} (with a 1-block
     * outline margin so standing on the cage counts). Returns {@code null} if
     * {@code pos} is outside every pillar plot. {@code dims.width()} supplies
     * the Z-span of each plot.
     */
    public static PillarSection plotContaining(BlockPos pos, CarriageDims dims) {
        int w = dims.width();
        for (PillarSection section : PillarSection.values()) {
            BlockPos o = plotOrigin(section);
            int h = section.height();
            if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + 1
                && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + h
                && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + w) {
                return section;
            }
        }
        return null;
    }

    /**
     * Teleport {@code player} to the section plot: save their return position,
     * erase the footprint, stamp the current template (or fallback stone brick)
     * so the player sees what would render today, then draw the barrier cage.
     */
    public static void enter(ServerPlayer player, PillarSection section) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(section);

        CarriageEditor.rememberReturn(player);
        stampPlot(overworld, section, dims);

        double tx = origin.getX() + 0.5;
        double ty = origin.getY() + 1.0;
        double tz = origin.getZ() + dims.width() / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        LOGGER.info("[DungeonTrain] Pillar editor enter: {} -> {} plot at {} (size=1x{}x{})",
            player.getName().getString(), section.id(), origin, section.height(), dims.width());
    }

    /**
     * Erase, place, and cage the plot for {@code section} without teleporting.
     * Used by {@link #enter} and by category-wide stamps
     * ({@code /dt editor tracks}). Idempotent.
     */
    public static void stampPlot(ServerLevel overworld, PillarSection section, CarriageDims dims) {
        BlockPos origin = plotOrigin(section);
        eraseAt(overworld, origin, section, dims);
        stampCurrent(overworld, origin, section, dims);
        setOutline(overworld, origin, section, dims);
    }

    /**
     * Erase the plot for {@code section} — footprint + outline cage cleared
     * to air. Used on category switch and editor exit.
     */
    public static void clearPlot(ServerLevel overworld, PillarSection section, CarriageDims dims) {
        BlockPos origin = plotOrigin(section);
        // {@link #eraseAt} already clears the 1-block outline margin in the
        // loop bounds ({@code -1 .. h} / {@code -1 .. w}), so the footprint
        // and the cage both go to air in one sweep.
        eraseAt(overworld, origin, section, dims);
    }

    /**
     * Capture the {@code 1 × height × width} region at the plot for
     * {@code section} into a fresh {@link StructureTemplate} and persist it
     * via {@link PillarTemplateStore}. Air positions are included so a
     * deliberately empty slot stays empty. When {@link EditorDevMode} is on,
     * the template is also written to the source tree so it ships with the
     * next build.
     */
    public static SaveResult save(ServerPlayer player, PillarSection section) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(section);

        StructureTemplate template = captureTemplate(overworld, origin, section, dims);
        PillarTemplateStore.save(section, template);

        LOGGER.info("[DungeonTrain] Pillar editor save: {} -> {} template (1x{}x{})",
            player.getName().getString(), section.id(), section.height(), dims.width());

        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            PillarTemplateStore.saveToSource(section, template);
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Pillar editor save: source write failed for {}: {}",
                section.id(), e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    /** Erase a 1×H×W region (plus 1-block outline margin) to air for a clean slate. */
    private static void eraseAt(ServerLevel level, BlockPos origin, PillarSection section, CarriageDims dims) {
        BlockState air = Blocks.AIR.defaultBlockState();
        int h = section.height();
        int w = dims.width();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= w; dz++) {
                for (int dy = -1; dy <= h; dy++) {
                    level.setBlock(origin.offset(dx, dy, dz), air, 3);
                }
            }
        }
    }

    /**
     * Fill the 1×H×W footprint with the current stored template if any, else
     * stamp the stone-brick fallback across the full width so the player has
     * something to edit.
     */
    private static void stampCurrent(ServerLevel level, BlockPos origin, PillarSection section, CarriageDims dims) {
        Optional<StructureTemplate> stored = PillarTemplateStore.get(level, section, dims);
        if (stored.isPresent()) {
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            stored.get().placeInWorld(level, origin, origin, settings, level.getRandom(), 3);
            return;
        }
        BlockState fallback = TrackPalette.PILLAR;
        int h = section.height();
        int w = dims.width();
        for (int dy = 0; dy < h; dy++) {
            for (int dz = 0; dz < w; dz++) {
                level.setBlock(origin.offset(0, dy, dz), fallback, 3);
            }
        }
    }

    private static StructureTemplate captureTemplate(ServerLevel level, BlockPos origin, PillarSection section, CarriageDims dims) {
        StructureTemplate template = new StructureTemplate();
        Vec3i size = new Vec3i(1, section.height(), dims.width());
        template.fillFromWorld(level, origin, size, false, Blocks.AIR);
        return template;
    }

    /** Barrier cage: 12 edges of a bounding box 1 block outside the 1×H×W footprint. */
    private static void setOutline(ServerLevel level, BlockPos origin, PillarSection section, CarriageDims dims) {
        int h = section.height();
        int w = dims.width();
        int x0 = origin.getX() - 1;
        int y0 = origin.getY() - 1;
        int z0 = origin.getZ() - 1;
        int x1 = origin.getX() + 1;
        int y1 = origin.getY() + h;
        int z1 = origin.getZ() + w;

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
}
