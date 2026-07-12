package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral form of NeoForge's {@code ClientPlayerNetworkEvent.LoggingIn} /
 * {@code .LoggingOut}. Fires on the client thread as the local player connects to
 * (or disconnects from) a server — integrated or dedicated. Not cancellable; every
 * DT handler ignores the event object (they reset client-side state or read
 * {@code Minecraft.getInstance()}), so the callback takes no arguments.
 *
 * <p>Two {@code DtEvents} fields use this interface — one per phase — so a Fabric
 * bridge can wire {@code LoggingIn} to {@code ClientPlayConnectionEvents.JOIN} and
 * {@code LoggingOut} to {@code ClientPlayConnectionEvents.DISCONNECT}.</p>
 */
@FunctionalInterface
public interface DtClientLoggingCallback {

    void onClientLogging();
}
