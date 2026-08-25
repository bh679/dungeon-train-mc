package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-player mirror of the relay's kid-safe-tester roster ({@code kidtesters.js}) — the
 * short list of players an operator trusts to say "not for a child" and have it acted on. Seeded by
 * {@link games.brennan.dungeontrain.net.relay.KidTesterClient} when a player's network consent
 * arrives, and read by the {@code BookKidRejectPacket} handler to decide whether that player's
 * "Remove for kids" really may be sent on.
 *
 * <p><b>Fail-closed</b>, exactly like {@link NetworkConsentMirror} whose shape this copies: an unknown
 * player is not a tester. A relay that is unreachable, slow, or older than this jar therefore grants
 * nobody anything, which is the only safe way for this particular signal to be missing.</p>
 *
 * <p>This is the SECOND of three checks, and none of them is the client's opinion. The client draws
 * the button because it was told to; this mirror decides whether the packet is honoured; and the
 * relay re-asks its own roster before writing. A modified client that draws the button anyway gets a
 * packet dropped here, and — were this mirror somehow wrong — a 403 there.</p>
 *
 * <p>Cleared per-player on logout ({@link #forget}, from {@link PlayerJoinEvents}) and wholesale on
 * server stop, so a mark revoked on the relay is picked up on the player's next login rather than
 * outliving it for the session's lifetime.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class KidTesterMirror {

    /** Per-player tester flag, seeded from the relay. Absent = not-yet-known = not a tester. */
    private static final Map<UUID, Boolean> TESTERS = new ConcurrentHashMap<>();

    private KidTesterMirror() {}

    /** Seed / update the mirror from the relay's answer. */
    public static void set(UUID playerId, boolean tester) {
        if (playerId == null) return;
        TESTERS.put(playerId, tester);
    }

    /**
     * True only when the relay has affirmatively named this player a kid-safe tester. Fail-closed:
     * {@code false} for an unknown player or a {@code null} argument.
     */
    public static boolean isTester(ServerPlayer player) {
        return player != null && isTester(player.getUUID());
    }

    /** Uuid-keyed form of {@link #isTester(ServerPlayer)}. */
    public static boolean isTester(UUID playerId) {
        if (playerId == null) return false;
        return TESTERS.getOrDefault(playerId, false);
    }

    /** Drop a player's mirrored mark when they leave; called from {@link PlayerJoinEvents} logout. */
    public static void forget(UUID playerId) {
        if (playerId != null) {
            TESTERS.remove(playerId);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // Nothing leaks into the next world: every player is re-checked against the relay on login.
        TESTERS.clear();
    }
}
