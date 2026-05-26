package games.brennan.dungeontrain.difficulty;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory registry of difficulty tiers loaded from
 * {@code data/dungeontrain/difficulty/tiers.json} bundled in the mod jar.
 *
 * <p>Loaded once at {@link ServerStartingEvent}; cleared on
 * {@link ServerStoppedEvent}. Single-file load (no scanner) since the tier
 * curve is one ordered list. Datapack reload is not currently wired — operators
 * need a server restart to pick up edits.</p>
 *
 * <p>Tier lookup clamps the requested index to {@code [0, size-1]} so callers
 * can hand in any non-negative integer without bounds-checking. An empty
 * registry returns {@code null} from {@link #tierFor(int)}, which the applier
 * treats as a no-op.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DifficultyTierRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RESOURCE_PATH = "/data/" + DungeonTrain.MOD_ID + "/difficulty/tiers.json";

    private static volatile List<DifficultyTier> TIERS = List.of();

    private DifficultyTierRegistry() {}

    /** Reload from the bundled resource. Safe to call outside event handlers. */
    public static synchronized void reload() {
        try (InputStream in = DifficultyTierRegistry.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                LOGGER.warn("[DungeonTrain] Difficulty: resource not found at {} — difficulty disabled until file is bundled.",
                        RESOURCE_PATH);
                TIERS = List.of();
                return;
            }
            List<DifficultyTier> parsed = DifficultyTierCodec.parse(in);
            TIERS = Collections.unmodifiableList(new ArrayList<>(parsed));
            LOGGER.info("[DungeonTrain] Difficulty registry loaded — {} tiers from {}", TIERS.size(), RESOURCE_PATH);
        } catch (DifficultyTierCodec.DifficultyParseException e) {
            LOGGER.error("[DungeonTrain] Difficulty: failed to parse {} — {}", RESOURCE_PATH, e.getMessage());
            TIERS = List.of();
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Difficulty: unexpected error reading {} — {}", RESOURCE_PATH, e.toString());
            TIERS = List.of();
        }
    }

    /**
     * Return the tier at {@code tierIndex}, clamping into {@code [0, size-1]}.
     * Returns {@code null} when the registry is empty.
     */
    public static DifficultyTier tierFor(int tierIndex) {
        List<DifficultyTier> snapshot = TIERS;
        if (snapshot.isEmpty()) return null;
        int clamped = Math.max(0, Math.min(snapshot.size() - 1, tierIndex));
        return snapshot.get(clamped);
    }

    public static int size() {
        return TIERS.size();
    }

    public static List<DifficultyTier> all() {
        return TIERS;
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TIERS = List.of();
    }
}
