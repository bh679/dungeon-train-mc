package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral form of NeoForge's {@code RenderHandEvent} (client game bus /
 * render thread). Fires before the first-person held item renders. CANCELLABLE:
 * the callback returns {@code true} to suppress the hand render (the former
 * {@code event.setCanceled(true)}); the bridge stops on the first {@code true} and
 * cancels. DT's sole handler ignores the event object (it decides purely from the
 * cinematic state), so no arguments are needed.
 */
@FunctionalInterface
public interface DtRenderHandCallback {

    boolean onRenderHand();
}
