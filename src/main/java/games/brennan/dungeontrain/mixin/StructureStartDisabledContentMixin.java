package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.compat.StructureDisabledContentCleanup;
import games.brennan.dungeontrain.worldgen.structure.BandBetterNetherStructures;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After a BetterNether structure places its part of a chunk, take out the ores and gear metals DT has turned
 * off ({@link StructureDisabledContentCleanup}). Every other structure is left alone.
 */
@Mixin(StructureStart.class)
public abstract class StructureStartDisabledContentMixin {

    @Shadow
    public abstract Structure getStructure();

    @Shadow
    public abstract BoundingBox getBoundingBox();

    @Inject(method = "placeInChunk", at = @At("RETURN"))
    private void dungeontrain$cleanDisabledContent(WorldGenLevel level, StructureManager structureManager,
                                                   ChunkGenerator generator, RandomSource random, BoundingBox chunkBox,
                                                   ChunkPos chunkPos, CallbackInfo ci) {
        if (!BandBetterNetherStructures.isBandEligible(getStructure())) return;
        BoundingBox box = getBoundingBox();
        if (!box.intersects(chunkBox)) return;
        StructureDisabledContentCleanup.clean(level, new BoundingBox(
                Math.max(box.minX(), chunkBox.minX()), Math.max(box.minY(), chunkBox.minY()), Math.max(box.minZ(), chunkBox.minZ()),
                Math.min(box.maxX(), chunkBox.maxX()), Math.min(box.maxY(), chunkBox.maxY()), Math.min(box.maxZ(), chunkBox.maxZ())));
    }
}
