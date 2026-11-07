package games.brennan.dungeontrain.mixin;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.RandomBlockMatchTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The block a {@link RandomBlockMatchTest} matches, for {@code OreTargetPrecheck}. */
@Mixin(RandomBlockMatchTest.class)
public interface RandomBlockMatchTestAccessor {

    @Accessor("block")
    Block dungeontrain$block();
}
