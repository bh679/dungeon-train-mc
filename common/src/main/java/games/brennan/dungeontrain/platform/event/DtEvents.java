package games.brennan.dungeontrain.platform.event;

/**
 * Dungeon Train's loader-neutral event surface: one {@link DtEvent} static field
 * per converted NeoForge event TYPE. Game logic (moving to {@code :common} over
 * the Fabric port) registers listeners here instead of touching NeoForge's bus;
 * a loader-specific bridge in the root module ({@code platform.neoforge.*Bridge})
 * subscribes the real NeoForge event once and fires the matching field.
 *
 * <p>Each field's Javadoc records the EXACT semantics it must preserve, matching
 * current NeoForge behavior verbatim: <b>when</b> it fires, on <b>what thread</b>,
 * whether it is <b>cancellable</b>, and whether its parameters are <b>mutable</b>.
 * Bridges are pure adapters — they add no logic and must keep those semantics
 * identical on every loader.</p>
 *
 * <p>This class is populated one category at a time (Stage 2a = server-side
 * categories, Stage 2b = client-side). Fields are added alongside their callback
 * interface, their root bridge, and the converted handler registration.</p>
 */
public final class DtEvents {

    private DtEvents() {}

    // ---- Server-side categories (Stage 2a) --------------------------------
    // Fields are added here per category, each with its exact-semantics Javadoc.

}
