package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral form of NeoForge's {@code InputEvent.InteractionKeyMappingTriggered}
 * (client game bus). Fires when a use/attack/pick key-mapping is triggered.
 * CANCELLABLE + mutable: DT's menu handlers cancel the interaction and suppress the
 * swing via {@link DtInteractionInput}; the two hotkey handlers only observe. The
 * bridge replicates NeoForge's cancellable dispatch — once a handler cancels, later
 * (non-{@code receiveCanceled}) handlers are skipped.
 */
@FunctionalInterface
public interface DtInteractionInputCallback {

    void onInteraction(DtInteractionInput input);
}
