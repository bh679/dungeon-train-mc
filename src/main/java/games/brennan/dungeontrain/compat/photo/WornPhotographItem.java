package games.brennan.dungeontrain.compat.photo;

import io.github.mortuusars.exposure.world.item.PhotographItem;
import io.github.mortuusars.exposure.world.photograph.PhotographType;
import net.minecraft.world.item.ItemStack;

/**
 * A found player photo: Exposure's photograph item, except that its paper wears with the views
 * the photo has left (see {@link WornPhotographs}). Exposure asks the item for its paper type per
 * stack, which is what lets one item show every stage of wear.
 */
public final class WornPhotographItem extends PhotographItem {

    public WornPhotographItem(Properties properties) {
        super(properties);
    }

    @Override
    public PhotographType getType(ItemStack stack) {
        return WornPhotographs.forViewsLeft(SharedPhotos.viewsLeft(stack), SharedPhotos.sharedId(stack));
    }
}
