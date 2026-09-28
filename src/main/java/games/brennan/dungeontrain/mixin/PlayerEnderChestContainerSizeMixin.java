package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.player.EnderChestExpansion;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Lets {@code EnderChestExpansion} construct a {@link PlayerEnderChestContainer} with more than the
 * hard-coded 27 slots.
 *
 * <p>The size is a literal in the {@code super(27)} call and the class has no other constructor, so a
 * subclass cannot ask for a different capacity. The literal is swapped for
 * {@link EnderChestExpansion#requestedSize()}, a thread-local that reads 27 everywhere except inside
 * {@code EnderChestExpansion}'s own construction of a bigger container — every container vanilla builds
 * (each {@code Player} constructor) stays exactly 27.</p>
 *
 * <p>Static handler: the call sits before {@code super()} returns, where {@code this} is not yet usable.</p>
 */
@Mixin(PlayerEnderChestContainer.class)
public abstract class PlayerEnderChestContainerSizeMixin {

    @ModifyConstant(method = "<init>()V", constant = @Constant(intValue = 27))
    private static int dungeontrain$requestedSize(int vanillaSize) {
        return EnderChestExpansion.requestedSize();
    }
}
