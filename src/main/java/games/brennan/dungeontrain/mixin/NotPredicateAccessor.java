package games.brennan.dungeontrain.mixin;

import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The predicate {@code not} negates (package-private class), for {@link games.brennan.dungeontrain.worldgen.DiskTestMemo}. */
@Mixin(targets = "net.minecraft.world.level.levelgen.blockpredicates.NotPredicate")
public interface NotPredicateAccessor {

    @Accessor("predicate")
    BlockPredicate dungeontrain$predicate();
}
