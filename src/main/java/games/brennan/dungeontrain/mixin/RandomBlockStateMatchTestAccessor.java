package games.brennan.dungeontrain.mixin;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.RandomBlockStateMatchTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The block state a {@link RandomBlockStateMatchTest} matches, for {@code OreTargetPrecheck}. */
@Mixin(RandomBlockStateMatchTest.class)
public interface RandomBlockStateMatchTestAccessor {

    @Accessor("blockState")
    BlockState dungeontrain$blockState();
}
