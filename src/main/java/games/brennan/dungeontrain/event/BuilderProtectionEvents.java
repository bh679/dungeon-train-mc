package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Makes the Train Builder world's scenery read-only: the grass platform you stand on and the
 * track the train sits on can't be broken or built over.
 *
 * <p>They're the frame, not the work. A build shouldn't be able to start by accidentally
 * knocking a hole in the floor or pulling up a rail, and neither is something the builder is
 * meant to author here — the track has its own mode. Everything from the train floor up
 * ({@code y >= }{@link BuilderWorldLayout#TRAIN_Y}) stays fully editable.</p>
 *
 * <p>Only ever active in a {@code dungeontrain:builder} dimension; every other world, including
 * the technical editor's, is untouched. Creative players still fire
 * {@link BlockEvent.BreakEvent}, so cancelling holds for them too.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BuilderProtectionEvents {

    private BuilderProtectionEvents() {}

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (isLocked(event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (isLocked(event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    private static boolean isLocked(LevelAccessor levelAccessor, BlockPos pos) {
        if (!(levelAccessor instanceof ServerLevel level)) {
            return false;
        }
        if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
            return false;
        }
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        BuilderMode mode = BuilderMode.fromId(data.builderMode()).orElse(null);
        return BuilderWorldLayout.isProtected(pos, data.dims(), mode);
    }
}
