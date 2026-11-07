package games.brennan.dungeontrain.mixin;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.blockpredicates.StateTestingPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The block offset a state-testing predicate reads, for {@link games.brennan.dungeontrain.worldgen.DiskTestMemo}. */
@Mixin(StateTestingPredicate.class)
public interface StateTestingPredicateAccessor {

    @Accessor("offset")
    Vec3i dungeontrain$offset();
}
