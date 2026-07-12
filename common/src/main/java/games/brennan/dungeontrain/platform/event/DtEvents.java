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

    /**
     * Command registration — NeoForge {@code RegisterCommandsEvent}. Fires once
     * per server start (integrated and dedicated) on the server thread, while
     * the command tree is built. Not cancellable. Listeners mutate the passed
     * dispatcher by registering command nodes. Bridge invokes every listener in
     * registration order.
     */
    public static final DtEvent<DtCommandRegistrationCallback> COMMAND_REGISTRATION =
        new DtEvent<>();

    /**
     * Server chat — NeoForge {@code ServerChatEvent}. Fires on the server thread
     * when a player sends a chat line, before broadcast. The NeoForge event is
     * cancellable with a mutable message, but DT's only listener is observe-only,
     * so this is a {@code void} exact-passthrough callback (see
     * {@link DtServerChatCallback}). Bridge invokes every listener in
     * registration order; it does not cancel or edit the message.
     */
    public static final DtEvent<DtServerChatCallback> SERVER_CHAT =
        new DtEvent<>();

    /**
     * Command execution — NeoForge {@code CommandEvent}. Fires on the server
     * thread after a command is parsed, before it runs. <b>Cancellable:</b>
     * listeners return {@code true} to cancel (see {@link DtCommandCallback});
     * the bridge stops on the first {@code true} and cancels the command. The
     * parse results are read-only here (DT does not rewrite them).
     */
    public static final DtEvent<DtCommandCallback> COMMAND_EXEC =
        new DtEvent<>();

}
