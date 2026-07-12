package games.brennan.dungeontrain.platform.neoforge;

/**
 * Root-side registrar that wires Dungeon Train's converted, still-root-resident
 * server-side handlers to their {@code DtEvents} fields. Invoked once from the
 * {@code DungeonTrain} constructor, immediately after {@code DungeonTrainCommon.init()}
 * and before any {@code NeoForge*Bridge} could fire an event.
 *
 * <p>This exists only because Stage 2a deliberately leaves the handler classes
 * in the root module (files are not moved to {@code :common} this run), so
 * {@code :common}'s {@code DungeonTrainCommon.init()} cannot reference them. As
 * each handler class migrates into {@code :common} in a later fixpoint, its
 * registration line moves from here into {@code DungeonTrainCommon.init()} and
 * this class shrinks toward empty.</p>
 *
 * <p><b>Ordering contract:</b> registrations here run in a deterministic order.
 * NeoForge fired same-priority same-bus handlers in annotation-scan order (not
 * something DT logic relied on across independent handlers); where DT DID rely
 * on {@code EventPriority}, handlers are registered high-priority-first so the
 * invocation order the bridge produces matches the old bus order. Each category
 * block below documents its ordering when it matters.</p>
 */
public final class NeoForgeServerEvents {

    private NeoForgeServerEvents() {}

    /** Register every converted server-side handler with its {@code DtEvents} field. */
    public static void register() {
        // Category registrations are added here, one block per converted category.

        // --- Command registration (RegisterCommandsEvent) --------------------
        // Single handler; order irrelevant.
        games.brennan.dungeontrain.platform.event.DtEvents.COMMAND_REGISTRATION
            .register(games.brennan.dungeontrain.event.CommandEvents::onRegisterCommands);

        // --- Server chat (ServerChatEvent) -----------------------------------
        // Single (observe-only) handler; order irrelevant.
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_CHAT
            .register(games.brennan.dungeontrain.event.MentionPresenceEvents::onServerChat);

        // --- Command execution (CommandEvent, cancellable) -------------------
        // Single handler; the Free Play gate cancels a tainting command.
        games.brennan.dungeontrain.platform.event.DtEvents.COMMAND_EXEC
            .register(games.brennan.dungeontrain.event.CheatDetectionEvents::onCommand);

        // --- Advancement earn (AdvancementEarnEvent) -------------------------
        // Single handler; order irrelevant.
        games.brennan.dungeontrain.platform.event.DtEvents.ADVANCEMENT_EARN
            .register(games.brennan.dungeontrain.event.AchievementEvents::onAdvancementEarn);
    }
}
