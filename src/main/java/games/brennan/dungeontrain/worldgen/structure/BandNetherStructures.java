package games.brennan.dungeontrain.worldgen.structure;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.worldgen.NetherCoreGeometry;
import games.brennan.dungeontrain.worldgen.NetherMountainTerrain;
import games.brennan.dungeontrain.worldgen.WorldFloor;
import games.brennan.dungeontrain.worldgen.density.NetherBandContext;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * The gate every Nether-band structure opens with, in one place: is this world running the band, is this
 * the overworld, is this patch of it real-Nether core, and what does the core terrain look like here.
 *
 * <p>The four {@code Band*Structure} classes differ only in which vanilla structure they then build. All of
 * them ask this first, and all of them decline the same way — quietly, by returning {@code null} — so a
 * world with no train, no Nether or no band snapshot simply generates no Nether structures rather than
 * failing. Everything is read from the {@link NetherBandContext} server-start snapshot, so structure-start
 * creation (which runs on the worldgen thread pool) never touches the server.</p>
 */
final class BandNetherStructures {

    private BandNetherStructures() {}

    /**
     * Open the band at this placement attempt, or {@code null} if a structure may not stand here.
     *
     * @param footprintRadius blocks the structure may spill past its start chunk on X — the whole span must
     *                        be core, or the structure would emerge from the netherrack into the mountains
     */
    static Site open(Structure.GenerationContext context, int footprintRadius) {
        if (!DungeonTrainCommonConfig.isNetherStructuresEnabled()) return null;

        NetherBandContext ctx = NetherBandContext.current();
        if (ctx == null || !ctx.enabled() || ctx.cycle() == null || ctx.netherCore() == null) return null;
        // Overworld-only: the band (and its core geometry) exists in exactly one dimension, and the real
        // Nether must be left alone — it has these structures already.
        if (context.chunkGenerator().getBiomeSource() != ctx.overworldBiomeSource()) return null;

        ChunkPos chunkPos = context.chunkPos();
        if (!NetherStructureSiting.isCoreFootprint(ctx.cycle(),
                chunkPos.getMinBlockX() - footprintRadius, chunkPos.getMaxBlockX() + footprintRadius)) {
            return null;
        }

        // The core terrain, bounded by the world's floor rather than the level's — a structure has to site
        // itself against the same range NetherTransitionFeature stamped the core over.
        NetherCoreGeometry geometry = ctx.netherCore().open(
                WorldFloor.bedrockY(context.heightAccessor(), context.chunkGenerator()),
                context.heightAccessor().getMaxBuildHeight() - 1);
        if (!geometry.isUsable()) return null;

        return new Site(ctx, geometry);
    }

    /** One placement attempt's view of the band: the snapshot it read, and the core terrain it sites into. */
    record Site(NetherBandContext ctx, NetherCoreGeometry geometry) {

        /** The track bed's world Y — where the Nether's own centre line lands. */
        int bedY() {
            return geometry.bedY();
        }

        /** Band world Y for one of vanilla's Nether-space placement heights. */
        int bandY(int netherY) {
            return NetherStructureSiting.bandY(bedY(), netherY);
        }

        /** {@link #bandY} clamped into the slab that actually carries core terrain. */
        int bandYInCore(int netherY) {
            return NetherStructureSiting.clampToCore(bedY(), bandY(netherY));
        }

        /**
         * A column of the core terrain at this band position, sampled at the <b>edge-waved</b> X so it
         * describes the very blocks {@code NetherTransitionFeature} will stamp there.
         */
        NetherCoreGeometry.Column column(int worldX, int worldZ) {
            int wavedX = NetherMountainTerrain.wavyX(ctx.generationSeed(), worldX, worldZ);
            return geometry.column(NetherCoreGeometry.sampleX(wavedX), worldZ);
        }

        /**
         * Vanilla's biome restriction for this structure ({@code #minecraft:has_structure/…}), asked of the
         * <b>real Nether's</b> biome source at the same patch of Nether the core terrain came from.
         *
         * <p>Asking the overworld biome source instead — which is what the base class's own filter does —
         * would be wrong twice over: it depends on DT's biome-forcing mixin being active, and that mixin
         * deliberately yields to the original biome below sea level, where several of these structures are
         * entitled to sit. This asks the authority directly, so a fossil lands in a soul sand valley and
         * nowhere else, exactly as it would in the Nether.</p>
         */
        boolean biomeAllows(HolderSet<Biome> biomes, int worldX, int worldZ) {
            if (ctx.netherCoreBiomes() == null) return false;
            Holder<Biome> coreBiome = ctx.netherCoreBiomes().biomeAt(worldX, worldZ);
            return biomes.contains(coreBiome);
        }
    }
}
