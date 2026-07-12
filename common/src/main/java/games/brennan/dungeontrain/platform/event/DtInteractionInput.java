package games.brennan.dungeontrain.platform.event;

/**
 * Mutable carrier for {@link DtInteractionInputCallback}, mirroring the two
 * mutations DT performs on NeoForge's {@code InputEvent.InteractionKeyMappingTriggered}:
 * cancel the interaction and suppress the arm swing. The bridge backs it with the
 * live event so both writes take effect exactly as the former
 * {@code event.setCanceled(true); event.setSwingHand(false);} did.
 */
public interface DtInteractionInput {

    /** Cancel the interaction (former {@code event.setCanceled}). */
    void setCanceled(boolean canceled);

    /** Suppress / allow the arm swing (former {@code event.setSwingHand}). */
    void setSwingHand(boolean swingHand);
}
