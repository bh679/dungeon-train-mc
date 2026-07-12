package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral form of NeoForge's {@code ScreenEvent.Opening} (client game bus).
 * CANCELLABLE + mutable: a handler may replace the incoming screen or cancel the
 * open via {@link DtScreenOpening}. The bridge replicates NeoForge's cancellable
 * dispatch — once a handler cancels, later (non-{@code receiveCanceled}) handlers
 * are skipped.
 */
@FunctionalInterface
public interface DtScreenOpeningCallback {

    void onScreenOpening(DtScreenOpening event);
}
