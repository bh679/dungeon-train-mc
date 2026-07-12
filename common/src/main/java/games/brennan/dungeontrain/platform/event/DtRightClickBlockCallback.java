package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral form of NeoForge's {@code PlayerInteractEvent.RightClickBlock}.
 * CANCELLABLE via the mutable {@link DtRightClickBlock} carrier. DT registers
 * handlers across three priority tiers — HIGHEST ({@code CommandMenuInputHandler},
 * cancels while a menu is open), HIGH ({@code PrefabUseHandler}, consumes a prefab
 * placement) and NORMAL (lectern / narrative / achievement / variant handlers).
 * The bridge fires each tier under a matching {@code @SubscribeEvent} priority and
 * skips remaining handlers once the interaction is canceled, so a higher-tier
 * cancel suppresses the rest exactly as the former per-handler {@code @SubscribeEvent}s did.
 */
@FunctionalInterface
public interface DtRightClickBlockCallback {

    void onRightClickBlock(DtRightClickBlock event);
}
