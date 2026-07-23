package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Dungeon Train expects AdventureItemStats (AIS) to roll item stats with its
 * <b>default</b> configuration — DT's difficulty and loot balance are tuned
 * against it. A world running with a modified {@code adventureitemstats.properties}
 * is not playing the game DT balanced, so the whole server session runs in
 * <b>Free Play</b> (see {@link RunIntegrity}): stats and advancements don't
 * persist to the cross-world profile while the deviation exists.
 *
 * <p><b>Session-only:</b> unlike the permanent {@code RUN_CHEATED} attachment,
 * this taint lives only for the server session. The config is re-checked at every
 * server start ({@link ServerAboutToStartEvent} — covers the SP integrated server
 * and dedicated servers), and restoring the default config restores normal play
 * on the next boot. Nothing is written to the world or player.</p>
 *
 * <p>The check mirrors AIS 0.7.0's own parse semantics ({@code AisConfig.parse}):
 * effective-value comparison, never text comparison. A missing file means
 * defaults (AIS writes the file on first launch but deleting it is harmless), a
 * malformed value falls back to that key's default exactly as AIS does, and
 * unknown keys are ignored (a future AIS may add keys). Config is player-editable
 * data — bad input must never take the game down or false-positive.</p>
 *
 * <p>⚠️ The defaults table below is pinned to AIS 0.7.0
 * ({@code adventureitemstats_min_version} in {@code gradle.properties}).
 * <b>Refresh it whenever the AIS floor is raised</b> — flagged in the
 * gradle.properties AIS comment block.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class AisDataIntegrity {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** AIS's config file name, under the loader config dir. */
    static final String FILE_NAME = "adventureitemstats.properties";

    // ---- AIS 0.7.0 defaults (keep in lock-step with adventureitemstats_min_version) ----
    static final String KEY_RAISE_CAPS = "raiseAttributeCaps";
    static final String KEY_ARMOR_MAX = "armorCapMax";
    static final String KEY_TOUGHNESS_MAX = "armorToughnessCapMax";

    static final boolean DEFAULT_RAISE_CAPS = true;
    static final double DEFAULT_ARMOR_MAX = 1024.0;
    static final double DEFAULT_TOUGHNESS_MAX = 1024.0;

    /**
     * Deviations found at the current server session's boot; empty when the AIS
     * config matches defaults (or no server is running). Immutable snapshot,
     * replaced whole — never mutated (volatile: written on the server thread,
     * read from event handlers).
     */
    private static volatile List<String> deviations = List.of();

    private AisDataIntegrity() {}

    /** Is the current server session Free Play because AIS data was changed? */
    public static boolean isSessionFreePlay() {
        return !deviations.isEmpty();
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        deviations = check(FMLPaths.CONFIGDIR.get());
        if (!deviations.isEmpty()) {
            LOGGER.warn("[DungeonTrain] AIS config differs from defaults — this session runs in Free Play: {}",
                String.join(", ", deviations));
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        deviations = List.of();
    }

    /**
     * Read {@link #FILE_NAME} from {@code configDir} and report deviations from
     * the AIS defaults. Missing file or unreadable file ⇒ no deviations (AIS
     * itself falls back to defaults in both cases).
     */
    static List<String> check(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return List.of();
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not read {} — assuming AIS defaults (AIS does the same)", file, e);
            return List.of();
        }
        return deviationsOf(properties);
    }

    /**
     * Pure: compare already-loaded properties against the AIS defaults, mirroring
     * AIS's per-key parse fallback — a malformed value is what AIS would replace
     * with the default, so it is <em>not</em> a deviation. Package-visible for
     * unit tests.
     */
    static List<String> deviationsOf(Properties properties) {
        List<String> found = new ArrayList<>(3);
        boolean raiseCaps = parseBoolean(properties.getProperty(KEY_RAISE_CAPS), DEFAULT_RAISE_CAPS);
        if (raiseCaps != DEFAULT_RAISE_CAPS) {
            found.add(KEY_RAISE_CAPS + "=" + raiseCaps + " (expected " + DEFAULT_RAISE_CAPS + ")");
        }
        double armorMax = parsePositiveDouble(properties.getProperty(KEY_ARMOR_MAX), DEFAULT_ARMOR_MAX);
        if (armorMax != DEFAULT_ARMOR_MAX) {
            found.add(KEY_ARMOR_MAX + "=" + armorMax + " (expected " + DEFAULT_ARMOR_MAX + ")");
        }
        double toughnessMax = parsePositiveDouble(properties.getProperty(KEY_TOUGHNESS_MAX), DEFAULT_TOUGHNESS_MAX);
        if (toughnessMax != DEFAULT_TOUGHNESS_MAX) {
            found.add(KEY_TOUGHNESS_MAX + "=" + toughnessMax + " (expected " + DEFAULT_TOUGHNESS_MAX + ")");
        }
        return List.copyOf(found);
    }

    /** Mirrors AIS's boolean parse: only literal true/false count; anything else is the default. */
    private static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null) return fallback;
        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("true")) return true;
        if (trimmed.equalsIgnoreCase("false")) return false;
        return fallback;
    }

    /** Mirrors AIS's double parse: malformed or non-positive values fall back to the default. */
    private static double parsePositiveDouble(String raw, double fallback) {
        if (raw == null) return fallback;
        try {
            double value = Double.parseDouble(raw.trim());
            return (value > 0.0 && Double.isFinite(value)) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
