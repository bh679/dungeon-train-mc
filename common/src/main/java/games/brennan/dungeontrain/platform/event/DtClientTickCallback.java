package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral form of NeoForge's {@code ClientTickEvent} (both {@code Pre} and
 * {@code Post}). Fires once per client tick on the render/client thread. NeoForge's
 * {@code Pre} is cancellable, but no Dungeon Train handler cancels it, so this is a
 * pure {@code void} passthrough. Every DT tick handler reads {@code Minecraft.getInstance()}
 * directly and never touches the event object, so the callback takes no arguments.
 *
 * <p>Two {@code DtEvents} fields use this interface — one per phase — so a Fabric
 * bridge can wire {@code Pre} to {@code ClientTickEvents.START_CLIENT_TICK} and
 * {@code Post} to {@code END_CLIENT_TICK} with no translation.</p>
 */
@FunctionalInterface
public interface DtClientTickCallback {

    void onClientTick();
}
