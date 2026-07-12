package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral form of the reload-listener registration events — NeoForge's
 * server-data {@code AddReloadListenerEvent} and client-resource
 * {@code RegisterClientReloadListenersEvent}. Declarative: each listener is handed
 * a {@link DtReloadListenerRegistrar} sink and registers its
 * {@code PreparableReloadListener}(s) on it (the same {@code addListener} /
 * {@code registerReloadListener} call, abstracted). Not cancellable; independent
 * (order irrelevant). Server-data listeners register on
 * {@code DtEvents.SERVER_RELOAD_LISTENER_REGISTRATION}; client-resource listeners on
 * {@code DtEvents.CLIENT_RELOAD_LISTENER_REGISTRATION}.
 */
@FunctionalInterface
public interface DtReloadListenerRegistrationCallback {

    void registerReloadListeners(DtReloadListenerRegistrar registrar);
}
