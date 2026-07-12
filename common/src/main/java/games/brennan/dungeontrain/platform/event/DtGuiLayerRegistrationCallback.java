package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral form of NeoForge's mod-bus {@code RegisterGuiLayersEvent}: called
 * once on the client during mod setup to register HUD overlay layers. Declarative —
 * each listener records its layer(s) through the {@link DtGuiLayerRegistrar} it is
 * handed (id + anchor), so a Fabric bridge can reproduce the vanilla-relative
 * ordering. Not cancellable.
 */
@FunctionalInterface
public interface DtGuiLayerRegistrationCallback {

    void registerGuiLayers(DtGuiLayerRegistrar registrar);
}
