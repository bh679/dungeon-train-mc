package games.brennan.dungeontrain.net.platform;

import net.minecraft.world.entity.player.Player;

/**
 * Loader-neutral stand-in for NeoForge's {@code IPayloadContext}, sized to
 * exactly what Dungeon Train's ~60 payload handlers use: the receiving
 * player and a way to hop onto the main thread before touching game state.
 *
 * <p>On NeoForge this is backed by a thin adapter over the real
 * {@code IPayloadContext} (see the root module's networking bridge). On
 * Fabric a future adapter will back it with the equivalent Fabric API
 * networking context.</p>
 */
public interface DtPayloadContext {

    /** The player associated with this packet (sender on C2S, receiver on S2C). */
    Player player();

    /**
     * Schedule {@code task} to run on the game thread (the same thread
     * semantics {@code IPayloadContext#enqueueWork} provides on NeoForge).
     * All of Dungeon Train's handlers wrap their logic in this call.
     */
    void enqueueWork(Runnable task);
}
