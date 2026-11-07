package games.brennan.dungeontrain.mixin;

import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The children of {@code all_of} / {@code any_of} (package-private class), for {@link games.brennan.dungeontrain.worldgen.DiskTestMemo}. */
@Mixin(targets = "net.minecraft.world.level.levelgen.blockpredicates.CombiningPredicate")
public interface CombiningPredicateAccessor {

    @Accessor("predicates")
    List<BlockPredicate> dungeontrain$predicates();
}
