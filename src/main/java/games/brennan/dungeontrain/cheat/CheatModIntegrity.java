package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dungeon Train's difficulty and loot balance assume a level playing field. A world running with a
 * <b>known cheat mod</b> installed (x-ray, fullbright, freecam, automation, …) is not playing the
 * game DT balanced, so the whole server session runs in <b>Free Play</b> (see {@link RunIntegrity}):
 * stats and advancements don't persist to the cross-world profile while such a mod is present.
 *
 * <p><b>Session-only</b>, exactly like {@link AisDataIntegrity}: the mod list is re-scanned at every
 * server start ({@link ServerAboutToStartEvent} — covers the SP integrated server and dedicated
 * servers) and cleared on stop. Removing the mod restores normal play on the next boot. Nothing is
 * written to the world or player.</p>
 *
 * <p><b>The list</b> comes from {@link CheatModList#effective()} — a curated baked set merged with
 * a relay-served overlay so it can grow without a mod release. A best-effort relay refresh is kicked
 * at boot; it lands async and affects the <em>next</em> boot (the current boot uses baked ∪ the
 * disk-cached list). This is a soft honesty nudge, not hard anti-cheat.</p>
 *
 * <p><b>Coverage:</b> in single-player the integrated server shares the JVM with the client, so
 * {@link ModList} sees <em>all</em> mods including client-only cheat mods — the primary case. On a
 * dedicated server, {@link ModList} sees only server-side mods, so a purely client-side cheat mod
 * there is not caught (that would need a client→server report, tracked as a follow-up).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CheatModIntegrity {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Cheat mods found at the current server session's boot, as {@code "<modId> v<version>"}
     * display strings; empty when clean (or no server is running). Immutable snapshot, replaced
     * whole — never mutated (volatile: written on the server thread, read from event handlers).
     */
    private static volatile List<String> detected = List.of();

    private CheatModIntegrity() {}

    /** Is the current server session Free Play because a known cheat mod is installed? */
    public static boolean isSessionFreePlay() {
        return !detected.isEmpty();
    }

    /**
     * The cheat mods found at this session's boot, e.g. {@code "xray v1.2.3"} — shown to the player
     * in the login notice so they can see exactly WHAT tripped Free Play. Empty when clean.
     */
    public static List<String> detected() {
        return detected;
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        // Best-effort: refresh the relay overlay for next boot (async; current boot uses the cache).
        CheatModListFetcher.ensureFetched();
        detected = scan();
        if (!detected.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Known cheat mod(s) installed — this session runs in Free Play: {}",
                String.join(", ", detected));
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        detected = List.of();
    }

    /**
     * Enumerate installed mods (id → version) and match against {@link CheatModList#effective()}.
     * Wrapped so a broken mod list can never take the game down at boot — a scan failure just means
     * "no cheat mods detected", matching the defensive posture of {@link AisDataIntegrity}.
     */
    static List<String> scan() {
        try {
            Map<String, String> installed = new LinkedHashMap<>();
            for (var info : ModList.get().getMods()) {
                installed.put(info.getModId(), info.getVersion().toString());
            }
            return detectedFrom(CheatModList.effective(), installed);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not scan the mod list for cheat mods — assuming none: {}",
                t.toString());
            return List.of();
        }
    }

    /**
     * Pure: which installed mods are in the cheat set, as {@code "<modId> v<version>"} display
     * strings (sorted for stable output). Match is case-insensitive on the mod ID. Package-visible
     * for unit tests — no live {@link ModList} needed.
     *
     * @param cheatIds  lowercase cheat mod IDs (from {@link CheatModList#effective()})
     * @param installed installed mod ID → version
     */
    static List<String> detectedFrom(Set<String> cheatIds, Map<String, String> installed) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, String> e : installed.entrySet()) {
            String id = e.getKey();
            if (id != null && cheatIds.contains(id.toLowerCase(Locale.ROOT))) {
                found.add(id + " v" + e.getValue());
            }
        }
        found.sort(String::compareTo);
        return List.copyOf(found);
    }
}
