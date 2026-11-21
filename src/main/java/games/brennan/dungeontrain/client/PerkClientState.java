package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.perks.PerkState;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * Client mirror of the local player's {@link PerkState}, fed by
 * {@link games.brennan.dungeontrain.net.PerkStatePacket} and read through
 * {@link games.brennan.dungeontrain.perks.Perks#rank}. Only the local player's state is known;
 * any other player reads as {@link PerkState#EMPTY}.
 *
 * <p>Deliberately free of client-only imports so a stray server-side class load is harmless;
 * the logout clear lives in {@link PerkClientEvents}.</p>
 */
public final class PerkClientState {

    private static volatile PerkState state = PerkState.EMPTY;
    private static volatile UUID owner;
    private static volatile int revision;

    private PerkClientState() {}

    public static void apply(UUID localPlayer, PerkState next) {
        owner = localPlayer;
        state = next == null ? PerkState.EMPTY : next;
        revision++;
    }

    public static void clear() {
        owner = null;
        state = PerkState.EMPTY;
        revision++;
    }

    /** The synced state when {@code player} is the local player it was synced for, else empty. */
    public static PerkState stateFor(Player player) {
        UUID o = owner;
        return o != null && player != null && o.equals(player.getUUID()) ? state : PerkState.EMPTY;
    }

    /** Bumped on every change so screens can cache per revision. */
    public static int revision() {
        return revision;
    }
}
