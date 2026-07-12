package games.brennan.dungeontrain;

import com.mojang.logging.LogUtils;
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
        // game logic migrates into :common. Currently none — root-resident
        // handlers register loader-side (see class Javadoc).
    }
}
