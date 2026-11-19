package games.brennan.dungeontrain.compat.jade;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.PhotographFrameBlock;
import games.brennan.dungeontrain.block.entity.PhotographFrameBlockEntity;
import games.brennan.dungeontrain.compat.photo.SharedPhotos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Names who took the photo hanging in a {@link PhotographFrameBlock}: "Photo by Steve" under the
 * frame's name. Any cell of a large frame reads its master's photo, which the block entity already
 * syncs to clients, so this is client only. The name is {@link SharedPhotos#authorOf}'s: a found
 * photo's credited author, else Exposure's photographer. Empty frames and the editor's random-photo
 * placeholder show nothing.
 */
enum PhotoFrameJadeProvider implements IBlockComponentProvider {
    INSTANCE;

    private static final ResourceLocation UID =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "photo_frame_photographer");

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        PhotographFrameBlockEntity frame =
            PhotographFrameBlock.masterEntity(accessor.getLevel(), accessor.getPosition(), accessor.getBlockState());
        if (frame == null || !frame.hasPhoto() || frame.hasRandomPlaceholder()) return;
        String author = SharedPhotos.authorOf(frame.photo());
        if (author.isBlank()) return;
        tooltip.add(Component.translatable("jade.dungeontrain.photo_frame.photographer", author));
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}
