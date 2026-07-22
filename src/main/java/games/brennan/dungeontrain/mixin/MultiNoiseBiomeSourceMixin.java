package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.worldgen.density.BandBiomeDecision;
import games.brennan.dungeontrain.worldgen.density.NetherBandContext;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Forces vegetated <b>highland biomes</b> onto nether-band mountain columns so vanilla decoration
 * (trees, flowers, snow caps) and structures populate the noise-raised terrain — which otherwise
 * inherits the original low terrain's (often tree-less) biome and reads as bare mountains. Also tags
 * Nether-band and End-band <b>core</b> columns with a real Nether/End biome (see
 * {@link NetherBandContext#netherCoreBiomes()} / {@link NetherBandContext#endCoreBiomes()}).
 *
 * <p>Wraps {@code getNoiseBiome(x,y,z,sampler)} — the per-quart biome assignment, also consulted
 * during structure placement, so structures follow the forced biome. Gated to be a pure pass-through
 * except on the OVERWORLD biome source (identity check — the Nether also uses
 * {@code MultiNoiseBiomeSource}), inside an active band column above sea level. The biome is
 * altitude-zoned (forest/meadow → spruce → snow → bare peak) by {@link NetherBandContext#highlandBiomes()}.
 * Any error falls back to the original biome — biome generation is never broken by this hook.</p>
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin {

    private static final org.slf4j.Logger dungeontrain$LOGGER = LogUtils.getLogger();
    private static final LogFirstN dungeontrain$FORCE_ERRORS = new LogFirstN(5);

    @ModifyReturnValue(
        method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
        at = @At("RETURN"))
    private Holder<Biome> dungeontrain$forceHighlandBiome(Holder<Biome> original, int x, int y, int z, Climate.Sampler sampler) {
        long genT0 = GenProfiler.t0();
        try {
            NetherBandContext ctx = NetherBandContext.current();
            if (ctx == null || !ctx.enabled() || ctx.highlandBiomes() == null) return original;
            // Overworld-only: the Nether also uses a MultiNoiseBiomeSource, so gate on the instance.
            if ((Object) this != ctx.overworldBiomeSource()) return original;

            int blockX = x << 2;
            int blockY = y << 2;
            int blockZ = z << 2;
            // The whole per-quart decision — sea-level gate, off-band early-out, waved Nether-core /
            // un-waved End-core / highland ordering — lives in the pure, unit-tested
            // BandBiomeDecision.decide; this shell only maps the result onto the live providers.
            switch (BandBiomeDecision.decide(ctx.cycle(), ctx.generationSeed(), ctx.seaLevel(),
                    ctx.netherCoreBiomes() != null, ctx.endCoreBiomes() != null,
                    blockX, blockY, blockZ)) {
                case NETHER_CORE:
                    // Per-biome fog/ambient/music + the vanilla Nether decoration features' own biome
                    // filter pass so they place in NetherTransitionFeature.
                    return ctx.netherCoreBiomes().biomeAt(blockX, blockZ);
                case END_CORE:
                    // Sample the real End's biome source (all five End biomes, swept across successive
                    // End-band passes — see EndCoreBiomes) so world label, surface skin and decoration agree.
                    return ctx.endCoreBiomes().biomeAt(blockX, blockZ, ctx.cycle().endPassIndex(blockX));
                case HIGHLAND:
                    return ctx.highlandBiomes().biomeFor(blockX, blockY, blockZ);
                default:
                    return original;
            }
        } catch (Throwable t) {
            dungeontrain$FORCE_ERRORS.error(dungeontrain$LOGGER,
                    "[DungeonTrain] Highland/core biome override failed; baking vanilla biome instead", t);
            return original;
        } finally {
            GenProfiler.add(GenProfiler.Bucket.BIOME_FORCE, genT0);
        }
    }
}
