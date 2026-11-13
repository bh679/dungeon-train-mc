package games.brennan.dungeontrain.worldgen.structure;

import com.mojang.datafixers.util.Either;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.NetherCoreGeometry;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * BetterNether's own structures — cities, altars, pillars, pyramids, jungle temples, gardens, ghast hives,
 * portals, respawn points — in the <b>BetterNether</b> Nether-band cores, sited by BetterNether's own code.
 *
 * <p>Those structures find their height by scanning {@code ChunkGenerator.getBaseColumn} and check their
 * biome through the biome source. In the band neither answers with the Nether: at structure-start time the
 * column is still the overworld terrain the core will replace (the core is stamped later, as a feature), and
 * DT's biome forcing yields to the overworld below sea level. So rather than re-site eleven structures the
 * way {@code Band*Siting} re-sites the four vanilla ones, their unmodified {@code findGenerationPoint} — and
 * the piece building it hands back — runs inside a short per-thread <b>scope</b>, during which
 * {@link #bandColumn} answers {@code getBaseColumn} with the core terrain and {@link #scopedBiome} answers
 * the biome source with the core's BetterNether biome. Outside a scope both hooks are a pass-through.</p>
 *
 * <p>The city is the one that doesn't scan: it builds at a fixed Nether {@code y=40} and digs its own cave.
 * {@link #cityCenterY} moves that to the band's own y 40 ({@code BetterNetherCityCenterMixin}).</p>
 *
 * <p>Only the BetterNether passes get them: their biome tags include {@code nether_wastes}, so a vanilla-look
 * band would otherwise sprout cities too. The real Nether is untouched — the overworld-only structure-set
 * gate in {@code ChunkGeneratorStructureStateMixin} keeps DT from adding a second helping there.</p>
 */
public final class BandBetterNetherStructures {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN SITE_ERRORS = new LogFirstN(5);

    static final String NAMESPACE = "betternether";
    private static final String CITY = "nether_city";
    /** The city's fixed Nether-space centre height ({@code CityStructure#getCenter}). */
    public static final int CITY_NETHER_Y = 40;

    /** Blocks a template structure may spill past its start chunk on X — the largest template is ~30 wide. */
    private static final int TEMPLATE_REACH = 32;
    /** The city's reach: its buildings and the cave it digs run up to ~100 blocks from the centre. */
    private static final int CITY_REACH = 112;

    private static final BlockState SOLID = Blocks.NETHERRACK.defaultBlockState();
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

    private BandBetterNetherStructures() {}

    /** One attempt's band: the core terrain it sites into. */
    public record Scope(BandNetherStructures.Site site) {}

    /** True for a BetterNether structure, by its structure type's namespace (no class dependency). */
    public static boolean isBandEligible(Structure structure) {
        ResourceLocation type = BuiltInRegistries.STRUCTURE_TYPE.getKey(structure.type());
        return type != null && NAMESPACE.equals(type.getNamespace());
    }

    private static boolean isCity(Structure structure) {
        ResourceLocation type = BuiltInRegistries.STRUCTURE_TYPE.getKey(structure.type());
        return type != null && CITY.equals(type.getPath());
    }

    /**
     * BetterNether's own generation point, run against the band, or empty when this attempt is not in a
     * BetterNether core (then nothing places: the overworld has no other home for these).
     */
    public static Optional<Structure.GenerationStub> findInBand(Structure structure, Structure.GenerationContext context,
                                                                java.util.function.Function<Structure.GenerationContext,
                                                                        Optional<Structure.GenerationStub>> find) {
        try {
            Scope scope = open(structure, context);
            if (scope == null) return Optional.empty();
            Optional<Structure.GenerationStub> stub = runIn(scope, () -> find.apply(context));
            return stub.map(s -> rescoped(s, scope));
        } catch (Throwable t) {
            SITE_ERRORS.error(LOGGER, "[DungeonTrain] BetterNether band structure siting failed; none in this chunk", t);
            return Optional.empty();
        }
    }

    /** The band at this attempt if the whole footprint is BetterNether core, else {@code null}. */
    static Scope open(Structure structure, Structure.GenerationContext context) {
        int reach = isCity(structure) ? CITY_REACH : TEMPLATE_REACH;
        BandNetherStructures.Site site = BandNetherStructures.open(context, reach);
        if (site == null) return null;
        WorldGenCycle cycle = site.ctx().cycle();
        long seed = site.ctx().generationSeed();
        ChunkPos chunkPos = context.chunkPos();
        int z = chunkPos.getMiddleBlockZ();
        if (cycle.netherLookAt(chunkPos.getMinBlockX() - reach, z, seed) != CycleLayout.Style.BETTER
                || cycle.netherLookAt(chunkPos.getMaxBlockX() + reach, z, seed) != CycleLayout.Style.BETTER) {
            return null;
        }
        if (site.ctx().netherCoreBiomes() == null) return null;
        return new Scope(site);
    }

    /**
     * The stub with its piece building re-entered into the scope: vanilla builds the pieces after
     * {@code findValidGenerationPoint} returns, and the city reads its centre again while it builds.
     */
    private static Structure.GenerationStub rescoped(Structure.GenerationStub stub, Scope scope) {
        Either<Consumer<StructurePiecesBuilder>, StructurePiecesBuilder> generator = stub.generator();
        if (generator.right().isPresent()) return stub;
        Consumer<StructurePiecesBuilder> build = generator.left().orElseThrow();
        return new Structure.GenerationStub(stub.position(), builder -> runIn(scope, () -> {
            build.accept(builder);
            return null;
        }));
    }

    private static <T> T runIn(Scope scope, java.util.function.Supplier<T> body) {
        Scope outer = SCOPE.get();
        SCOPE.set(scope);
        try {
            return body.get();
        } finally {
            if (outer == null) SCOPE.remove(); else SCOPE.set(outer);
        }
    }

    /** True while a BetterNether structure is being sited or built in the band on this thread. */
    public static boolean inScope() {
        return SCOPE.get() != null;
    }

    /**
     * The band's core terrain as a {@code getBaseColumn} answer inside a scope, else {@code null}.
     * Above and below the sampled slab the column reads solid, so a floor or ceiling scan lands inside it.
     */
    public static NoiseColumn bandColumn(int x, int z, LevelHeightAccessor level) {
        Scope scope = SCOPE.get();
        if (scope == null) return null;
        NetherCoreGeometry geometry = scope.site().geometry();
        NetherCoreGeometry.Column column = scope.site().column(x, z);
        int minY = level.getMinBuildHeight();
        BlockState[] states = new BlockState[level.getHeight()];
        for (int i = 0; i < states.length; i++) {
            int y = minY + i;
            states[i] = stateAt(geometry.isSampled(y), column.isSolid(y), geometry.isLavaLevel(y));
        }
        return new NoiseColumn(minY, states);
    }

    /** One block of {@link #bandColumn}: solid outside the slab, else netherrack / lava sea / air. */
    static BlockState stateAt(boolean sampled, boolean solid, boolean lavaLevel) {
        if (!sampled || solid) return SOLID;
        return lavaLevel ? LAVA : AIR;
    }

    /** The core's BetterNether biome at this block column inside a scope, at any height; else {@code null}. */
    public static Holder<Biome> scopedBiome(int blockX, int blockZ) {
        Scope scope = SCOPE.get();
        if (scope == null) return null;
        return scope.site().ctx().netherCoreBiomes().biomeAt(blockX, blockZ, CycleLayout.Style.BETTER);
    }

    /** The city's centre y inside a scope — the band's Nether y 40 — or {@code y} unchanged outside one. */
    public static int cityCenterY(int y) {
        Scope scope = SCOPE.get();
        if (scope == null) return y;
        return scope.site().bandYInCore(CITY_NETHER_Y);
    }

    /** Test seam: is the thread's scope clear. */
    static boolean scopeClear() {
        return SCOPE.get() == null;
    }

    /** Test seam: run {@code body} as if inside a scope. */
    static <T> T runInForTest(Scope scope, java.util.function.Supplier<T> body) {
        return runIn(scope, body);
    }
}
