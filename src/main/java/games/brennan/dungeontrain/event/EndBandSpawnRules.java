package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.EndBandSampler;
import games.brennan.dungeontrain.worldgen.MixBand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/**
 * What may spawn naturally in a <b>BetterEnd</b> End-band column, under the overworld's sky.
 *
 * <p>{@link BandMobSpawnEvents} allows only endermen across the End band; here a BetterEnd column also
 * admits whatever its own biome lists ({@link #admits}), which is how slimes, shadow walkers, dragonflies,
 * moths and End fish reach the band.</p>
 *
 * <p>Endermen and shadow walkers are ruled by light, in two checks, and both read the sky: the real End has
 * none, the band has the overworld's, so by day neither could ever spawn on an island. Both give way here
 * on a block that a real End would allow ({@link #skyDoesNotCount}): no block light, a floor the mob may
 * stand on, and not on Peaceful. Mobs with rules of their own (the End slime) are left to them.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EndBandSpawnRules {

    private static final ResourceLocation SHADOW_WALKER = ResourceLocation.fromNamespaceAndPath("betterend", "shadow_walker");

    private EndBandSpawnRules() {}

    /** Whether an ambient spawn stands in the End band: endermen everywhere, a biome's own mobs in BetterEnd columns. */
    static boolean admits(boolean enderman, boolean betterEndColumn, boolean listedByBiome) {
        return enderman || (betterEndColumn && listedByBiome);
    }

    /** Whether a light-ruled monster may ignore the sky: the real End's own limit is block light 0. */
    static boolean skyDoesNotCount(boolean betterEndColumn, boolean lightRuled, int blockLight, boolean peaceful) {
        return betterEndColumn && lightRuled && blockLight == 0 && !peaceful;
    }

    /** True for a band column generated from the BetterEnd End. */
    static boolean isBetterEndColumn(ServerLevel level, int x, int z) {
        if (!level.dimension().equals(Level.OVERWORLD)) return false;
        if (DisintegrationBand.middleRampAt(level, x, z) <= 0.0) return false;
        long seed = DungeonTrainWorldData.get(level).getGenerationSeed();
        return MixBand.cycleAtColumn(level, x, z).endSourceLookAt(x, z, seed) == CycleLayout.Style.BETTER
                && EndBandSampler.appliesTo(level.getServer(), CycleLayout.Style.BETTER);
    }

    /** Whether the biome at {@code pos} lists {@code type} in its own category. */
    static boolean listedByBiome(ServerLevel level, BlockPos pos, EntityType<?> type) {
        return level.getBiome(pos).value().getMobSettings().getMobs(type.getCategory()).unwrap().stream()
                .anyMatch(data -> data.type == type);
    }

    private static boolean isLightRuled(EntityType<?> type) {
        return type == EntityType.ENDERMAN || SHADOW_WALKER.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    private static boolean skyDoesNotCount(ServerLevel level, EntityType<?> type, BlockPos pos) {
        return isLightRuled(type)
                && skyDoesNotCount(isBetterEndColumn(level, pos.getX(), pos.getZ()), true,
                        level.getBrightness(LightLayer.BLOCK, pos), level.getDifficulty() == Difficulty.PEACEFUL);
    }

    /** The placement check: the mob's light rule, with a floor test standing in for the rest of it. */
    @SubscribeEvent
    public static void onSpawnPlacementCheck(MobSpawnEvent.SpawnPlacementCheck event) {
        if (event.getSpawnType() != MobSpawnType.NATURAL) return;
        ServerLevel level = event.getLevel().getLevel();
        BlockPos pos = event.getPos();
        if (!skyDoesNotCount(level, event.getEntityType(), pos)) return;
        BlockPos below = pos.below();
        if (level.getBlockState(below).isValidSpawn(level, below, event.getEntityType())) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.SUCCEED);
        }
    }

    /** The position check: a monster's {@code getWalkTargetValue} refuses bright blocks, so only obstruction is left to ask. */
    @SubscribeEvent
    public static void onPositionCheck(MobSpawnEvent.PositionCheck event) {
        if (event.getSpawnType() != MobSpawnType.NATURAL || event.getSpawner() != null) return;
        ServerLevel level = event.getLevel().getLevel();
        Mob mob = event.getEntity();
        if (!skyDoesNotCount(level, mob.getType(), mob.blockPosition())) return;
        event.setResult(mob.checkSpawnObstruction(level)
                ? MobSpawnEvent.PositionCheck.Result.SUCCEED
                : MobSpawnEvent.PositionCheck.Result.FAIL);
    }
}
