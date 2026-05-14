package games.brennan.dungeontrain.track;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deduped WARN logger for mob-variant entries on track / pillar / tunnel
 * variants. The schema (v7) accepts {@code "entity"} entries on every
 * variant sidecar, but track-side runtime spawning is out of scope for
 * the initial mob-variants feature: those cells become air at runtime
 * and the mob is dropped.
 *
 * <p>This emitter logs once per unique {@code (context, localPos, entityId)}
 * combination so the author sees a single visible signal per dropped
 * authoring without spamming the log every time the chunk regenerates.</p>
 */
public final class TrackVariantMobs {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private TrackVariantMobs() {}

    public static void warnDropped(String context, BlockPos localPos, ResourceLocation entityId) {
        String key = context + "@" + localPos.getX() + "," + localPos.getY() + "," + localPos.getZ()
            + "=" + (entityId == null ? "?" : entityId);
        if (WARNED.add(key)) {
            LOGGER.warn("[DungeonTrain] Mob-variant on {} sidecar at local {} (entity={}) — track/tunnel mob spawning is not yet supported, cell will be air.",
                context, localPos, entityId);
        }
    }
}
