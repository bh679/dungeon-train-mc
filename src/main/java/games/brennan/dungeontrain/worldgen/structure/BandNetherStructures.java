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
import games.brennan.dungeontrain.mixin.JigsawStructureAccessor;
import games.brennan.dungeontrain.mixin.RuinedPortalStructureAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;
import net.minecraft.world.level.levelgen.structure.structures.NetherFossilStructure;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalPiece;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalStructure;

/**
 * The gate every Nether-band structure opens with, in one place: is this world running the band, is this
 * the overworld, is this patch of it real-Nether core, and what does the core terrain look like here.
 *
 * <p>The band generates the <b>vanilla</b> Nether structures, re-sited: {@code minecraft:fortress},
 * {@code bastion_remnant}, {@code nether_fossil} and {@code ruined_portal_nether} themselves, so
 * {@code /locate} finds them by their real names and their own {@code spawn_overrides} and loot apply. Each
 * one's siting mixin asks this first, and they all decline the same way — quietly, by returning
 * {@code null}, whereupon vanilla's own siting runs untouched. That is what keeps the real Nether exactly as
 * it was: outside the overworld band this class always says no.</p>
 *
 * <p>Everything is read from the {@link NetherBandContext} server-start snapshot, so structure-start
 * creation (which runs on the worldgen thread pool) never touches the server.</p>
 */
public final class BandNetherStructures {

    /** Vanilla's bastion start pool — the only thing that distinguishes a bastion from any other jigsaw. */
    private static final ResourceLocation BASTION_START_POOL =
            ResourceLocation.withDefaultNamespace("bastion/starts");

    private BandNetherStructures() {}

    /**
     * True for the four vanilla structures the band re-sites. Class alone is not enough for two of them:
     * {@link JigsawStructure} is shared with villages, outposts and trial chambers, and
     * {@link RuinedPortalStructure} is shared with the six overworld portal variants — a jungle portal has
     * no business in the netherrack. Both are pinned down by their configuration instead.
     */
    public static boolean isBandEligible(Structure structure) {
        if (structure instanceof NetherFortressStructure) return true;
        if (structure instanceof NetherFossilStructure) return true;
        if (structure instanceof RuinedPortalStructure portal) return isNetherRuinedPortal(portal);
        if (structure instanceof JigsawStructure jigsaw) return isBastion(jigsaw);
        return false;
    }

    /** True for {@code minecraft:ruined_portal_nether} — every one of its setups places in the Nether. */
    public static boolean isNetherRuinedPortal(RuinedPortalStructure portal) {
        try {
            var setups = ((RuinedPortalStructureAccessor) portal).dungeontrain$setups();
            return !setups.isEmpty() && setups.stream()
                    .allMatch(setup -> setup.placement() == RuinedPortalPiece.VerticalPlacement.IN_NETHER);
        } catch (Throwable t) {
            return false;   // unreadable → treat as a vanilla structure and leave it alone
        }
    }

    /** True for {@code minecraft:bastion_remnant}, identified by vanilla's own start pool. */
    public static boolean isBastion(JigsawStructure jigsaw) {
        try {
            return ((JigsawStructureAccessor) (Object) jigsaw).dungeontrain$startPool()
                    .unwrapKey().map(key -> key.location().equals(BASTION_START_POOL)).orElse(false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Open the band at this placement attempt, or {@code null} if a structure may not stand here.
     *
     * @param footprintRadius blocks the structure may spill past its start chunk on X — the whole span must
     *                        be core, or the structure would emerge from the netherrack into the mountains
     */
    public static Site open(Structure.GenerationContext context, int footprintRadius) {
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
    public record Site(NetherBandContext ctx, NetherCoreGeometry geometry) {

        /** The track bed's world Y — where the Nether's own centre line lands. */
        public int bedY() {
            return geometry.bedY();
        }

        /** Band world Y for one of vanilla's Nether-space placement heights. */
        public int bandY(int netherY) {
            return NetherStructureSiting.bandY(bedY(), netherY);
        }

        /** {@link #bandY} clamped into the slab that actually carries core terrain. */
        public int bandYInCore(int netherY) {
            return NetherStructureSiting.clampToCore(bedY(), bandY(netherY));
        }

        /**
         * A column of the core terrain at this band position, sampled at the <b>edge-waved</b> X so it
         * describes the very blocks {@code NetherTransitionFeature} will stamp there.
         */
        public NetherCoreGeometry.Column column(int worldX, int worldZ) {
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
        public boolean biomeAllows(HolderSet<Biome> biomes, int worldX, int worldZ) {
            if (ctx.netherCoreBiomes() == null) return false;
            Holder<Biome> coreBiome = ctx.netherCoreBiomes().biomeAt(worldX, worldZ);
            return biomes.contains(coreBiome);
        }
    }
}
