package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carries "static" carriage-contents entities with their moving carriage.
 *
 * <p><b>Why this exists.</b> Sable only carries an entity with a moving sub-level as a side
 * effect of that entity calling {@link Entity#move} every tick: {@code move} runs Sable's
 * collision sweep, which records the entity's plot position and then re-anchors it each tick to
 * {@code subLevel.logicalPose().transformPosition(plotPosition)}. Mobs and villagers move every
 * tick (gravity / AI) so they ride. <b>End Crystals never call {@code move}</b> (their tick only
 * spins the beam), and <b>hanging entities</b> (paintings, item frames) never move either — so
 * Sable never binds them, and they sit at their spawn coordinate while the carriage drives away.
 * The symptom the player sees is a crystal "left behind" by the train.</p>
 *
 * <p><b>What this does.</b> For the small set of contents entities Sable won't carry, DT replays
 * Sable's own anchor formula server-side, once per tick, through the {@link ManagedShip}
 * abstraction: resolve the carriage that owns the entity's fixed spawn <i>shipyard</i> coordinate
 * ({@link Shipyards#of}{@code .findAt}, internally Sable's {@code getContaining}) and set the
 * entity's world position to {@link ManagedShip#shipToWorld} of that coordinate. The anchor
 * coordinate is the spawn shipyard position {@link CarriageContentsPlacer} already persists on
 * every contents entity ({@link CarriageContentsPlacer#NBT_SPAWN_SHIPYARD_X} etc.), so this
 * survives save / reload and needs no new per-entity state on disk.</p>
 *
 * <p><b>Membership.</b> The tracked set is a per-dimension {@code UUID -> shipyard-pos} map,
 * populated on {@link EntityJoinLevelEvent} (which fires both for the fresh spawn and on every
 * sub-level / chunk reload, giving free reload rehydration) and cleared on
 * {@link EntityLeaveLevelEvent}. Only entities carrying a DT contents tag are tracked, so a
 * crystal or painting a player placed in the plain world is never touched.</p>
 *
 * <p><b>Cost.</b> {@link #onLevelTick} walks only this registry, never the level — O(tracked
 * static contents entities), which is single digits in practice and zero when no such decor
 * exists. Entities on culled carriages resolve to {@code null} and are skipped. See the plan's
 * Performance section.</p>
 *
 * <p><b>Known limitation.</b> Position is corrected; a hanging entity's facing is not rotated on
 * the rare curved / reversing track sections. Trains are overwhelmingly straight-line translation,
 * so this is cosmetic.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TrainStaticContentsCarrier {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Per-dimension {@code UUID -> spawn shipyard position}. Keyed by dimension because entity
     * UUIDs are unique per save but an entity lives in exactly one level at a time; keying keeps
     * {@link #onLevelTick} from probing another dimension's entities. Concurrent maps because
     * entity lifecycle events and the tick handler both touch this on the server thread today, but
     * the structure is cheap insurance against future off-thread callers.
     */
    private static final Map<ResourceKey<Level>, Map<UUID, Vector3d>> TRACKED = new ConcurrentHashMap<>();

    private TrainStaticContentsCarrier() {}

    /**
     * True for the entity classes Sable's move-driven carry never binds — End Crystals and hanging
     * entities (paintings, item frames). Kept as one predicate so the carried set is easy to widen
     * if another static content type surfaces the same bug.
     */
    private static boolean isCarriableType(Entity entity) {
        return entity instanceof EndCrystal || entity instanceof HangingEntity;
    }

    /** True if {@code entity} carries a {@link CarriageContentsPlacer#DT_CONTENTS_TAG_PREFIX} tag. */
    private static boolean hasContentsTag(Entity entity) {
        for (String tag : entity.getTags()) {
            if (tag.startsWith(CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getEntity();
        if (!isCarriableType(entity) || !hasContentsTag(entity)) return;

        double x = entity.getPersistentData().getDouble(CarriageContentsPlacer.NBT_SPAWN_SHIPYARD_X);
        double y = entity.getPersistentData().getDouble(CarriageContentsPlacer.NBT_SPAWN_SHIPYARD_Y);
        double z = entity.getPersistentData().getDouble(CarriageContentsPlacer.NBT_SPAWN_SHIPYARD_Z);
        // (0,0,0) means no anchor was ever persisted (a contents entity from before the spawn NBT
        // existed). Nothing to anchor to — skip rather than yank it to the world origin.
        if (x == 0.0 && y == 0.0 && z == 0.0) return;

        TRACKED.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>())
            .put(entity.getUUID(), new Vector3d(x, y, z));
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Map<UUID, Vector3d> byUuid = TRACKED.get(level.dimension());
        if (byUuid != null) {
            byUuid.remove(event.getEntity().getUUID());
        }
    }

    /**
     * Re-anchor every tracked static contents entity in {@code level} to its carriage. Called once
     * per server tick from {@link games.brennan.dungeontrain.event.TrainTickEvents}. No-op when the
     * dimension has no tracked entities.
     */
    public static void onLevelTick(ServerLevel level) {
        Map<UUID, Vector3d> byUuid = TRACKED.get(level.dimension());
        if (byUuid == null || byUuid.isEmpty()) return;

        for (Map.Entry<UUID, Vector3d> e : byUuid.entrySet()) {
            Entity entity = level.getEntity(e.getKey());
            if (entity == null) {
                // Momentarily absent (culled with its carriage). Keep the entry: EntityLeaveLevelEvent
                // already removed genuinely-gone entities, and a cull re-fires join on reload.
                continue;
            }
            Vector3d shipyardPos = e.getValue();
            // findAt takes the fixed shipyard coordinate and returns the carriage that owns it —
            // internally Sable's getContaining, the same lookup Sable uses for the entities it does
            // carry. Null when that carriage is culled/removed; leave the entity put until it reloads.
            ManagedShip ship = Shipyards.of(level).findAt(
                BlockPos.containing(shipyardPos.x, shipyardPos.y, shipyardPos.z));
            if (ship == null || !ship.isResident()) continue;

            // shipToWorld mutates the passed vector, so hand it a throwaway copy and keep the stored
            // shipyard coordinate intact for next tick.
            Vector3d world = ship.shipToWorld(new Vector3d(shipyardPos));
            entity.setPos(world.x, world.y, world.z);
        }
    }
}
