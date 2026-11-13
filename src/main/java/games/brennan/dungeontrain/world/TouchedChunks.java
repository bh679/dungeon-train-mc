package games.brennan.dungeontrain.world;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.worldgen.UntouchedChunkSaves;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Overworld chunks a player has changed, which are always saved ({@code UntouchedChunkSaves} drops the
 * rest once nobody is near them).
 *
 * <p>The flag lives on the chunk ({@link ModDataAttachments#CHUNK_TOUCHED}) so it survives a restart,
 * and is mirrored into a set of chunk keys so the entity save, which runs without the chunk in hand,
 * asks the same question. A chunk is touched when a player breaks, places or uses a block in it, when an
 * explosion breaks blocks in it, and when DT builds an exit portal in it ({@link #mark}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TouchedChunks {

    private static final LongSet KEYS = LongSets.synchronize(new LongOpenHashSet());

    private TouchedChunks() {}

    /** Marks the chunk holding {@code pos} as changed, if {@code level} is the overworld. */
    public static void mark(LevelAccessor level, BlockPos pos) {
        if (!(level instanceof ServerLevel server) || !server.dimension().equals(Level.OVERWORLD)) return;
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        KEYS.add(ChunkPos.asLong(cx, cz));
        LevelChunk chunk = server.getChunkSource().getChunkNow(cx, cz);
        if (chunk != null && !chunk.getData(ModDataAttachments.CHUNK_TOUCHED)) {
            chunk.setData(ModDataAttachments.CHUNK_TOUCHED, Boolean.TRUE);
            chunk.setUnsaved(true);
        }
    }

    public static boolean isTouched(ServerLevel level, ChunkAccess chunk) {
        return chunk.getData(ModDataAttachments.CHUNK_TOUCHED) || KEYS.contains(chunk.getPos().toLong());
    }

    public static boolean isTouched(ServerLevel level, ChunkPos pos) {
        if (KEYS.contains(pos.toLong())) return true;
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        return chunk != null && chunk.getData(ModDataAttachments.CHUNK_TOUCHED);
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.dimension().equals(Level.OVERWORLD)) return;
        ChunkAccess chunk = event.getChunk();
        if (chunk.getData(ModDataAttachments.CHUNK_TOUCHED)) KEYS.add(chunk.getPos().toLong());
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        mark(event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player) mark(event.getLevel(), event.getPos());
    }

    /** Opening a chest, filling a bucket, lighting a portal, writing to a lectern, hanging a photo. */
    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        mark(event.getLevel(), event.getPos());
        mark(event.getLevel(), event.getPos().relative(event.getFace() == null
                ? net.minecraft.core.Direction.UP : event.getFace()));
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        for (BlockPos pos : event.getAffectedBlocks()) mark(event.getLevel(), pos);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        UntouchedChunkSaves.onServerStopping(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        KEYS.clear();
        UntouchedChunkSaves.resetCounts();
    }
}
