package games.brennan.dungeontrain.mixin;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.MobBucketItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads which mob a mob bucket holds ({@code editor.BucketMob}). {@code MobBucketItem.type} is
 * private and vanilla has no getter for it.
 */
@Mixin(MobBucketItem.class)
public interface MobBucketItemAccessor {

    @Accessor("type")
    EntityType<?> dungeontrain$getType();
}
