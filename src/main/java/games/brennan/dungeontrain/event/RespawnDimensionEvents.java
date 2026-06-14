package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.StartingDimension;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

/**
 * Rolls a random starting dimension when the player clicks the vanilla
 * "Respawn" button on the death screen. 1% End / 5% Nether / 94% Overworld —
 * the same distribution {@link games.brennan.dungeontrain.client.DeathScreenLayoutHandler}
 * uses for the "New World" / "Same World" buttons, but consumed server-side
 * since vanilla respawn keeps the player in the existing world instance.
 *
 * <p>If the rolled dimension differs from where vanilla just placed the
 * player, this handler:</p>
 * <ol>
 *   <li>Calls {@link TrainBootstrapEvents#ensureTrainSpawned} to lay down a
 *       seed train at the standard origin in the rolled dimension if one
 *       doesn't already exist. The per-tick appender extends it at gameplay
 *       speed afterward — no eager-fill on this path.</li>
 *   <li>Calls {@link PlayerJoinEvents#computeBootstrapPlacement} to pick a
 *       spawn position with line of sight to the train.</li>
 *   <li>Cross-dim teleports the player via {@code player.teleportTo}.</li>
 * </ol>
 *
 * <p>Skip-rules:</p>
 * <ul>
 *   <li>{@link PlayerEvent.PlayerRespawnEvent#isEndConquered()} — End portal
 *       credits, not a death.</li>
 *   <li>Hardcore mode — vanilla kicks the player to spectator immediately
 *       after this event fires; teleporting first then being kicked is a
 *       jarring no-op.</li>
 *   <li>{@code !data.startsWithTrain()} — the world has the auto-train
 *       system disabled; respect that choice.</li>
 *   <li>Rolled dimension already matches the player's current respawn dim —
 *       vanilla flow handles it. Most respawns hit this branch (94% Overworld
 *       on an Overworld-started world).</li>
 *   <li>Rolled dimension's {@code ServerLevel} not loaded — defensive guard
 *       for the unlikely case a datapack removed a vanilla dimension.</li>
 * </ul>
 *
 * <p>Server-side; common-scope so dedicated servers also run it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class RespawnDimensionEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RespawnDimensionEvents() {}

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null || server.isHardcore()) return;

        ServerLevel overworld = server.overworld();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return;

        StartingDimension rolled = StartingDimension.rollRespawnDimension(
                overworld.random.nextDouble());
        LOGGER.info("[DungeonTrain] Respawn rolled startingDimension={} for {}",
                rolled, player.getName().getString());

        if (player.serverLevel().dimension() == rolled.levelKey()) return;

        ServerLevel target = server.getLevel(rolled.levelKey());
        if (target == null) {
            LOGGER.warn("[DungeonTrain] Rolled dim {} not loaded — vanilla respawn flow", rolled);
            return;
        }

        TrainBootstrapEvents.ensureTrainSpawned(target, data);

        // Prefer landing the player ON the train (flatbed deck) so a cross-dim
        // respawn matches the spawn-on-train behavior. Yaw -90 faces +X (travel
        // direction). Falls back to the ground spawn pose beside the train if
        // the train hasn't bound yet.
        PlayerJoinEvents.FlatbedTarget flat = PlayerJoinEvents.findFlatbedTarget(target, data);
        if (flat != null) {
            LOGGER.info("[DungeonTrain] Respawn placing {} on train in {} at ({}, {}, {})",
                    player.getName().getString(), rolled,
                    String.format("%.1f", flat.x()), String.format("%.1f", flat.y()), String.format("%.1f", flat.z()));
            player.teleportTo(target, flat.x(), flat.y(), flat.z(), -90.0f, 0.0f);
            return;
        }

        PlayerJoinEvents.SpawnPlacement sp = PlayerJoinEvents.computeBootstrapPlacement(
                target, data.dims(), data.getTrainY());
        LOGGER.info("[DungeonTrain] Respawn teleporting {} to {} (ground fallback) at pos=({}, {}, {}) yaw={} pitch={}",
                player.getName().getString(), rolled,
                String.format("%.1f", sp.x()), sp.y(), String.format("%.1f", sp.z()),
                String.format("%.1f", sp.yaw()), String.format("%.1f", sp.pitch()));
        player.teleportTo(target, sp.x(), sp.y(), sp.z(), sp.yaw(), sp.pitch());
    }
}
