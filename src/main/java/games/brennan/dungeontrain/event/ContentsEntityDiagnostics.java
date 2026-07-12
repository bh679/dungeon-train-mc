package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.debug.DebugFlags;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import org.slf4j.Logger;

/**
 * Lifecycle subscriber for {@link CarriageContentsPlacer}-spawned entities.
 * Identifies tagged contents-entities via {@code dungeontrain_contents_pidx_*}
 * tags (see {@link CarriageContentsPlacer#contentsTagFor}) and logs their
 * {@code EntityJoinLevelEvent} and {@code EntityLeaveLevelEvent} transitions.
 *
 * <p>Why a lifecycle subscriber: the user reported that animals/villagers
 * stop appearing in newly spawned carriages after the train has been running
 * a while. The two most common silent-removal paths are
 * {@link net.minecraft.world.entity.Entity.RemovalReason#DISCARDED} (mob
 * cap / despawn) and {@link net.minecraft.world.entity.Entity.RemovalReason#UNLOADED_TO_CHUNK}
 * (chunk unload before player re-enters). Logging the removal reason at the
 * source lets us bisect which path is causing it.</p>
 *
 * <p>Log volume is bounded: each carriage has ≤8 contents entities; only
 * tagged entities log. A typical 45-carriage train at full population emits
 * ~360 join events at startup and a few thousand leave/join events across a
 * ten-minute soak test. {@code LOGGER.info} keeps these visible without
 * needing log-level changes.</p>
 */
public final class ContentsEntityDiagnostics {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ContentsEntityDiagnostics() {}

    /**
     * Returns the contents-tag attached to {@code entity}, or {@code null}
     * if the entity isn't a tracked contents-spawn. Walks the entity's tag
     * set looking for the {@code DT_CONTENTS_TAG_PREFIX} marker — a single
     * O(tags) check, and most entities in the world carry no tags at all.
     */
    private static String contentsTag(Entity entity) {
        for (String tag : entity.getTags()) {
            if (tag.startsWith(CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX)) {
                return tag;
            }
        }
        return null;
    }

        public static void onEntityJoin(net.minecraft.world.entity.Entity joiningEntity, net.minecraft.world.level.Level joinLevel, boolean loadedFromDisk) {
        if (!DebugFlags.logContentsEntities()) return;
        Level level = joinLevel;
        if (level.isClientSide) return;
        Entity entity = joiningEntity;
        String tag = contentsTag(entity);
        if (tag == null) return;
        long spawnTick = entity.getPersistentData().getLong(CarriageContentsPlacer.NBT_SPAWN_GAME_TICK);
        long age = (spawnTick > 0) ? (level.getGameTime() - spawnTick) : -1L;
        double spawnX = entity.getPersistentData().getDouble(CarriageContentsPlacer.NBT_SPAWN_SHIPYARD_X);
        double spawnY = entity.getPersistentData().getDouble(CarriageContentsPlacer.NBT_SPAWN_SHIPYARD_Y);
        double spawnZ = entity.getPersistentData().getDouble(CarriageContentsPlacer.NBT_SPAWN_SHIPYARD_Z);
        LOGGER.info("[DungeonTrain] Entity JOIN: type={} uuid={} tag={} pos=({},{},{}) spawn=({},{},{}) ageTicks={}",
            entity.getType().getDescriptionId(), entity.getUUID(), tag,
            String.format("%.2f", entity.getX()),
            String.format("%.2f", entity.getY()),
            String.format("%.2f", entity.getZ()),
            String.format("%.2f", spawnX),
            String.format("%.2f", spawnY),
            String.format("%.2f", spawnZ),
            age);
    }

        public static void onEntityLeave(net.minecraft.world.entity.Entity leftEntity, net.minecraft.world.level.Level leaveLevel) {
        if (!DebugFlags.logContentsEntities()) return;
        Level level = leaveLevel;
        if (level.isClientSide) return;
        Entity entity = leftEntity;
        String tag = contentsTag(entity);
        if (tag == null) return;
        long spawnTick = entity.getPersistentData().getLong(CarriageContentsPlacer.NBT_SPAWN_GAME_TICK);
        long age = (spawnTick > 0) ? (level.getGameTime() - spawnTick) : -1L;
        Entity.RemovalReason reason = entity.getRemovalReason();
        // Capture stack trace so we can see WHO triggered the removal. The
        // EntityLeaveLevelEvent fires synchronously inside the entity-remove
        // pipeline, so the upper frames show the caller chain. Cheap to
        // build a Throwable (no fillInStackTrace cost ourselves —
        // {@code new Throwable()} does it). Only fires for tagged entities,
        // so log volume stays bounded.
        StackTraceElement[] stack = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(stack.length, 20); i++) {
            sb.append("\n      at ").append(stack[i]);
        }
        // Most leaves are routine chunk-unload as the train rolls past the
        // entity's slot — we still log them so a "discarded" reason stands
        // out by contrast in the log stream.
        LOGGER.info("[DungeonTrain] Entity LEAVE: type={} uuid={} tag={} pos=({},{},{}) reason={} ageTicks={} stack:{}",
            entity.getType().getDescriptionId(), entity.getUUID(), tag,
            String.format("%.2f", entity.getX()),
            String.format("%.2f", entity.getY()),
            String.format("%.2f", entity.getZ()),
            reason, age, sb);
    }
}
