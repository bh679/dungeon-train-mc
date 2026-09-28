package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.WorldUpsideDownEvents;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lights a band-carved chunk as if every block in it stood on the surface: every sky-light section
 * is stamped with full daylight and no propagation runs.
 *
 * <p>The band carve passes rewrite blocks through the raw {@code LevelChunkSection#setBlockState}
 * path (the Sable-safe write), which the light engine never sees, so a carved chunk keeps the sky
 * light computed for the terrain it had <em>before</em> the rewrite — anything that used to be
 * underground stays dark even when it is now open air or the cap of a floating sphere. A real
 * recompute costs a full chunk light pass; this instead installs a section of {@code 15}s through
 * {@link LevelLightEngine#queueSectionData}, the same call the chunk loader uses to hand saved
 * light to the engine (on the server it is a queued task on the light thread, so it is a few
 * allocations per section). Block light is untouched, so torches still work. The trade — sphere
 * undersides and cave interiors read as daylight — is the point: these bands float in open sky.</p>
 *
 * <p>The stamp lands asynchronously and a chunk's first send to a player can beat it, so the chunk
 * is resent two ticks later ({@link WorldUpsideDownEvents#resendChunk}); a chunk unloaded by then
 * is simply dropped (its saved light is already the stamped one).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SunlitChunks {

    private static final int FULL_SKY = 15;
    /** Ticks between the stamp and the resend — enough for the light thread to apply the queued data. */
    private static final int RESEND_DELAY_TICKS = 2;

    /** Chunks awaiting their resend: chunk key → server tick the stamp was queued on. Per-level maps. */
    private static final Map<ServerLevel, ConcurrentHashMap<Long, Long>> PENDING = new ConcurrentHashMap<>();

    private SunlitChunks() {}

    /** Stamp every sky-light section of {@code chunk} with full daylight and schedule a resend. */
    public static void sunlight(ServerLevel level, ChunkAccess chunk) {
        LevelLightEngine engine = level.getLightEngine();
        ChunkPos pos = chunk.getPos();
        for (int sy = engine.getMinLightSection(); sy < engine.getMaxLightSection(); sy++) {
            engine.queueSectionData(LightLayer.SKY, SectionPos.of(pos.x, sy, pos.z), new DataLayer(FULL_SKY));
        }
        PENDING.computeIfAbsent(level, l -> new ConcurrentHashMap<>())
                .put(pos.toLong(), (long) level.getServer().getTickCount());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        long now = event.getServer().getTickCount();
        for (Map.Entry<ServerLevel, ConcurrentHashMap<Long, Long>> perLevel : PENDING.entrySet()) {
            ServerLevel level = perLevel.getKey();
            Iterator<Map.Entry<Long, Long>> it = perLevel.getValue().entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, Long> e = it.next();
                if (now - e.getValue() < RESEND_DELAY_TICKS) continue;
                it.remove();
                long key = e.getKey();
                LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
                if (chunk != null) WorldUpsideDownEvents.resendChunk(level, chunk);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PENDING.clear();
    }
}
