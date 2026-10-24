package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.AllAtOnceAdvancement;
import games.brennan.dungeontrain.advancement.CloseCallRules;
import games.brennan.dungeontrain.train.ChallengeMelonSpawner;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The train hands out the Live Eye itself when a player is close to a rare challenge
 * ({@link LiveCloseCallEvents}) and nobody anywhere is live, so there is no streamer to gift it. Silent:
 * the advancements are hidden, so nothing is said.
 *
 * <ul>
 *   <li><b>The Challenge</b> forbids opening chests, so the eye is <b>owed</b>: the next time the player
 *       stands in a room next to a flatbed, one Live Eye drops there, the way the challenge melon does
 *       ({@link ChallengeMelonSpawner#dropOnAdjacentFlatbed}). Once a life.</li>
 *   <li><b>The others</b> turn on a <b>chest boost</b>: each train container the player opens or breaks
 *       that holds no Live Eye rolls {@link #BOOST_CHANCE} once to gain one
 *       ({@link #maybeAddEye}).</li>
 * </ul>
 * Neither runs on a timer: each lasts until its advancement is earned or the life ends (respawn or
 * logout clears it). The eye is only handed out when {@link LiveEyeLootResolver#mayGrant}, so the one-at-a-time
 * rule, the cooldown, Kid mode and bans all still hold; when it would come out as a TV nothing is given.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveEyeTrainHandout {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Chance a never-rolled container gains a Live Eye while the chest boost is on. */
    public static final float BOOST_CHANCE = 0.25f;

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    /** One player's life: the Challenge eye owed, the advancements boosting chests, the containers rolled. */
    private static final class State {
        ResourceLocation owedFor;
        final Set<ResourceLocation> boostFor = new HashSet<>();
        final Set<Long> rolled = new HashSet<>();
    }

    /** Which half each close-call target takes. Pure for the tests. */
    record Split(boolean owesEye, List<ResourceLocation> boost) {}

    private LiveEyeTrainHandout() {}

    static Split split(List<CloseCallRules.Target> close) {
        boolean owes = close.stream().anyMatch(t -> t == CloseCallRules.THE_CHALLENGE);
        List<ResourceLocation> boost = close.stream()
            .filter(t -> t != CloseCallRules.THE_CHALLENGE).map(CloseCallRules.Target::id).toList();
        return new Split(owes, boost);
    }

    /** Whether a boosted player's sighting of a container rolls for an eye. Pure for the tests. */
    static boolean shouldRoll(boolean boostOn, boolean alreadyRolled, boolean holdsAnyEye, boolean hasEmptySlot) {
        return boostOn && !alreadyRolled && !holdsAnyEye && hasEmptySlot;
    }

    /** The first empty slot of {@code container}, or −1. */
    static int firstEmptySlot(Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    /** A close call on the train route (nobody live) for {@code close}. Server thread. */
    public static void onCloseCall(ServerPlayer player, List<CloseCallRules.Target> close) {
        Split split = split(close);
        State state = STATES.computeIfAbsent(player.getUUID(), u -> new State());
        String name = player.getGameProfile().getName();
        if (split.owesEye() && state.owedFor == null) {
            state.owedFor = CloseCallRules.THE_CHALLENGE.id();
            LOGGER.info("[DungeonTrain] Live Eye owed to {} on the next flatbed (The Challenge, nobody live)", name);
        }
        if (!split.boost().isEmpty() && state.boostFor.addAll(split.boost())) {
            LOGGER.info("[DungeonTrain] Live Eye chest boost on for {} ({})", name, split.boost());
        }
    }

    /** Every boarding scan: drop an owed Challenge eye on the flatbed next to the player. Server thread. */
    public static void tick(ServerLevel level, List<Trains.Carriage> carriages, ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        if (state == null || state.owedFor == null) return;
        if (!player.isAlive()) return;
        if (isEarned(player, state.owedFor) || AllAtOnceAdvancement.anyRuleBroken(player)
            || LiveGiftServer.refusalFor(player) != null) {
            state.owedFor = null; // nothing to help with any more, or they can't take one
            return;
        }
        if (!LiveEyeLootResolver.mayGrant(player)) return; // cooldown: try a later flatbed
        ChallengeMelonSpawner.Dropped dropped = ChallengeMelonSpawner.dropOnAdjacentFlatbed(
            level, carriages, player, (flatbed, size) -> true, LiveEyeLootResolver.unclaimedEye());
        if (dropped == null) return;
        LiveEyeLootResolver.markSeen(player);
        state.owedFor = null;
        LOGGER.info("[DungeonTrain] Live Eye dropped for {} on flatbed before group {} at {}",
            player.getGameProfile().getName(), dropped.flatbed(), dropped.pos());
    }

    /**
     * {@code player} sees the train container at {@code pos}: while their chest boost is on, a container
     * they have not rolled before may gain a Live Eye. Returns true when a slot changed. Server thread.
     */
    public static boolean maybeAddEye(ServerPlayer player, Container container, BlockPos pos) {
        State state = STATES.get(player.getUUID());
        if (state == null || state.boostFor.isEmpty()) return false;
        state.boostFor.removeIf(id -> isEarned(player, id));
        int slot = firstEmptySlot(container);
        boolean roll = shouldRoll(!state.boostFor.isEmpty(), state.rolled.contains(pos.asLong()),
            LiveEyeLootResolver.holdsAnyLiveEye(container), slot >= 0);
        if (!roll) return false;
        state.rolled.add(pos.asLong());
        if (LiveGiftServer.refusalFor(player) != null) return false;
        if (!LiveEyeLootResolver.mayGrant(player)) return false; // would be a TV: give nothing
        if (player.getRandom().nextFloat() >= BOOST_CHANCE) return false;
        container.setItem(slot, LiveEyeLootResolver.unclaimedEye());
        LiveEyeLootResolver.markSeen(player);
        LOGGER.info("[DungeonTrain] Live Eye chest boost added an eye for {} at {}", player.getGameProfile().getName(), pos);
        return true;
    }

    private static boolean isEarned(ServerPlayer player, ResourceLocation id) {
        AdvancementHolder holder = player.server.getAdvancements().get(id);
        return holder == null || player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!event.isEndConquered()) STATES.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }
}
