package games.brennan.dungeontrain.fabric.net;

import games.brennan.dungeontrain.net.platform.DtPayloadContext;
import net.minecraft.world.entity.player.Player;

/**
 * Fabric adapter for {@link DtPayloadContext}. Fabric API's networking receivers
 * already run on the game (main) thread, so {@link #enqueueWork} runs the task
 * immediately — the same "already on the main thread → run now" behaviour NeoForge's
 * {@code IPayloadContext.enqueueWork} provides when the handler is already on-thread.
 */
public final class FabricPayloadContext implements DtPayloadContext {

    private final Player player;

    public FabricPayloadContext(Player player) {
        this.player = player;
    }

    @Override
    public Player player() {
        return player;
    }

    @Override
    public void enqueueWork(Runnable task) {
        // Fabric receivers are dispatched on the game thread already — run inline.
        task.run();
    }
}
