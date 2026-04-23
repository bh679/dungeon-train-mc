package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.TrackPalette;
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
    private static final int PLOT_Z = 30;
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
     * Find the section whose 1×H×1 plot contains {@code pos} (with a 1-block
     * outline margin so the player standing on the cage detects too). Returns
     * {@code null} if {@code pos} is outside every pillar plot.
     */
    public static PillarSection plotContaining(BlockPos pos) {
        for (PillarSection section : PillarSection.values()) {
            BlockPos o = plotOrigin(section);
            int h = section.height();
            if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + 1
                && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + h
                && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + 1) {
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
        BlockPos origin = plotOrigin(section);

        CarriageEditor.rememberReturn(player);

        eraseAt(overworld, origin, section);
        stampCurrent(overworld, origin, section);
        setOutline(overworld, origin, section);

        double tx = origin.getX() + 0.5;
        double ty = origin.getY() + 1.0;
        double tz = origin.getZ() + 0.5;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        LOGGER.info("[DungeonTrain] Pillar editor enter: {} -> {} plot at {} (height={})",
            player.getName().getString(), section.id(), origin, section.height());
    }

    /**
     * Capture the {@code 1 × height × 1} region at the plot for {@code section}
     * into a fresh {@link StructureTemplate} and persist it via
     * {@link PillarTemplateStore}. Air positions are included so a deliberately
     * empty slot stays empty. When {@link EditorDevMode} is on, the template
     * is also written to the source tree so it ships with the next build.
     */
    public static SaveResult save(ServerPlayer player, PillarSection section) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        BlockPos origin = plotOrigin(section);

        StructureTemplate template = captureTemplate(overworld, origin, section);
        PillarTemplateStore.save(section, template);

        LOGGER.info("[DungeonTrain] Pillar editor save: {} -> {} template",
            player.getName().getString(), section.id());

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

    /** Erase a 1×H×1 region (plus cage) to air so the fresh stamp lands on a clean slate. */
    private static void eraseAt(ServerLevel level, BlockPos origin, PillarSection section) {
        BlockState air = Blocks.AIR.defaultBlockState();
        int h = section.height();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= h; dy++) {
                    level.setBlock(origin.offset(dx, dy, dz), air, 3);
                }
            }
        }
    }

    /**
     * Fill the 1×H×1 footprint with the current stored template if any, else
     * fall back to the stone-brick palette so the cage shows the fallback
     * visual and the player has something to edit.
     */
    private static void stampCurrent(ServerLevel level, BlockPos origin, PillarSection section) {
        Optional<StructureTemplate> stored = PillarTemplateStore.get(level, section);
        if (stored.isPresent()) {
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            stored.get().placeInWorld(level, origin, origin, settings, level.getRandom(), 3);
            return;
        }
        BlockState fallback = TrackPalette.PILLAR;
        int h = section.height();
        for (int dy = 0; dy < h; dy++) {
            level.setBlock(origin.offset(0, dy, 0), fallback, 3);
        }
    }

    private static StructureTemplate captureTemplate(ServerLevel level, BlockPos origin, PillarSection section) {
        StructureTemplate template = new StructureTemplate();
        Vec3i size = new Vec3i(1, section.height(), 1);
        template.fillFromWorld(level, origin, size, false, Blocks.AIR);
        return template;
    }

    /** Barrier cage: 12 edges of a bounding box 1 block outside the 1×H×1 footprint. */
    private static void setOutline(ServerLevel level, BlockPos origin, PillarSection section) {
        int h = section.height();
        int x0 = origin.getX() - 1;
        int y0 = origin.getY() - 1;
        int z0 = origin.getZ() - 1;
        int x1 = origin.getX() + 1;
        int y1 = origin.getY() + h;
        int z1 = origin.getZ() + 1;

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
