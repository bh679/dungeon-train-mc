package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.factories.IDhApiWrapperFactory;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkProcessingEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import games.brennan.dungeontrain.portal.PortalTwinRegion;
import org.slf4j.Logger;

/**
 * Keeps the sunk zone's attic out of <b>Distant Horizons'</b> LOD data, through DH's chunk-processing
 * event (DH's own hook for X-ray prevention): each attic block DH reads is stored as air. What and where
 * is decided by {@link DistantHorizonsSunkAttic}; this class only speaks DH.
 *
 * <p><b>Cost.</b> DH fires the event on its worker threads, once per block or biome change while it
 * turns a chunk into LODs — never at render time. The attic is looked up once per chunk and cached per
 * thread, so every other call is a compare and a Y range check.</p>
 *
 * <p><b>Existing data.</b> DH skips chunks it has already stored unchanged, so a world explored before
 * this keeps its attic LODs until those chunks are re-processed.</p>
 *
 * <p><b>Loading.</b> Names DH types, so it is reached only behind the {@code ModList} check in
 * {@code DungeonTrain#commonSetup}. Any failure degrades to "DH stores everything", logged once.</p>
 */
public final class DistantHorizonsAtticFilter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The last chunk this thread resolved, so a chunk's many events share one lookup. */
    private static final class ChunkCache {
        Object level;
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;
        PortalTwinRegion attic;
    }

    private static final ThreadLocal<ChunkCache> CACHE = ThreadLocal.withInitial(ChunkCache::new);
    private static volatile IDhApiBlockStateWrapper air = null;
    private static volatile boolean broken = false;

    private DistantHorizonsAtticFilter() {}

    /** Bind the filter to DH. Call once, only when DH is loaded. */
    public static void register() {
        try {
            DhApiEventRegister.on(DhApiChunkProcessingEvent.class, new AtticFilter());
            DistantHorizonsSunkAttic.register();
            LOGGER.info("[DungeonTrain] Distant Horizons will leave the sunk zone's dimensional carriages out of its LODs");
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not bind the Distant Horizons chunk filter; "
                    + "dimensional carriages may show in the distance over the sunk zone: {}", t.toString());
        }
    }

    private static PortalTwinRegion atticFor(DhApiChunkProcessingEvent.EventParam param) {
        Object level = param.levelWrapper.getWrappedMcObject();
        ChunkCache cache = CACHE.get();
        if (cache.level != level || cache.chunkX != param.chunkX || cache.chunkZ != param.chunkZ) {
            cache.level = level;
            cache.chunkX = param.chunkX;
            cache.chunkZ = param.chunkZ;
            cache.attic = DistantHorizonsSunkAttic.atticForChunk(level, param.chunkX);
        }
        return cache.attic;
    }

    private static IDhApiBlockStateWrapper air() {
        IDhApiBlockStateWrapper cached = air;
        if (cached != null) return cached;
        IDhApiWrapperFactory factory = DhApi.Delayed.wrapperFactory;
        if (factory == null) return null;
        cached = factory.getAirBlockStateWrapper();
        air = cached;
        return cached;
    }

    private static final class AtticFilter extends DhApiChunkProcessingEvent {
        @Override
        public void blockOrBiomeChangedDuringChunkProcessing(DhApiEventParam<EventParam> event) {
            if (broken) return;
            try {
                EventParam param = event.value;
                if (param.getBlockOverride() != null) return; // another mod has already decided
                if (!DistantHorizonsSunkAttic.hides(atticFor(param), param.blockPosY)) return;
                if (param.currentBlock != null && param.currentBlock.isAir()) return;
                IDhApiBlockStateWrapper replacement = air();
                if (replacement != null) param.setBlockOverride(replacement);
            } catch (Throwable t) {
                broken = true;
                LOGGER.warn("[DungeonTrain] Distant Horizons chunk filter failed and is now off: {}", t.toString());
            }
        }
    }
}
