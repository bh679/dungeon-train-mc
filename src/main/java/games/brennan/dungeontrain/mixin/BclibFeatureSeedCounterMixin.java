package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.FeatureSeedCounter;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * Makes BCLib's feature-seed rotation thread-safe — see {@link FeatureSeedCounter} for the why.
 *
 * <p>BCLib's {@code @ModifyArg} on {@code setFeatureSeed} rotates the seed by a counter shared across
 * threads. This mixin's own {@code @ModifyArg} on the same call runs <b>after</b> BCLib's (priority 1100 &gt;
 * BCLib's default 1000, so it is applied later and sits nearest the call). It ignores BCLib's value and
 * returns the same rotation computed from the call's own decoration seed and a per-thread counter.
 *
 * <p>Only active when BCLib's counter field is present on {@link ChunkGenerator}. Without it (BCLib gone,
 * or its mixin reworked) the seed passes through untouched, so DT never adds a rotation of its own.
 * Re-check BCLib's {@code ChunkGeneratorMixin} on every BCLib bump (see {@code gradle.properties}).
 */
@Mixin(value = ChunkGenerator.class, priority = 1100)
public abstract class BclibFeatureSeedCounterMixin {

    /** BCLib's per-instance counter, added to {@link ChunkGenerator} by its {@code ChunkGeneratorMixin}. */
    @Unique
    private static final String dungeontrain$BCLIB_COUNTER_FIELD = "bclib_featureIteratorSeed";

    @Unique
    private static final boolean dungeontrain$bclibRotates = dungeontrain$detectBclibCounter();

    @Unique
    private static boolean dungeontrain$detectBclibCounter() {
        for (Field field : ChunkGenerator.class.getDeclaredFields()) {
            if (field.getName().contains(dungeontrain$BCLIB_COUNTER_FIELD)) return true;
        }
        return false;
    }

    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
    private void dungeontrain$beginFeatureSeeds(WorldGenLevel level, ChunkAccess chunk,
                                                StructureManager structureManager, CallbackInfo ci) {
        if (dungeontrain$bclibRotates) FeatureSeedCounter.begin();
    }

    /** The one {@code long} local: the decoration seed from {@code setDecorationSeed}. Captured, unchanged. */
    @ModifyVariable(method = "applyBiomeDecoration", at = @At("STORE"), ordinal = 0)
    private long dungeontrain$captureDecorationSeed(long decorationSeed) {
        if (dungeontrain$bclibRotates) FeatureSeedCounter.captureSeed(decorationSeed);
        return decorationSeed;
    }

    @ModifyArg(method = "applyBiomeDecoration",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/WorldgenRandom;setFeatureSeed(JII)V"),
        index = 0)
    private long dungeontrain$perThreadFeatureSeed(long bclibRotatedSeed) {
        return dungeontrain$bclibRotates ? FeatureSeedCounter.next() : bclibRotatedSeed;
    }
}
