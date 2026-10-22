package games.brennan.dungeontrain.client.render;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModBlocks;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Hides the variant editor's empty-cell marker the camera is inside, so an author standing in one
 * sees straight out of it. Tracks the camera's cell once a client tick; when it moves into or out
 * of a marker, the cell's section is rebuilt and {@link PlaceholderHiddenModel} draws it empty.
 *
 * <p>Only that one cell hides — its neighbours keep drawing. A rebuild per marker crossed is one
 * section, built off-thread, the same path {@code SkyboxSectionRebuild} uses (Sodium redirects
 * {@link LevelRenderer#setSectionDirty}).</p>
 */
public final class PlaceholderCameraHide {

    /** The marker cell the camera is in, or null. Read by chunk-build threads, hence volatile. */
    @Nullable
    private static volatile BlockPos hiddenCell;

    private PlaceholderCameraHide() {}

    @Nullable
    static BlockPos hiddenCell() {
        return hiddenCell;
    }

    /** Wrap the marker's blockstate models so the hidden cell draws nothing. */
    @EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModEvents {
        private ModEvents() {}

        @SubscribeEvent
        public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
            Map<ModelResourceLocation, BakedModel> models = event.getModels();
            for (BlockState state : ModBlocks.VARIANT_PLACEHOLDER.get().getStateDefinition().getPossibleStates()) {
                ModelResourceLocation key = BlockModelShaper.stateToModelLocation(state);
                BakedModel baked = models.get(key);
                if (baked != null && !(baked instanceof PlaceholderHiddenModel)) {
                    models.put(key, new PlaceholderHiddenModel(baked));
                }
            }
        }
    }

    /** Follow the camera's cell and rebuild the sections it leaves and enters. */
    @EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
    public static final class GameEvents {
        private GameEvents() {}

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            BlockPos next = cameraMarkerCell(mc);
            BlockPos previous = hiddenCell;
            if (next == null ? previous == null : next.equals(previous)) return;
            hiddenCell = next;
            rebuild(mc.levelRenderer, previous);
            rebuild(mc.levelRenderer, next);
        }
    }

    /** The cell the camera is in when it holds a marker, else null. */
    @Nullable
    private static BlockPos cameraMarkerCell(Minecraft mc) {
        if (mc.level == null) return null;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return null;
        BlockPos cell = camera.getBlockPosition();
        return mc.level.getBlockState(cell).is(ModBlocks.VARIANT_PLACEHOLDER.get()) ? cell.immutable() : null;
    }

    private static void rebuild(@Nullable LevelRenderer renderer, @Nullable BlockPos cell) {
        if (renderer == null || cell == null) return;
        renderer.setSectionDirty(SectionPos.blockToSectionCoord(cell.getX()),
            SectionPos.blockToSectionCoord(cell.getY()), SectionPos.blockToSectionCoord(cell.getZ()));
    }
}
