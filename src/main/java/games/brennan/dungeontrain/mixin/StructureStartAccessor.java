package games.brennan.dungeontrain.mixin;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Clears a {@link StructureStart}'s memoised box after its pieces move ({@link StructureMixCentreMixin}). */
@Mixin(StructureStart.class)
public interface StructureStartAccessor {

    @Accessor("cachedBoundingBox")
    void dungeontrain$setCachedBoundingBox(BoundingBox box);
}
