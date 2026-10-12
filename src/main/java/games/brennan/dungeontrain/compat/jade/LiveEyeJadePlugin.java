package games.brennan.dungeontrain.compat.jade;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.LiveEyeContainerEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade's container tooltip lists what a chest, barrel or vase holds — so looking at one with Jade is
 * seeing its Live Eyes. This server data provider resolves them for the looking player
 * ({@link LiveEyeContainerEvents#see}) before Jade's own item-storage provider (priority 1000) reads
 * the contents, so the tooltip shows what the player would find on opening it.
 *
 * <p>Optional: Jade finds this class by its {@link WailaPlugin} annotation, so nothing loads it — or
 * any Jade class — when Jade is not installed.</p>
 */
@WailaPlugin(DungeonTrain.MOD_ID)
public final class LiveEyeJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(LiveEyeProvider.INSTANCE, BaseContainerBlockEntity.class);
        registration.registerBlockDataProvider(LiveEyeProvider.INSTANCE, DecoratedPotBlockEntity.class);
    }

    enum LiveEyeProvider implements IServerDataProvider<BlockAccessor> {
        INSTANCE;

        private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live_eye_resolve");

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getPlayer() instanceof ServerPlayer player)) return;
            if (!(accessor.getLevel() instanceof ServerLevel level)) return;
            LiveEyeContainerEvents.see(player, level, accessor.getPosition());
        }

        @Override
        public ResourceLocation getUid() {
            return UID;
        }

        /** Ahead of Jade's item-storage provider (1000), so it reads the resolved contents. */
        @Override
        public int getDefaultPriority() {
            return -1000;
        }
    }
}
