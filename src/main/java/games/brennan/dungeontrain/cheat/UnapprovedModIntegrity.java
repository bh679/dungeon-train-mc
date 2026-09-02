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
import java.util.Map;
import java.util.Set;

/**
 * Dungeon Train's difficulty and loot balance assume a level playing field. {@link CheatModIntegrity}
 * asks whether a mod we know is cheating is installed; this class asks the broader question the
 * balance actually depends on — is everything installed something we have <b>approved</b>? A run
 * with an unapproved mod present is not necessarily cheating, but it is not comparable to anyone
 * else's, so it runs in <b>Free Play</b> (see {@link RunIntegrity}).
 *
 * <p><b>Session-only</b>, exactly like {@link CheatModIntegrity}: the mod list is re-scanned at
 * every server start ({@link ServerAboutToStartEvent} — covers the SP integrated server and
 * dedicated servers) and cleared on stop. Removing the mod restores normal play on the next boot.
 * Nothing is written to the world or player.</p>
 *
 * <p><b>Enforcement is a relay switch, and it ships OFF</b> ({@link ApprovedModList#enforce}).
 * While it is off the scan still runs and still logs what it found, but {@link #isSessionFreePlay}
 * returns false and no run is affected. That is deliberate: the failure direction here is the
 * opposite of the blacklist's — a missing blacklist entry lets one cheat through, a missing
 * APPROVAL free-plays every honest player running that mod — so the list gets measured against the
 * real player base (the relay's Approved Mods page) before it costs anybody anything.</p>
 *
 * <p><b>Known cheat mods are left to {@link CheatModIntegrity}.</b> They are unapproved too, but
 * naming them twice at login would say the same thing in a vaguer way; the specific "a known cheat
 * mod is installed" notice is the more useful one, so this class excludes them from its own list.</p>
 *
 * <p><b>Coverage:</b> in single-player the integrated server shares the JVM with the client, so
 * {@link ModList} sees <em>all</em> mods including client-only ones — the primary case. On a
 * dedicated server, {@link ModList} sees only server-side mods (same limitation as the blacklist).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class UnapprovedModIntegrity {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Unapproved mods found at the current server session's boot, as {@code "<modId> v<version>"}
     * display strings; empty when clean (or no server is running). Immutable snapshot, replaced
     * whole — never mutated (volatile: written on the server thread, read from event handlers).
     *
     * <p>Populated whether or not enforcement is on: the whole point of the observe-only period is
     * that the detection runs and gets logged while the consequence does not.</p>
     */
    private static volatile List<String> detected = List.of();

    private UnapprovedModIntegrity() {}

    /**
     * Is the current server session Free Play because an unapproved mod is installed? False while
     * the relay's enforcement switch is off, however many unapproved mods were found.
     */
    public static boolean isSessionFreePlay() {
        return ApprovedModList.enforce() && !detected.isEmpty();
    }

    /**
     * The unapproved mods found at this session's boot, e.g. {@code "somemod v1.2.3"} — shown to
     * the player in the login notice so they can see exactly WHAT tripped Free Play. Empty when
     * clean. Non-empty while enforcement is off is normal and means nothing has been applied.
     */
    public static List<String> detected() {
        return detected;
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        // Best-effort: refresh the relay overlay for next boot (async; current boot uses the cache).
        ApprovedModListFetcher.ensureFetched();
        detected = scan();
        if (detected.isEmpty()) return;
        if (ApprovedModList.enforce()) {
            LOGGER.warn("[DungeonTrain] Unapproved mod(s) installed — this session runs in Free Play: {}",
                String.join(", ", detected));
        } else {
            // The observe-only line. Worth logging loudly enough to find in a player's log when
            // they ask why their run WOULD have been Free Play, without warning about a thing that
            // has not happened.
            LOGGER.info("[DungeonTrain] Unapproved mod(s) installed, but the approved-mod list is not "
                + "enforcing — this session plays normally: {}", String.join(", ", detected));
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        detected = List.of();
    }

    /**
     * Enumerate installed mods (id → version) and match against {@link ApprovedModList}. Wrapped so
     * a broken mod list can never take the game down at boot — a scan failure means "nothing
     * unapproved", matching the defensive posture of {@link CheatModIntegrity#scan}. That is also
     * the only sane direction: a scan that cannot see the mod list must not conclude that every mod
     * is unapproved.
     */
    static List<String> scan() {
        try {
            Map<String, String> installed = new LinkedHashMap<>();
            for (var info : ModList.get().getMods()) {
                installed.put(info.getModId(), info.getVersion().toString());
            }
            return unapprovedFrom(ApprovedModList.approved(), ApprovedModList.prefixes(),
                ApprovedModList.revoked(), CheatModList.effective(), installed);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not scan the mod list against the approved list — "
                + "assuming everything is approved: {}", t.toString());
            return List.of();
        }
    }

    /**
     * Pure: which installed mods are NOT approved, as {@code "<modId> v<version>"} display strings
     * (sorted for stable output). Package-visible for unit tests — no live {@link ModList} needed.
     *
     * @param approvedIds lowercase approved mod IDs (from {@link ApprovedModList#approved()})
     * @param prefixes    raw-ID prefixes that also count as approved
     * @param revokedIds  lowercase revoked IDs — never approved, whichever rule would have matched
     * @param cheatIds    the blacklist; those mods are reported by {@link CheatModIntegrity}
     *                    instead, so they are left out here rather than named twice
     * @param installed   installed mod ID → version
     */
    static List<String> unapprovedFrom(Set<String> approvedIds, List<String> prefixes,
                                       Set<String> revokedIds, Set<String> cheatIds,
                                       Map<String, String> installed) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, String> e : installed.entrySet()) {
            String id = ModIds.normalise(e.getKey());
            if (id.isEmpty()) continue;
            if (cheatIds != null && cheatIds.contains(id)) continue;
            if (ApprovedModList.isApproved(id, approvedIds, prefixes, revokedIds)) continue;
            found.add(e.getKey() + " v" + e.getValue());
        }
        found.sort(String::compareTo);
        return List.copyOf(found);
    }
}
