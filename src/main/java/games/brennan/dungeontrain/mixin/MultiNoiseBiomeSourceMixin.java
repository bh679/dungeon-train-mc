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
 * Biome generation is never broken by this hook: on the overworld, a missing context or an error
 * falls back to a vanilla pick ({@link OverworldStretchBiomes#vanillaFallback}), never to TerraBlender's
 * — which would bake Biomes O' Plenty biomes into vanilla stretches for good.</p>
 *
 * <p>Everywhere else on the overworld the biome comes from {@link OverworldStretchBiomes}: Biomes O'
 * Plenty in its second-lap stretch and the band transitions bordering it, vanilla elsewhere
 * ({@link SecondLapOverworld#lookAt}).</p>
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
    private static final LogFirstN dungeontrain$FALLBACKS = new LogFirstN(5);

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
            // also covers TerraBlender's per-chunk clones of the overworld source, and outlives the
            // context — so a marked source is still answered when the context is gone.
            if (!(dungeontrain$overworld || (ctx != null && (Object) this == ctx.overworldBiomeSource()))) return;
            MultiNoiseBiomeSource source = (MultiNoiseBiomeSource) (Object) this;
            Holder<Biome> forced = ctx == null ? null : dungeontrain$bandBiome(ctx, x, y, z);
            if (forced == null && ctx != null) forced = dungeontrain$stretchBiome(ctx, source, x, y, z, sampler);
            if (forced == null) forced = dungeontrain$vanillaFallback(source, x, y, z, sampler, ctx == null
                    ? "no Nether-band context" : "no stretch biome tables");
            if (forced != null) cir.setReturnValue(forced);
        } catch (Throwable t) {
            dungeontrain$FORCE_ERRORS.error(dungeontrain$LOGGER,
                    "[DungeonTrain] Highland/core/stretch biome override failed; using a vanilla biome instead", t);
            if (dungeontrain$overworld) {
                try {
                    Holder<Biome> fallback = dungeontrain$vanillaFallback(
                            (MultiNoiseBiomeSource) (Object) this, x, y, z, sampler, "override error");
                    if (fallback != null) cir.setReturnValue(fallback);
                } catch (Throwable t2) {
                    dungeontrain$FORCE_ERRORS.error(dungeontrain$LOGGER,
                            "[DungeonTrain] Vanilla biome fallback failed too; baking the source's own biome", t2);
                }
            }
        } finally {
            GenProfiler.add(GenProfiler.Bucket.BIOME_FORCE, genT0);
        }
    }

    /**
     * A vanilla pick needing no published context — never TerraBlender's, which could be BoP. Debug-level:
     * vanilla's stronghold-ring search legitimately lands here on every boot, while {@code publish()} runs
     * between marking the source and publishing the context.
     */
    private static Holder<Biome> dungeontrain$vanillaFallback(MultiNoiseBiomeSource source, int x, int y, int z,
                                                            Climate.Sampler sampler, String why) {
        dungeontrain$FALLBACKS.debug(dungeontrain$LOGGER,
                "[DungeonTrain] Overworld biome at quart ({}, {}, {}) used the vanilla fallback: {}", x, y, z, why);
        return OverworldStretchBiomes.vanillaFallback(source, x, y, z, sampler);
    }

    /** The second-lap stretch biome (BoP or vanilla), or {@code null} to leave the live source's pick. */
    private static Holder<Biome> dungeontrain$stretchBiome(NetherBandContext ctx, MultiNoiseBiomeSource source,
                                                         int x, int y, int z, Climate.Sampler sampler) {
        OverworldStretchBiomes stretchBiomes = OverworldStretchBiomes.current();
        if (stretchBiomes == null) return null;
        return stretchBiomes.pick(SecondLapOverworld.lookAt(ctx.cycle(), x << 2), source, x, y, z, sampler);
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
                // pass so they place in NetherTransitionFeature. The order's :better passes are BetterNether.
                return ctx.netherCoreBiomes().biomeAt(blockX, blockZ, ctx.cycle().isBetterNetherAt(blockX));
            case END_CORE:
                // Sample the real End's biome source (all five End biomes, swept across successive
                // End-band passes — see EndCoreBiomes) so world label, surface skin and decoration agree.
                long endPass = ctx.cycle().endPassIndex(blockX);
                return ctx.endCoreBiomes().biomeAt(blockX, blockZ, endPass, ctx.cycle().isBetterEndPass(endPass));
            case HIGHLAND:
                // Mountain stages bordering the BoP stretch climb through BoP's forests and snow instead.
                return SecondLapOverworld.lookAt(ctx.cycle(), blockX) == SecondLapOverworld.Stretch.BOP
                        ? ctx.highlandBiomes().bopBiomeFor(blockX, blockY, blockZ)
                        : ctx.highlandBiomes().biomeFor(blockX, blockY, blockZ);
            default:
                return null;
        }
    }
}
