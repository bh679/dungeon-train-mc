package games.brennan.dungeontrain.worldgen.feature;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.worldgen.StacksBand;
import games.brennan.dungeontrain.worldgen.StampRandom;
import games.brennan.dungeontrain.worldgen.VanillaTemplateCatalogue;
import games.brennan.dungeontrain.worldgen.WorldFloor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.List;

/**
 * Worldgen feature for the stacks band ({@link StacksBand}): in a {@link StacksBand.Kind#STACK} chunk —
 * generated all-air by {@code NoiseBasedChunkGeneratorMixin} — stamps one vanilla structure piece
 * repeatedly, edge-to-edge, from the world floor to near build height. A tower of one thing; the next
 * tower is a different thing.
 *
 * <p>Which piece: a seed-stable pick from the {@link VanillaTemplateCatalogue}, rerolled (deterministically,
 * up to {@link #MAX_ATTEMPTS} times) when the piece does not fit a single chunk footprint — the stamp
 * must stay inside this chunk so towers never straddle into a neighbour's decoration window, and a
 * chunk the size of a village house is the scale the band is designed around. Rotation is fixed per
 * tower, the same for every layer. Loot seeds come from the position-pure {@link StampRandom}, so
 * twin same-seed runs roll identical chests.</p>
 *
 * <p>Wired by datapack: {@code data/dungeontrain/worldgen/configured_feature/stacks.json} →
 * {@code placed_feature/stacks.json} → {@code neoforge/biome_modifier/track_bed_overworld.json}, ahead of
 * the track bed. Whitelisted in {@code ChunkGeneratorDecorationMixin} so it survives the void-chunk
 * decoration skip that drops every vanilla feature in the band. Runs on the worldgen worker with a
 * {@link WorldGenLevel} — the stamp idiom is {@code TunnelPlacer.stampTemplateWorldgen}'s, not
 * {@code TemplateStamp}'s (which is {@code ServerLevel}-only).</p>
 */
public class StacksFeature extends Feature<NoneFeatureConfiguration> {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Widest/deepest template footprint a tower may use — one chunk, so the stamp never leaves it. */
    static final int MAX_FOOTPRINT = 16;
    /** Shortest piece worth stacking (flat pads / paths are not towers). */
    static final int MIN_HEIGHT = 3;
    /** Smallest footprint area worth stacking (a 1×N sliver reads as a pole, not a building). */
    static final int MIN_AREA = 9;
    /** Rows kept clear under the build-height cap. */
    static final int TOP_MARGIN = 4;
    /** Deterministic rerolls before giving up on a chunk (each attempt salts the catalogue pick). */
    static final int MAX_ATTEMPTS = 8;

    public StacksFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        long genT0 = GenProfiler.t0();
        try {
            return placeInner(ctx);
        } finally {
            GenProfiler.add(GenProfiler.Bucket.STACKS_FEATURE, genT0);
        }
    }

    private boolean placeInner(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        try {
            WorldGenLevel level = ctx.level();
            ChunkPos chunkPos = new ChunkPos(ctx.origin());

            ServerLevel serverLevel = level.getLevel();
            if (!serverLevel.dimension().equals(Level.OVERWORLD)) return false;
            MinecraftServer server = serverLevel.getServer();
            if (server == null) return false;
            ServerLevel overworld = server.overworld();
            if (overworld == null) return false;

            if (StacksBand.kindOf(overworld, chunkPos.x, chunkPos.z) != StacksBand.Kind.STACK) return false;

            DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
            long seed = data.getGenerationSeed();
            List<ResourceLocation> catalogue = VanillaTemplateCatalogue.ids(server);
            if (catalogue.isEmpty()) return false;

            Rotation rotation = StacksBand.rotationFor(seed, chunkPos.x, chunkPos.z);
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                ResourceLocation id = VanillaTemplateCatalogue.pick(catalogue, seed, chunkPos.x, chunkPos.z, attempt);
                if (id == null) return false;
                StructureTemplate template = serverLevel.getStructureManager().getOrCreate(id);
                Vec3i size = template.getSize(rotation);
                if (!fits(size)) continue;
                boolean placed = stampTower(level, chunkPos, template, size, rotation);
                // DEBUG level: run/logs/debug.log carries it for verification; latest.log stays quiet.
                LOGGER.debug("[DungeonTrain] stacks: chunk ({},{}) tower {} size {}x{}x{} rot {} attempt {} placed={}",
                    chunkPos.x, chunkPos.z, id, size.getX(), size.getY(), size.getZ(), rotation, attempt, placed);
                return placed;
            }
            return false;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] stacks feature failed at {}", ctx.origin(), t);
            return false;
        }
    }

    /** Pure fit test on a rotated template size. Package-private for unit tests. */
    static boolean fits(Vec3i size) {
        int x = size.getX(), y = size.getY(), z = size.getZ();
        if (x <= 0 || y <= 0 || z <= 0) return false;
        if (x > MAX_FOOTPRINT || z > MAX_FOOTPRINT) return false;
        if (y < MIN_HEIGHT) return false;
        return x * z >= MIN_AREA;
    }

    /**
     * How many layers of a piece {@code pieceHeight} tall fit between {@code floorY} (inclusive) and
     * {@code maxBuildHeight} (exclusive) with {@link #TOP_MARGIN} rows kept clear. Package-private for tests.
     */
    static int layerCount(int floorY, int maxBuildHeight, int pieceHeight) {
        if (pieceHeight <= 0) return 0;
        int span = maxBuildHeight - TOP_MARGIN - floorY;
        return Math.max(0, span / pieceHeight);
    }

    private static boolean stampTower(WorldGenLevel level, ChunkPos chunkPos, StructureTemplate template,
                                      Vec3i size, Rotation rotation) {
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setRotation(rotation)
            .setIgnoreEntities(true)
            // Honour the template's own dry/wet state; nothing around a tower holds water to inherit.
            .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING)
            .addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK)
            // Village / bastion / ancient-city pieces are full of jigsaw blocks; swap them for their
            // final state (the same pass vanilla's jigsaw assembler applies).
            .addProcessor(JigsawReplacementProcessor.INSTANCE);

        // Rotation pivots about the template origin, so the rotated box can extend into negative X/Z.
        // Centre the box in the chunk regardless of rotation by cancelling its own min corner.
        BoundingBox box = template.getBoundingBox(settings, BlockPos.ZERO);
        int wantMinX = chunkPos.getMinBlockX() + (MAX_FOOTPRINT - size.getX()) / 2;
        int wantMinZ = chunkPos.getMinBlockZ() + (MAX_FOOTPRINT - size.getZ()) / 2;
        int x0 = wantMinX - box.minX();
        int z0 = wantMinZ - box.minZ();

        int floorY = WorldFloor.bedrockY(level);
        int layers = layerCount(floorY, level.getMaxBuildHeight(), size.getY());
        if (layers <= 0) return false;

        for (int layer = 0; layer < layers; layer++) {
            BlockPos origin = new BlockPos(x0, floorY + layer * size.getY(), z0);
            // Position-pure random: only consumed for container LootTableSeeds (see StampRandom).
            template.placeInWorld(level, origin, origin, settings,
                StampRandom.at(level.getSeed(), origin),
                CarriageStampGuard.STAMP_FLAGS);
        }
        return true;
    }
}
