package games.brennan.dungeontrain;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.platform.event.DtEvents;
import org.slf4j.Logger;

/**
 * Loader-neutral initialisation entry point for Dungeon Train, called once from
 * the loader's mod-construct hook — on NeoForge, from the {@code DungeonTrain}
 * constructor (see {@code DungeonTrain}) BEFORE any event bridge could fire.
 *
 * <p>{@link #init()} is the seam where converted game logic wires itself to
 * {@code DtEvents} independent of the loader. As game-logic handlers migrate
 * into {@code :common} (a later fixpoint of the Fabric port), their
 * {@code DtEvents.register(...)} calls land here.</p>
 *
 * <p><b>Stage 2a note:</b> the converted handler classes still physically live
 * in the root ({@code src/main/java}) this run — files are intentionally NOT
 * moved to {@code :common} yet — so {@code :common} cannot reference them. Their
 * registration therefore runs loader-side, in
 * {@code games.brennan.dungeontrain.platform.neoforge.NeoForgeServerEvents},
 * which the root {@code DungeonTrain} constructor invokes right after this. Once
 * a handler class moves to {@code :common}, its registration moves here.</p>
 */
public final class DungeonTrainCommon {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile boolean initialised;
    private static volatile boolean clientInitialised;

    private DungeonTrainCommon() {}

    /**
     * Idempotent common init. Safe to call more than once (the loader only calls
     * it once today); guarded so a stray second call cannot double-register.
     */
    public static synchronized void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        LOGGER.info("Dungeon Train common init");
        // Registrations for common-resident converted handlers land here as
        // game logic migrates into :common. Root-resident handlers still register
        // loader-side (see class Javadoc + NeoForgeServerEvents).
    }

    /**
     * Idempotent client-only common init — the loader-neutral home for {@code DtEvents}
     * registrations of client-side ({@code SCREEN_*}, {@code GUI_LAYER_*}, HUD, input)
     * handlers that have migrated into {@code :common}. Called only from a client-gated
     * loader path: on NeoForge, from the {@code DungeonTrain} constructor's
     * {@code isClient()} block (right where {@code NeoForgeClientEvents.register()} runs);
     * a future Fabric client entrypoint calls it too. Never runs on a dedicated server,
     * so client-only Minecraft types referenced by these handlers never classload there.
     */
    public static synchronized void initClient() {
        if (clientInitialised) {
            return;
        }
        clientInitialised = true;
        LOGGER.info("Dungeon Train common client init");

        // Dev quick-world button on the title screen (screen-init + render-pre).
        DtEvents.SCREEN_INIT_POST
            .register(games.brennan.dungeontrain.client.DevQuickWorldHandler::onScreenInitPost);
        DtEvents.SCREEN_RENDER_PRE
            .register(games.brennan.dungeontrain.client.DevQuickWorldHandler::onScreenRenderPre);

        // Pending starting-dimension sync gate (render-pre).
        DtEvents.SCREEN_RENDER_PRE
            .register(games.brennan.dungeontrain.client.PendingStartingDimensionSyncHandler::onRenderPre);

        // NOTE: VariantHoverHudOverlay's GUI_LAYER_REGISTRATION stays in
        // NeoForgeClientEvents even though the class now lives in :common — GUI
        // layer registration order is render order, and migrating it here (which
        // runs before NeoForgeClientEvents.register()) would reorder it relative
        // to the other HUD layers. The FQN method-ref resolves to :common fine, so
        // NeoForge behavior is unchanged; a Fabric client registrar will wire it.
    }
}
