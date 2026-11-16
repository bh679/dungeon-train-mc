package games.brennan.dungeontrain.mixin.client;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Moves a slot on screen: the villager trade screen's cost slots shift into the extra-cost grid. */
@Mixin(Slot.class)
public interface SlotPositionAccessor {

    @Mutable
    @Accessor("x")
    void dungeontrain$setX(int x);

    @Mutable
    @Accessor("y")
    void dungeontrain$setY(int y);
}
