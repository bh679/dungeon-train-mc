package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.WorldFloor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * Nothing spawns on its own below the world's floor.
 *
 * <p><b>Why there is anything down there to spawn on.</b> A Dungeon Train overworld's
 * {@code dimension_type} runs 80 blocks deeper than its {@code noise_settings}, leaving an empty
 * basement under the bedrock row for the portal system's twin structures — see {@link WorldFloor}.
 * {@code WorldGenRegionFloorMixin} keeps world <i>generation</i> out of that space; this keeps
 * ambient <i>spawning</i> out of it, which is a separate path with a separate reason to reach it:
 * {@code NaturalSpawner.getRandomPosWithin} draws its candidate Y from
 * {@code getMinBuildHeight()} — the bottom of the basement, not the bedrock — so about a fifth of
 * every chunk's spawn attempts start under the world. Most die for want of a solid block, but a
 * stamped dimensional carriage is solid, unlit and next to a player, which is the whole recipe.</p>
 *
 * <p><b>Wider than {@link DimensionalCarriageSpawnGuard}, and not a replacement for it.</b> That guard asks
 * whether a position is inside a portal structure the pair index currently knows about; this one
 * asks only how deep it is, so it also covers a room's roof, the space beside it, and any structure
 * the index has not registered yet. The two overlap but neither contains the other — a Compatible
 * Terrain world has no basement at all, and there the portal guard is the only one that fires.</p>
 *
 * <p><b>Ambient only.</b> {@link MobSpawnType#NATURAL} and {@link MobSpawnType#CHUNK_GENERATION},
 * the pair {@code BandMobSpawnEvents} and {@code DimensionalCarriageSpawnGuard} also treat as ambient.
 * Everything deliberate still works below the floor — a dimensional carriage's authored mobs (which spawn as
 * {@code SPAWN_EGG}), commands, spawn eggs, breeding, conversion, a mob a player leads in.</p>
 *
 * <p><b>A no-op wherever there is no basement.</b> In Compatible Terrain mode, the nether, the end
 * or another mod's dimension the generator reaches the build floor, so {@code bedrockY} is
 * {@code getMinBuildHeight()}, no block can exist below it and the test never passes. Nothing here
 * branches on which world it is in.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BasementSpawnGuard {

    private BasementSpawnGuard() {}

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        MobSpawnType type = event.getSpawnType();
        if (type != MobSpawnType.NATURAL && type != MobSpawnType.CHUNK_GENERATION) return;

        ServerLevel level = event.getLevel().getLevel();
        // Overworld-only, as the other two ambient guards are: a Sable sub-level is a level of its
        // own and asking it for a terrain generator's floor means nothing.
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        if (WorldFloor.isBelowFloor(Mth.floor(event.getY()), WorldFloor.bedrockY(level))) {
            event.setSpawnCancelled(true);
        }
    }
}
