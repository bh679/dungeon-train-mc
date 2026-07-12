package games.brennan.dungeontrain.platform.event;

import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;

/**
 * Loader-neutral form of NeoForge's mod-bus {@code RegisterKeyMappingsEvent}:
 * called once on the client during mod setup to register the mod's key bindings.
 * Declarative — each listener is handed a {@code registrar} sink and passes its
 * {@link KeyMapping} instance(s) to it (the same {@code event.register(KEY)} call,
 * abstracted). {@code KeyMapping} is a vanilla client type, so a Fabric bridge can
 * back the sink with {@code KeyBindingHelper.registerKeyBinding} unchanged.
 */
@FunctionalInterface
public interface DtKeyMappingRegistrationCallback {

    void registerKeyMappings(Consumer<KeyMapping> registrar);
}
