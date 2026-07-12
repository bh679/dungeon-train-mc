package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral form of NeoForge's {@code ScreenEvent.Init.Post} (client game bus).
 * Fires after a screen's widgets are laid out. Not cancellable; handlers add extra
 * widgets to the screen via {@link DtScreenInit}.
 */
@FunctionalInterface
public interface DtScreenInitCallback {

    void onScreenInit(DtScreenInit event);
}
