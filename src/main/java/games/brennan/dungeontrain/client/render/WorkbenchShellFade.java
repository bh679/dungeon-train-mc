package games.brennan.dungeontrain.client.render;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.List;

/**
 * Where the Workbench's shells stand, on the client: the boxes a {@code WorkbenchShellFadePacket}
 * last carried. {@link StagePlaceholderBakedModel} draws a stage placeholder inside one of them
 * faint, so the carriage shell round a staged contents build reads as its frame rather than a wall
 * of red tiles. Every chunk section a box touches is re-meshed when the list changes, since the
 * fade is baked into the chunk mesh.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class WorkbenchShellFade {

    private static volatile List<BoundingBox> boxes = List.of();

    private WorkbenchShellFade() {}

    /** Replace the boxes and re-mesh what the old and new ones cover. Render thread. */
    public static void apply(List<BoundingBox> next) {
        List<BoundingBox> before = boxes;
        boxes = List.copyOf(next);
        markDirty(before);
        markDirty(boxes);
    }

    /** Whether a stage placeholder at {@code pos} is part of a Workbench shell. */
    public static boolean contains(BlockPos pos) {
        return contains(boxes, pos);
    }

    /** The pure test, for the unit test: whether any of {@code boxes} holds {@code pos}. */
    static boolean contains(List<BoundingBox> boxes, BlockPos pos) {
        for (BoundingBox box : boxes) {
            if (box.isInside(pos)) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        boxes = List.of();
    }

    private static void markDirty(List<BoundingBox> toMark) {
        LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
        if (renderer == null || Minecraft.getInstance().level == null) return;
        for (BoundingBox box : toMark) {
            for (int sx = SectionPos.blockToSectionCoord(box.minX()); sx <= SectionPos.blockToSectionCoord(box.maxX()); sx++) {
                for (int sy = SectionPos.blockToSectionCoord(box.minY()); sy <= SectionPos.blockToSectionCoord(box.maxY()); sy++) {
                    for (int sz = SectionPos.blockToSectionCoord(box.minZ()); sz <= SectionPos.blockToSectionCoord(box.maxZ()); sz++) {
                        renderer.setSectionDirty(sx, sy, sz);
                    }
                }
            }
        }
    }
}
