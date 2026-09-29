package games.brennan.dungeontrain.worldgen.density;

import games.brennan.dungeontrain.worldgen.SecondLapOverworld;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBiomes;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * The live {@link ColumnBiomePlan.Providers} — the column-level halves of the biome-source hook's
 * decision, each the same call the per-quart path makes, bound to one published
 * {@link NetherBandContext}. One instance per context ({@link #forContext}) so the hot path allocates
 * nothing.
 *
 * <p>The band decision always reads the <b>base</b> cycle — even in the mix zone, whose chunks keep
 * the ordinary overworld biome whatever band they picked: vanilla takes sky, fog and water colour
 * from the biome, so a forced Nether / End biome would re-tint the air chunk by chunk. The zone swaps
 * blocks, never the atmosphere.</p>
 */
public record LiveColumnProviders(NetherBandContext ctx) implements ColumnBiomePlan.Providers<Holder<Biome>> {

    private static volatile LiveColumnProviders last;

    /** The providers for {@code ctx} — the cached instance while the context stays published. */
    public static LiveColumnProviders forContext(NetherBandContext ctx) {
        LiveColumnProviders p = last;
        if (p == null || p.ctx != ctx) {
            p = new LiveColumnProviders(ctx);
            last = p;                                  // benign race — same-value replace
        }
        return p;
    }

    @Override
    public Holder<Biome> legacy(int blockX, int blockZ) {
        return LegacyBiomes.overrideOnOverworld(blockX, blockZ);
    }

    /**
     * {@link BandBiomeDecision#decide} at sea level — y only enters that decision as the
     * {@code blockY < seaLevel} gate, so this is its answer for every quart at or above sea level.
     * A disabled band or missing highland palette declines outright, as the per-quart path does.
     */
    @Override
    public BandBiomeDecision.Result decideAboveSea(int blockX, int blockZ) {
        if (!ctx.enabled() || ctx.highlandBiomes() == null) return BandBiomeDecision.Result.ORIGINAL;
        return BandBiomeDecision.decide(ctx.cycle(), ctx.generationSeed(), ctx.seaLevel(),
                ctx.netherCoreBiomes() != null, ctx.endCoreBiomes() != null,
                blockX, ctx.seaLevel(), blockZ);
    }

    /** Per-biome fog/ambient/music + the Nether decoration features' own biome filter pass. */
    @Override
    public Holder<Biome> netherCore(int blockX, int blockZ) {
        WorldGenCycle cycle = ctx.cycle();
        return ctx.netherCoreBiomes().biomeAt(blockX, blockZ, cycle.netherLookAt(blockX));
    }

    /** The real End's biome, swept across successive End-band passes — see {@link EndCoreBiomes}. */
    @Override
    public Holder<Biome> endCore(int blockX, int blockZ) {
        WorldGenCycle cycle = ctx.cycle();
        long endPass = cycle.endSourcePassAt(blockX, blockZ, ctx.generationSeed());
        return ctx.endCoreBiomes().biomeAt(blockX, blockZ, endPass, cycle.endStyleOfPass(endPass));
    }

    @Override
    public SecondLapOverworld.Stretch look(int blockX) {
        return SecondLapOverworld.lookAt(ctx.cycle(), blockX);
    }
}
