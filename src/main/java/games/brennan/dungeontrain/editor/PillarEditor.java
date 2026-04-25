package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.TrackPalette;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Multi-plot editor for pillar sections (TOP/MIDDLE/BOTTOM) and the
 * stairs adjunct. Layout follows {@link TrackSidePlots}: pillar parts
 * stack on Y at the {@link TrackSidePlots#X_PILLARS} column, adjunct
 * lives at {@link TrackSidePlots#X_STAIRS}; each kind's registered
 * variant names lay out along {@code +Z} with
 * {@link EditorLayout#GAP}-block spacing between footprints.
 *
 * <p>The carriage editor's session map is reused via
 * {@link CarriageEditor#rememberReturn} so {@code /dungeontrain editor
 * exit} restores the player from any track-side editor.</p>
 */
public final class PillarEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final BlockState OUTLINE_BLOCK = Blocks.BEDROCK.defaultBlockState();

    public record SaveResult(boolean sourceAttempted, boolean sourceWritten, String sourceError) {
        public static SaveResult skipped() { return new SaveResult(false, false, null); }
        public static SaveResult written() { return new SaveResult(true, true, null); }
        public static SaveResult failed(String error) { return new SaveResult(true, false, error); }
    }

    /** Resolved section + name pair for a player position. */
    public record SectionPlot(PillarSection section, String name) {}

    /** Resolved adjunct + name pair for a player position. */
    public record AdjunctPlot(PillarAdjunct adjunct, String name) {}

    private PillarEditor() {}

    /** Plot origin for {@code (section, default)}. */
    public static BlockPos plotOrigin(PillarSection section, CarriageDims dims) {
        return TrackSidePlots.plotOriginDefault(PillarTemplateStore.pillarKind(section), dims);
    }

    /** Plot origin for {@code (section, name)}. */
    public static BlockPos plotOrigin(PillarSection section, String name, CarriageDims dims) {
        return TrackSidePlots.plotOrigin(PillarTemplateStore.pillarKind(section), name, dims);
    }

    /** Plot origin for {@code (adjunct, default)}. */
    public static BlockPos plotOriginAdjunct(PillarAdjunct adjunct, CarriageDims dims) {
        return TrackSidePlots.plotOriginDefault(PillarTemplateStore.adjunctKind(adjunct), dims);
    }

    /** Plot origin for {@code (adjunct, name)}. */
    public static BlockPos plotOriginAdjunct(PillarAdjunct adjunct, String name, CarriageDims dims) {
        return TrackSidePlots.plotOrigin(PillarTemplateStore.adjunctKind(adjunct), name, dims);
    }

    /**
     * Resolve the section + variant name {@code pos} sits inside, or null
     * if outside every pillar section plot. Includes the 1-block outline-
     * cage margin.
     */
    public static SectionPlot plotContaining(BlockPos pos, CarriageDims dims) {
        for (PillarSection section : PillarSection.values()) {
            int h = section.height();
            int w = dims.width();
            for (String name : TrackVariantRegistry.namesFor(PillarTemplateStore.pillarKind(section))) {
                BlockPos o = TrackSidePlots.plotOrigin(PillarTemplateStore.pillarKind(section), name, dims);
                if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + 1
                    && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + h
                    && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + w) {
                    return new SectionPlot(section, name);
                }
            }
        }
        return null;
    }

    /**
     * Resolve the adjunct + variant name {@code pos} sits inside, or null
     * if outside every adjunct plot. Includes the 1-block outline margin.
     */
    public static AdjunctPlot plotContainingAdjunct(BlockPos pos, CarriageDims dims) {
        for (PillarAdjunct adjunct : PillarAdjunct.values()) {
            for (String name : TrackVariantRegistry.namesFor(PillarTemplateStore.adjunctKind(adjunct))) {
                BlockPos o = TrackSidePlots.plotOrigin(PillarTemplateStore.adjunctKind(adjunct), name, dims);
                if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + adjunct.xSize()
                    && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + adjunct.ySize()
                    && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + adjunct.zSize()) {
                    return new AdjunctPlot(adjunct, name);
                }
            }
        }
        return null;
    }

    /**
     * Teleport {@code player} to the default plot for {@code section}.
     * Stamps every registered variant for this section first so the row
     * is fully visible.
     */
    public static void enter(ServerPlayer player, PillarSection section) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(section, TrackKind.DEFAULT_NAME, dims);

        CarriageEditor.rememberReturn(player);
        stampAllSectionPlots(overworld, section, dims);

        double tx = origin.getX() + 0.5;
        double ty = origin.getY() + 1.0;
        double tz = origin.getZ() + dims.width() / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        LOGGER.info("[DungeonTrain] Pillar editor enter: {} -> {} default plot at {} ({} variants)",
            player.getName().getString(), section.id(), origin,
            TrackVariantRegistry.namesFor(PillarTemplateStore.pillarKind(section)).size());
    }

    /** Erase + restamp every variant for {@code section}. Idempotent. */
    public static void stampPlot(ServerLevel overworld, PillarSection section, CarriageDims dims) {
        stampAllSectionPlots(overworld, section, dims);
    }

    /** Erase + restamp the single plot for {@code (section, name)}. */
    public static void stampPlot(ServerLevel overworld, PillarSection section, String name, CarriageDims dims) {
        BlockPos origin = plotOrigin(section, name, dims);
        eraseAt(overworld, origin, section, dims);
        stampNameAt(overworld, origin, section, name, dims);
        setOutline(overworld, origin, section, dims);
    }

    /** Erase every variant plot for {@code section}. */
    public static void clearPlot(ServerLevel overworld, PillarSection section, CarriageDims dims) {
        for (String name : TrackVariantRegistry.namesFor(PillarTemplateStore.pillarKind(section))) {
            BlockPos origin = plotOrigin(section, name, dims);
            eraseAt(overworld, origin, section, dims);
        }
    }

    /** Erase a single named variant plot for {@code section} — footprint + outline cleared to air. */
    public static void clearPlot(ServerLevel overworld, PillarSection section, String name, CarriageDims dims) {
        BlockPos origin = plotOrigin(section, name, dims);
        eraseAt(overworld, origin, section, dims);
    }

    /**
     * Save the captured template for the {@code (section, name)} the player
     * is currently standing in.
     */
    public static SaveResult save(ServerPlayer player, PillarSection section) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        SectionPlot loc = plotContaining(player.blockPosition(), dims);
        if (loc == null || loc.section() != section) {
            throw new IOException("Player is not inside any " + section.id() + " plot.");
        }
        String name = loc.name();
        BlockPos origin = plotOrigin(section, name, dims);

        StructureTemplate template = captureTemplate(overworld, origin, section, dims);
        TrackKind kind = PillarTemplateStore.pillarKind(section);
        TrackVariantStore.save(kind, name, template);

        LOGGER.info("[DungeonTrain] Pillar editor save: {} -> {}/{} (1x{}x{})",
            player.getName().getString(), section.id(), name,
            section.height(), dims.width());

        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            TrackVariantStore.saveToSource(kind, name, template);
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Pillar editor save: source write failed for {}: {}",
                section.id(), e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    private static void stampAllSectionPlots(ServerLevel overworld, PillarSection section, CarriageDims dims) {
        List<String> names = TrackVariantRegistry.namesFor(PillarTemplateStore.pillarKind(section));
        for (String name : names) {
            stampPlot(overworld, section, name, dims);
        }
    }

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

    private static void stampNameAt(ServerLevel level, BlockPos origin, PillarSection section, String name, CarriageDims dims) {
        TrackKind kind = PillarTemplateStore.pillarKind(section);
        Optional<StructureTemplate> stored = TrackVariantStore.get(level, kind, name, dims);
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

    /**
     * Teleport {@code player} to the default plot for {@code adjunct}.
     * Stamps every registered variant first.
     */
    public static void enter(ServerPlayer player, PillarAdjunct adjunct) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOriginAdjunct(adjunct, TrackKind.DEFAULT_NAME, dims);

        CarriageEditor.rememberReturn(player);
        stampAllAdjunctPlots(overworld, adjunct, dims);

        double tx = origin.getX() + adjunct.xSize() / 2.0;
        double ty = origin.getY() + 1.0;
        double tz = origin.getZ() + adjunct.zSize() / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        LOGGER.info("[DungeonTrain] Pillar editor enter adjunct: {} -> {} default plot at {} (size={}x{}x{}, {} variants)",
            player.getName().getString(), adjunct.id(), origin,
            adjunct.xSize(), adjunct.ySize(), adjunct.zSize(),
            TrackVariantRegistry.namesFor(PillarTemplateStore.adjunctKind(adjunct)).size());
    }

    /** Erase + restamp every variant for {@code adjunct}. Idempotent. */
    public static void stampPlot(ServerLevel overworld, PillarAdjunct adjunct, CarriageDims dims) {
        stampAllAdjunctPlots(overworld, adjunct, dims);
    }

    /** Erase + restamp the single plot for {@code (adjunct, name)}. */
    public static void stampPlotAdjunct(ServerLevel overworld, PillarAdjunct adjunct, String name, CarriageDims dims) {
        BlockPos origin = plotOriginAdjunct(adjunct, name, dims);
        eraseAtAdjunct(overworld, origin, adjunct);
        stampNameAtAdjunct(overworld, origin, adjunct, name);
        setOutlineAdjunct(overworld, origin, adjunct);
    }

    /** Erase every variant plot for {@code adjunct}. */
    public static void clearPlotAdjunct(ServerLevel overworld, PillarAdjunct adjunct, CarriageDims dims) {
        for (String name : TrackVariantRegistry.namesFor(PillarTemplateStore.adjunctKind(adjunct))) {
            BlockPos origin = plotOriginAdjunct(adjunct, name, dims);
            eraseAtAdjunct(overworld, origin, adjunct);
        }
    }

    /**
     * Save the captured adjunct template for the {@code (adjunct, name)}
     * the player is currently standing in.
     */
    public static SaveResult save(ServerPlayer player, PillarAdjunct adjunct) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        AdjunctPlot loc = plotContainingAdjunct(player.blockPosition(), dims);
        if (loc == null || loc.adjunct() != adjunct) {
            throw new IOException("Player is not inside any " + adjunct.id() + " plot.");
        }
        String name = loc.name();
        BlockPos origin = plotOriginAdjunct(adjunct, name, dims);

        StructureTemplate template = captureAdjunctTemplate(overworld, origin, adjunct);
        TrackKind kind = PillarTemplateStore.adjunctKind(adjunct);
        TrackVariantStore.save(kind, name, template);

        LOGGER.info("[DungeonTrain] Pillar editor save adjunct: {} -> {}/{} ({}x{}x{})",
            player.getName().getString(), adjunct.id(), name,
            adjunct.xSize(), adjunct.ySize(), adjunct.zSize());

        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            TrackVariantStore.saveToSource(kind, name, template);
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Pillar editor save adjunct: source write failed for {}: {}",
                adjunct.id(), e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    private static void stampAllAdjunctPlots(ServerLevel overworld, PillarAdjunct adjunct, CarriageDims dims) {
        List<String> names = TrackVariantRegistry.namesFor(PillarTemplateStore.adjunctKind(adjunct));
        for (String name : names) {
            stampPlotAdjunct(overworld, adjunct, name, dims);
        }
    }

    private static void eraseAtAdjunct(ServerLevel level, BlockPos origin, PillarAdjunct adjunct) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = -1; dx <= adjunct.xSize(); dx++) {
            for (int dz = -1; dz <= adjunct.zSize(); dz++) {
                for (int dy = -1; dy <= adjunct.ySize(); dy++) {
                    level.setBlock(origin.offset(dx, dy, dz), air, 3);
                }
            }
        }
    }

    private static void stampNameAtAdjunct(ServerLevel level, BlockPos origin, PillarAdjunct adjunct, String name) {
        TrackKind kind = PillarTemplateStore.adjunctKind(adjunct);
        // Adjunct dims are fixed — pass min CarriageDims since the kind ignores width.
        CarriageDims sentinel = CarriageDims.clamp(
            CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT);
        Optional<StructureTemplate> stored = TrackVariantStore.get(level, kind, name, sentinel);
        if (stored.isPresent()) {
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            stored.get().placeInWorld(level, origin, origin, settings, level.getRandom(), 3);
            return;
        }
        stampProceduralStairsFallback(level, origin, adjunct);
    }

    private static void stampProceduralStairsFallback(ServerLevel level, BlockPos origin, PillarAdjunct adjunct) {
        if (adjunct != PillarAdjunct.STAIRS) {
            BlockState block = TrackPalette.PILLAR;
            for (int dx = 0; dx < adjunct.xSize(); dx++) {
                for (int dy = 0; dy < adjunct.ySize(); dy++) {
                    for (int dz = 0; dz < adjunct.zSize(); dz++) {
                        level.setBlock(origin.offset(dx, dy, dz), block, 3);
                    }
                }
            }
            return;
        }
        BlockState stairBlock = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(StairBlock.FACING, Direction.SOUTH);
        BlockState fill = TrackPalette.PILLAR;
        BlockState air = Blocks.AIR.defaultBlockState();
        int xs = adjunct.xSize();
        int ys = adjunct.ySize();
        int zs = adjunct.zSize();
        for (int dy = 0; dy < ys; dy++) {
            int stepZ = Math.min(dy, zs - 1);
            for (int dx = 0; dx < xs; dx++) {
                for (int dz = 0; dz < zs; dz++) {
                    BlockState state;
                    if (dz < stepZ) state = fill;
                    else if (dz == stepZ) state = stairBlock;
                    else state = air;
                    level.setBlock(origin.offset(dx, dy, dz), state, 3);
                }
            }
        }
    }

    private static StructureTemplate captureAdjunctTemplate(ServerLevel level, BlockPos origin, PillarAdjunct adjunct) {
        StructureTemplate template = new StructureTemplate();
        Vec3i size = new Vec3i(adjunct.xSize(), adjunct.ySize(), adjunct.zSize());
        template.fillFromWorld(level, origin, size, false, Blocks.AIR);
        return template;
    }

    private static void setOutlineAdjunct(ServerLevel level, BlockPos origin, PillarAdjunct adjunct) {
        int x0 = origin.getX() - 1;
        int y0 = origin.getY() - 1;
        int z0 = origin.getZ() - 1;
        int x1 = origin.getX() + adjunct.xSize();
        int y1 = origin.getY() + adjunct.ySize();
        int z1 = origin.getZ() + adjunct.zSize();
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
