package games.brennan.dungeontrain.mixin;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Replaces a piece's bounding box — {@code LostCityFootprint} resizes a stretched building's at start time. */
@Mixin(StructurePiece.class)
public interface StructurePieceAccessor {

    @Accessor("boundingBox")
    void dungeontrain$setBoundingBox(BoundingBox box);
}
