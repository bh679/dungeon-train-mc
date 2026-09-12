package games.brennan.dungeontrain.client.crash;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.builder.BuilderWorldCheck;
import games.brennan.dungeontrain.data.CrashRunState;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Keeps {@link CrashRunState} honest: writes it when a Dungeon Train run starts and clears it on
 * every clean way out, so that the file surviving a restart means one thing only — the last
 * session crashed mid-run.
 *
 * <p>The clean exits, all of which must be covered or the title screen would greet a player who
 * simply quit with "you crashed":</p>
 * <ul>
 *   <li>{@link ClientPlayerNetworkEvent.LoggingOut} — quit to title, a fresh world via
 *       {@code DeathScreenLayoutHandler.launchWorld}, any disconnect.</li>
 *   <li>{@link GameShuttingDownEvent} — Alt+F4 / window close straight from in-world. Fires only
 *       on a graceful close, which is exactly what makes it a valid clean signal.</li>
 *   <li>{@link #runEnded()} — called when the narrative death screen opens. The run is over, so a
 *       crash <em>on</em> the death screen must not offer to reload a world you're dead in.</li>
 * </ul>
 *
 * <p>Only singleplayer play worlds are tracked: a server world can't be reopened from the title
 * screen, and a Train Builder world has no run to lose. The world's identity is its save folder
 * name — the same {@code getWorldPath(ROOT)} expression the death screen's reboard-delete uses —
 * because that is what {@code WorldOpenFlows.openWorld} needs to bring it back.</p>
 *
 * <p>Also answers {@link #isSalvageSession()} for the HUD banner and the pause menu: true while
 * the open world is one the player reopened through the crash offer.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CrashRunTracker {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Save folder of the world the player is salvaging, or null when this isn't a salvage session. */
    @Nullable private static String salvagingLevelId = null;

    private CrashRunTracker() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        if (BuilderWorldCheck.isBuilderWorld()) return;
        String levelId = levelIdOf(server);
        if (levelId == null) return;

        // A world reopened through the offer keeps its SALVAGING record rather than being
        // re-marked ACTIVE — a second crash during salvage should offer the same one-shot again,
        // not pretend this was a fresh run.
        Optional<CrashRunState.RunState> existing = CrashRunState.read(PlayerDataPaths.root());
        boolean salvage = existing.isPresent()
                && existing.get().status() == CrashRunState.Status.SALVAGING
                && existing.get().levelId().equals(levelId);
        salvagingLevelId = salvage ? levelId : null;
        if (salvage) {
            LOGGER.info("[DungeonTrain] Crash recovery: salvage session open in '{}'.", levelId);
            return;
        }

        String worldName = server.getWorldData().getLevelName();
        CrashRunState.write(PlayerDataPaths.root(),
                new CrashRunState.RunState(levelId, worldName, System.currentTimeMillis(),
                        CrashRunState.Status.ACTIVE));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        runEnded();
    }

    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        runEnded();
    }

    /**
     * The run is over by a route we own — a clean exit, a death, or a fresh world started from the
     * salvage session. Forget it, so nothing is offered next launch.
     */
    public static void runEnded() {
        salvagingLevelId = null;
        CrashRunState.clear(PlayerDataPaths.root());
    }

    /** True while the open world is one the player came back to through the crash offer. */
    public static boolean isSalvageSession() {
        return salvagingLevelId != null && Minecraft.getInstance().level != null;
    }

    /** The save folder name of {@code server}'s world, or null if it can't be resolved. */
    @Nullable
    static String levelIdOf(MinecraftServer server) {
        try {
            return server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Crash recovery: couldn't resolve the current save folder; not tracking this run.", e);
            return null;
        }
    }
}
