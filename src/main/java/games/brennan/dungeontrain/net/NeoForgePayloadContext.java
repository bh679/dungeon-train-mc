package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.net.platform.DtPayloadContext;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Thin adapter from NeoForge's {@code IPayloadContext} to the loader-neutral
 * {@link DtPayloadContext}, so the (mostly {@code :common}-portable) handler
 * methods never see a NeoForge type. Same thread semantics: {@link
 * #enqueueWork} delegates straight through to {@code IPayloadContext}'s
 * play-phase game-thread hop.
 */
final class NeoForgePayloadContext implements DtPayloadContext {

    private final IPayloadContext delegate;

    NeoForgePayloadContext(IPayloadContext delegate) {
        this.delegate = delegate;
    }

    @Override
    public Player player() {
        return delegate.player();
    }

    @Override
    public void enqueueWork(Runnable task) {
        delegate.enqueueWork(task);
    }
}
