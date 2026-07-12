package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral form of NeoForge's {@code InputEvent.MouseScrollingEvent} (client
 * game bus). Fires on a mouse-wheel scroll <b>before</b> screen routing; DT handlers
 * guard {@code Minecraft.getInstance().screen} themselves (unchanged). CANCELLABLE:
 * the callback returns {@code true} to swallow the scroll (the former
 * {@code event.setCanceled(true)}); {@code NeoForgeClientInputBridge} stops on the
 * first {@code true} and cancels. Params mirror the scroll deltas the event exposes.
 */
@FunctionalInterface
public interface DtMouseScrollCallback {

    /**
     * @param deltaX horizontal scroll delta ({@code event.getScrollDeltaX()})
     * @param deltaY vertical scroll delta ({@code event.getScrollDeltaY()})
     * @return {@code true} to cancel the scroll (suppress vanilla handling)
     */
    boolean onMouseScroll(double deltaX, double deltaY);
}
