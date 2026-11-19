package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.AllAtOnceAdvancement;
import games.brennan.dungeontrain.advancement.CloseCallRules;
import games.brennan.dungeontrain.train.ChallengeMelonSpawner;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The train hands out the Live Eye itself when a player is close to The Challenge
 * ({@link LiveCloseCallEvents}) and nobody anywhere is live, so there is no streamer to gift it. Silent:
 * the advancement is hidden, so nothing is said.
 *
 * <p>The Challenge forbids opening chests, so the eye is <b>owed</b>: the next time the player stands in a
 * room next to a flatbed that is not a melon one ({@link #isEyeFlatbed}), one Live Eye drops there, the way
 * the challenge melon does ({@link ChallengeMelonSpawner#dropOnAdjacentFlatbed}). Once a life. It is not
 * dropped once The Challenge is done or a rule broke, and only when {@link LiveEyeLootResolver#mayGrant}
 * and {@link LiveGiftServer#refusalFor} allow one. Respawn or logout clears what is owed.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveEyeTrainHandout {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Players the train owes a Live Eye this life. */
    private static final Set<UUID> OWED = ConcurrentHashMap.newKeySet();

    private LiveEyeTrainHandout() {}

    /** Whether a close call on {@code target} earns a train Live Eye: The Challenge only. Pure for the tests. */
    static boolean owesEye(CloseCallRules.Target target) {
        return target == CloseCallRules.THE_CHALLENGE;
    }

    /** Any flatbed but a melon one: the eye and the challenge melon never share a flatbed. Pure for the tests. */
    static boolean isEyeFlatbed(int flatbed, int groupSize) {
        return !ChallengeMelonSpawner.isMelonFlatbed(flatbed, groupSize);
    }

    /** A close call on The Challenge with nobody live. Server thread. */
    public static void onCloseCall(ServerPlayer player) {
        if (OWED.add(player.getUUID())) {
            LOGGER.info("[DungeonTrain] Live Eye owed to {} on the next flatbed (The Challenge, nobody live)",
                player.getGameProfile().getName());
        }
    }

    /** Every boarding scan: drop an owed eye on the flatbed next to the player. Server thread. */
    public static void tick(ServerLevel level, List<Trains.Carriage> carriages, ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!OWED.contains(uuid) || !player.isAlive()) return;
        if (isEarned(player) || AllAtOnceAdvancement.anyRuleBroken(player) || LiveGiftServer.refusalFor(player) != null) {
            OWED.remove(uuid); // nothing to help with any more, or they can't take one
            return;
        }
        if (!LiveEyeLootResolver.mayGrant(player)) return; // cooldown: try a later flatbed
        ChallengeMelonSpawner.Dropped dropped = ChallengeMelonSpawner.dropOnAdjacentFlatbed(
            level, carriages, player, LiveEyeTrainHandout::isEyeFlatbed, LiveEyeLootResolver.unclaimedEye());
        if (dropped == null) return;
        LiveEyeLootResolver.markSeen(player);
        OWED.remove(uuid);
        LOGGER.info("[DungeonTrain] Live Eye dropped for {} on flatbed before group {} at {}",
            player.getGameProfile().getName(), dropped.flatbed(), dropped.pos());
    }

    private static boolean isEarned(ServerPlayer player) {
        AdvancementHolder holder = player.server.getAdvancements().get(CloseCallRules.THE_CHALLENGE.id());
        return holder == null || player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!event.isEndConquered()) OWED.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        OWED.remove(event.getEntity().getUUID());
    }
}
