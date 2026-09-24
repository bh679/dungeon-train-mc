package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.worldgen.SecondLapOverworld;
import games.brennan.dungeontrain.worldgen.density.BandBiomeDecision;
import games.brennan.dungeontrain.worldgen.density.NetherBandContext;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBiomes;
import games.brennan.dungeontrain.worldgen.density.OverworldBiomeSourceMark;
import games.brennan.dungeontrain.worldgen.density.OverworldStretchBiomes;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
 *
 * <p>Everywhere else on the overworld the biome comes from {@link OverworldStretchBiomes}: Biomes O'
 * Plenty in its second-lap stretch, vanilla elsewhere ({@link SecondLapOverworld}).</p>
 *
 * <p>A cancellable HEAD inject, not a return-value modifier: TerraBlender (Biomes O' Plenty's library)
 * answers this method from its own HEAD inject and cancels, so a RETURN hook never sees its answer. The
 * lower priority applies this mixin first, so its callback runs ahead of TerraBlender's. TerraBlender
 * also fills each chunk through a clone of the source, so the overworld is recognised by a mark field
 * the clone inherits ({@link OverworldBiomeSourceMark}), not by identity.</p>
 */
@Mixin(value = MultiNoiseBiomeSource.class, priority = 500)
public abstract class MultiNoiseBiomeSourceMixin implements OverworldBiomeSourceMark {

    @Unique
    private boolean dungeontrain$overworld;

    @Override
    public void dungeontrain$markOverworld() {
        this.dungeontrain$overworld = true;
    }

    @Override
    public boolean dungeontrain$isOverworld() {
        return this.dungeontrain$overworld;
    }

    private static final org.slf4j.Logger dungeontrain$LOGGER = LogUtils.getLogger();
    private static final LogFirstN dungeontrain$FORCE_ERRORS = new LogFirstN(5);

    @Inject(
        method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
        at = @At("HEAD"), cancellable = true)
    private void dungeontrain$forceBiome(int x, int y, int z, Climate.Sampler sampler,
                                         CallbackInfoReturnable<Holder<Biome>> cir) {
        long genT0 = GenProfiler.t0();
        try {
            // Legacy bands first: an old generator's column shows its own biome map at every height.
            Holder<Biome> legacy = LegacyBiomes.override(this, x << 2, z << 2);
            if (legacy != null) {
                cir.setReturnValue(legacy);
                return;
            }
            NetherBandContext ctx = NetherBandContext.current();
            // Overworld-only: the Nether also uses a MultiNoiseBiomeSource. The mark (not identity)
            // also covers TerraBlender's per-chunk clones of the overworld source.
            if (ctx == null || !(dungeontrain$overworld || (Object) this == ctx.overworldBiomeSource())) return;
            Holder<Biome> forced = dungeontrain$bandBiome(ctx, x, y, z);
            if (forced == null) forced = dungeontrain$stretchBiome(ctx, (MultiNoiseBiomeSource) (Object) this, x, y, z, sampler);
            if (forced != null) cir.setReturnValue(forced);
        } catch (Throwable t) {
            dungeontrain$FORCE_ERRORS.error(dungeontrain$LOGGER,
                    "[DungeonTrain] Highland/core biome override failed; baking the source's own biome instead", t);
        } finally {
            GenProfiler.add(GenProfiler.Bucket.BIOME_FORCE, genT0);
        }
    }

    /** The second-lap stretch biome (BoP or vanilla), or {@code null} to leave the live source's pick. */
    private static Holder<Biome> dungeontrain$stretchBiome(NetherBandContext ctx, MultiNoiseBiomeSource source,
                                                         int x, int y, int z, Climate.Sampler sampler) {
        OverworldStretchBiomes stretchBiomes = OverworldStretchBiomes.current();
        if (stretchBiomes == null) return null;
        return stretchBiomes.pick(SecondLapOverworld.at(ctx.cycle(), x << 2), source, x, y, z, sampler);
    }

    /** The forced Nether-core / End-core / highland biome, or {@code null} for an ordinary column. */
    private static Holder<Biome> dungeontrain$bandBiome(NetherBandContext ctx, int x, int y, int z) {
        if (!ctx.enabled() || ctx.highlandBiomes() == null) return null;

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
                // Per-biome fog/ambient/music + the Nether decoration features' own biome filter
                // pass so they place in NetherTransitionFeature. Alternate passes are BetterNether.
                return ctx.netherCoreBiomes().biomeAt(blockX, blockZ, ctx.cycle().netherPassIndex(blockX));
            case END_CORE:
                // Sample the real End's biome source (all five End biomes, swept across successive
                // End-band passes — see EndCoreBiomes) so world label, surface skin and decoration agree.
                return ctx.endCoreBiomes().biomeAt(blockX, blockZ, ctx.cycle().endPassIndex(blockX));
            case HIGHLAND:
                return ctx.highlandBiomes().biomeFor(blockX, blockY, blockZ);
            default:
                return null;
        }
    }
}
