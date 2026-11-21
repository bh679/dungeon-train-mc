package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.requirement.AdvancementRequirements;
import games.brennan.dungeontrain.event.AchievementEvents;
import games.brennan.dungeontrain.event.PlayerActivityTracker;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "One Sitting" — travel {@link #DEFAULT_CARRIAGES} carriages without saving or going AFK.
 *
 * <p>A sitting starts at the travelled-carriage reading ({@link AchievementEvents#effectiveTravelled})
 * the player had when it began, and the streak is the distance from there. The baseline lives only in
 * memory, so anything that saves the player and takes them out of the world — Exit to Title, quitting,
 * disconnecting, a nap (always a fresh login into a new world) — ends the sitting by construction: the
 * next login starts a new one.</p>
 *
 * <p>Going AFK ({@link PlayerActivityTracker#isAfk(ServerPlayer)}: paused without using the screen,
 * mouse idle, or input idle) restarts the sitting from wherever the player is, checked once a second.
 * A new life restarts it too: its travelled reading falls back below the baseline.</p>
 *
 * <p>Server-thread only.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class OneSittingStreak {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/one_sitting");
    /** The shipped requirement; the advancement JSON's {@code threshold} overrides it. */
    static final int DEFAULT_CARRIAGES = 3000;
    /** The {@code gameplay_action} id the advancement listens for. */
    static final String ACTION_ID = "one_sitting_carts";
    /** How often the AFK scan runs (1 s). */
    static final int SCAN_INTERVAL_TICKS = 20;

    /** Travelled reading at which each online player's current sitting began. */
    private static final Map<UUID, Integer> BASELINES = new HashMap<>();

    private OneSittingStreak() {}

    // ---------------------------------------------------------------- pure rules

    /**
     * The baseline to keep, given the current reading: a reading below it means a new life began
     * (the travelled counter reset), so the sitting starts again from there.
     */
    static int rebaseIfReset(int baseline, int current) {
        return current < baseline ? current : baseline;
    }

    /** Carriages travelled this sitting. */
    static int streak(int baseline, int current) {
        return Math.max(0, current - baseline);
    }

    // ---------------------------------------------------------------- hooks

    /** Called by {@link AchievementEvents#notifyCartAdvance} with the same reading the carts tiers use. */
    public static void onAdvance(ServerPlayer player, int effectiveTravelled) {
        UUID uuid = player.getUUID();
        int baseline = rebaseIfReset(BASELINES.getOrDefault(uuid, effectiveTravelled), effectiveTravelled);
        BASELINES.put(uuid, baseline);
        int needed = AdvancementRequirements.intValue(ID, DEFAULT_CARRIAGES);
        if (streak(baseline, effectiveTravelled) >= needed) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, ACTION_ID);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            restart(player, "login");
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        BASELINES.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SCAN_INTERVAL_TICKS != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (PlayerActivityTracker.isAfk(player)) {
                restart(player, "afk");
            }
        }
    }

    /** Start a new sitting at the player's current reading. */
    private static void restart(ServerPlayer player, String why) {
        int current = AchievementEvents.effectiveTravelled(
            player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()));
        Integer previous = BASELINES.put(player.getUUID(), current);
        if (previous == null || previous != current) {
            LOGGER.debug("[DungeonTrain] One Sitting restarted for {} at carriage {} ({}, streak was {})",
                player.getName().getString(), current, why,
                previous == null ? 0 : streak(previous, current));
        }
    }
}
