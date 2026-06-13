package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side, in-memory tracker for item exchanges between a player and
 * individual PlayerMobs. Backs the <em>Simon's Desperation</em> and
 * <em>Come On, Grab Your Friends</em> advancements.
 *
 * <p>Two halves are recorded per (player, mob) pair, in either order:</p>
 * <ul>
 *   <li><b>gave</b> — a PlayerMob picked up an item the player dropped
 *       ({@code creditGift}, surfaced via {@code PlayerMobSocialHooks.onPlayerGift}
 *       and forwarded by {@code compat.PlayerMobSocialBridge}).</li>
 *   <li><b>received</b> — a PlayerMob gifted the player an item
 *       ({@code tossGift}, surfaced via {@code PlayerMobSocialHooks.onMobGift}
 *       and forwarded by {@code compat.PlayerMobSocialBridge}).</li>
 * </ul>
 *
 * <p>Outcomes:</p>
 * <ul>
 *   <li>You give a mob an item and it has given you nothing back →
 *       <em>Simon's Desperation</em> (unrequited giving).</li>
 *   <li>Both halves exist for the same pair (either order) →
 *       <em>Come On, Grab Your Friends</em> (mutual exchange).</li>
 * </ul>
 *
 * <p>State is transient (cleared on logout via {@link #forget} and on server
 * restart); granted advancements persist through the existing
 * {@code GlobalAchievementStore} sidecar, so the pairing state only needs to
 * live long enough to observe both halves within a session. All mutations run
 * on the server thread; {@code synchronized} is a cheap guard.</p>
 */
public final class PlayerMobSocialTracker {

    /** playerUuid → mob UUIDs the player has GIVEN an item to. */
    private static final Map<UUID, Set<UUID>> GAVE_TO = new HashMap<>();

    /** playerUuid → mob UUIDs that have GIVEN the player an item. */
    private static final Map<UUID, Set<UUID>> RECEIVED_FROM = new HashMap<>();

    private PlayerMobSocialTracker() {}

    /**
     * A PlayerMob gifted {@code player} an item. Unlocks <em>Come On, Grab
     * Your Friends</em> if the player has already given this same mob an item
     * (mutual exchange). A mob-initiated gift on its own grants nothing —
     * Simon's Desperation is specifically the <em>unrequited</em> case where
     * the player gives and gets nothing back.
     */
    public static synchronized void recordMobGift(ServerPlayer player, UUID mobUuid) {
        UUID playerUuid = player.getUUID();
        RECEIVED_FROM.computeIfAbsent(playerUuid, k -> new HashSet<>()).add(mobUuid);
        if (GAVE_TO.getOrDefault(playerUuid, Set.of()).contains(mobUuid)) {
            ModAdvancementTriggers.BEFRIENDED_PLAYERMOB.get().trigger(player);
            // Per-run death-screen "befriended" tally (distinct mobs, deduped by the Set).
            player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).recordBefriended(mobUuid);
        }
    }

    /**
     * {@code player} gave a PlayerMob an item (it picked up the player's
     * drop). If the mob has already gifted the player, the exchange is mutual
     * → <em>Come On, Grab Your Friends</em>. Otherwise the gift is unanswered
     * → <em>Simon's Desperation</em>.
     */
    public static synchronized void recordPlayerGift(ServerPlayer player, UUID mobUuid) {
        UUID playerUuid = player.getUUID();
        GAVE_TO.computeIfAbsent(playerUuid, k -> new HashSet<>()).add(mobUuid);
        if (RECEIVED_FROM.getOrDefault(playerUuid, Set.of()).contains(mobUuid)) {
            ModAdvancementTriggers.BEFRIENDED_PLAYERMOB.get().trigger(player);
            // Per-run death-screen "befriended" tally (distinct mobs, deduped by the Set).
            player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).recordBefriended(mobUuid);
        } else {
            ModAdvancementTriggers.GAVE_PLAYERMOB_UNREQUITED.get().trigger(player);
        }
    }

    /** Drop a player's transient pairing state (called on logout). */
    public static synchronized void forget(UUID playerUuid) {
        GAVE_TO.remove(playerUuid);
        RECEIVED_FROM.remove(playerUuid);
    }
}
