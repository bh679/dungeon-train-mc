package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral form of NeoForge's mod-bus
 * {@code RegisterClientTooltipComponentFactoriesEvent}: called once on the client
 * during mod setup. Declarative — each listener records its data-type → factory
 * mapping through the {@link DtClientTooltipFactoryRegistrar} it is handed. Not
 * cancellable.
 */
@FunctionalInterface
public interface DtClientTooltipComponentFactoryRegistrationCallback {

    void registerFactories(DtClientTooltipFactoryRegistrar registrar);
}
