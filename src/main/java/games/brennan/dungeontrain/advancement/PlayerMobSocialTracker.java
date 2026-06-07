package games.brennan.dungeontrain.advancement;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side, in-memory tracker for two-way item exchanges between a player
 * and individual PlayerMobs. Backs the <em>A Silent Friend</em> and
 * <em>Friends</em> advancements.
 *
 * <p>Two halves are recorded per (player, mob) pair, in either order:</p>
 * <ul>
 *   <li><b>received</b> — a PlayerMob gifted the player an item
 *       ({@code PlayerMobEntity.giveItemTo}, captured by
 *       {@code mixin.PlayerMobGiveItemMixin}). Each such gift also unlocks
 *       <em>A Silent Friend</em>.</li>
 *   <li><b>gave</b> — a PlayerMob picked up an item the player dropped
 *       ({@code PlayerMobEntity.tryPickUpFloorItem} with the item's thrower
 *       resolving to the player, captured by
 *       {@code mixin.PlayerMobPickupMixin}).</li>
 * </ul>
 *
 * <p>When both halves exist for the same pair, <em>Friends</em> fires. State
 * is intentionally transient (cleared on logout via {@link #forget} and on
 * server restart) — the advancements themselves persist through the existing
 * {@code GlobalAchievementStore} sidecar once granted, so the pairing state
 * only needs to live long enough to observe both halves within a session.</p>
 *
 * <p>All mutations run on the server thread (mob AI / pickup / interaction
 * ticks); {@code synchronized} is a cheap belt-and-braces guard.</p>
 */
public final class PlayerMobSocialTracker {

    /** playerUuid → mob UUIDs the player has GIVEN an item to. */
    private static final Map<UUID, Set<UUID>> GAVE_TO = new HashMap<>();

    /** playerUuid → mob UUIDs that have GIVEN the player an item. */
    private static final Map<UUID, Set<UUID>> RECEIVED_FROM = new HashMap<>();

    private PlayerMobSocialTracker() {}

    /**
     * A PlayerMob gifted {@code player} an item. Always unlocks
     * <em>A Silent Friend</em>; unlocks <em>Friends</em> if the player has
     * already given this same mob an item.
     */
    public static synchronized void recordMobGift(ServerPlayer player, UUID mobUuid) {
        UUID playerUuid = player.getUUID();
        RECEIVED_FROM.computeIfAbsent(playerUuid, k -> new HashSet<>()).add(mobUuid);
        ModAdvancementTriggers.RECEIVED_PLAYERMOB_GIFT.get().trigger(player);
        if (GAVE_TO.getOrDefault(playerUuid, Set.of()).contains(mobUuid)) {
            ModAdvancementTriggers.BEFRIENDED_PLAYERMOB.get().trigger(player);
        }
    }

    /**
     * {@code player} gave a PlayerMob an item (it picked up the player's
     * drop). Unlocks <em>Friends</em> if the mob has already gifted the
     * player.
     */
    public static synchronized void recordPlayerGift(ServerPlayer player, UUID mobUuid) {
        UUID playerUuid = player.getUUID();
        GAVE_TO.computeIfAbsent(playerUuid, k -> new HashSet<>()).add(mobUuid);
        if (RECEIVED_FROM.getOrDefault(playerUuid, Set.of()).contains(mobUuid)) {
            ModAdvancementTriggers.BEFRIENDED_PLAYERMOB.get().trigger(player);
        }
    }

    /** Drop a player's transient pairing state (called on logout). */
    public static synchronized void forget(UUID playerUuid) {
        GAVE_TO.remove(playerUuid);
        RECEIVED_FROM.remove(playerUuid);
    }
}
