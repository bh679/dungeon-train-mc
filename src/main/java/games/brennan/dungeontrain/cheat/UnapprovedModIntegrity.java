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
 * <p><b>Enforcement ships ON and the relay can switch it off</b> ({@link ApprovedModList#enforce}).
 * While it is off the scan still runs and still logs what it found, but {@link #isSessionFreePlay}
 * returns false and no run is affected. That kill switch exists because the failure direction here
 * is the opposite of the blacklist's — a missing blacklist entry lets one cheat through, a missing
 * APPROVAL free-plays every honest player running that mod.</p>
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
     * <p>Populated whether or not enforcement is on: with the relay kill switch thrown, detection
     * still runs and gets logged while the consequence does not.</p>
     */
    private static volatile List<String> detected = List.of();

    /**
     * Installed mod FILES the relay could not verify at this session's boot — the hash-verified
     * half (2026-10), as {@link ModFileFingerprint#display()} strings. A file is unverified when its
     * SHA-1 is not on {@link ApprovedModList#approvedHashes()}: not a published build of an
     * approved mod, or one the relay has not placed yet. Costs the run only while
     * {@link ApprovedModList#enforceHashes()} is on. Immutable, replaced whole.
     */
    private static volatile List<String> unverified = List.of();

    /** How long the boot-time scan waits for {@link ModFileScanner} before judging without it. */
    static final long SCAN_WAIT_MS = 5000;
    /**
     * How long server start waits for an in-flight relay verify before judging from the cache. A
     * client's fetch started at the title screen and is long done; a dedicated server's starts
     * here, and a few seconds is cheap next to a session judged a launch late.
     */
    static final long FETCH_WAIT_MS = 8000;
    private static volatile boolean serverRunning;

    /** The verdict tiers of the file check — only {@link #SUSPECT} costs the run (see {@link #tierOf}). */
    public enum Tier { VERIFIED, PLAUSIBLE, UNKNOWN, SUSPECT }

    private UnapprovedModIntegrity() {}

    /**
     * Is the current server session Free Play because of the whitelist — an unapproved mod ID
     * (while id enforcement is on) or an unverified mod file (while hash enforcement is on)?
     */
    public static boolean isSessionFreePlay() {
        return isIdFreePlay() || isFileFreePlay();
    }

    /** The id half alone: an unapproved mod ID is installed and the relay's enforcement switch is on. */
    public static boolean isIdFreePlay() {
        return ApprovedModList.enforce() && !detected.isEmpty();
    }

    /** The file half alone: an unverified mod file is installed and hash enforcement is on. */
    public static boolean isFileFreePlay() {
        return ApprovedModList.enforceHashes() && !unverified.isEmpty();
    }

    /**
     * The unverified mod files found at this session's boot, e.g. {@code "xray.jar (jei)"} — the
     * file name and the ids it claims, so the player sees WHICH jar to reinstall or remove.
     */
    public static List<String> unverified() {
        return unverified;
    }

    /**
     * The unapproved mods found at this session's boot, e.g. {@code "somemod v1.2.3"} — shown to
     * the player in the login notice so they can see exactly WHAT tripped Free Play. Empty when
     * clean. Non-empty while enforcement is off means nothing has been applied.
     */
    public static List<String> detected() {
        return detected;
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        // Best-effort: refresh the relay overlay for next boot (async; current boot uses the cache).
        // On a dedicated server this is also what starts the file scan.
        ApprovedModListFetcher.ensureFetched();
        serverRunning = true;
        detected = scan();
        // Never a session late: give the relay's answer a moment to land before judging, so a mod
        // updated this launch is judged against what the relay knows now, not last launch's cache.
        ApprovedModListFetcher.awaitSettled(FETCH_WAIT_MS);
        unverified = scanFiles();
        if (!unverified.isEmpty()) {
            if (ApprovedModList.enforceHashes()) {
                LOGGER.warn("[DungeonTrain] Unverified mod file(s) installed — this session runs in Free Play: {}",
                    String.join(", ", unverified));
            } else {
                LOGGER.info("[DungeonTrain] Unverified mod file(s) installed, but the hash check is not "
                    + "enforcing — this session plays normally: {}", String.join(", ", unverified));
            }
        }
        if (detected.isEmpty()) return;
        if (ApprovedModList.enforce()) {
            LOGGER.warn("[DungeonTrain] Unapproved mod(s) installed — this session runs in Free Play: {}",
                String.join(", ", detected));
        } else {
            // The kill-switch line. Worth logging loudly enough to find in a player's log when they
            // ask why their run WOULD have been Free Play, without warning about a thing that has
            // not happened.
            LOGGER.info("[DungeonTrain] Unapproved mod(s) installed, but the approved-mod list is not "
                + "enforcing — this session plays normally: {}", String.join(", ", detected));
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        serverRunning = false;
        detected = List.of();
        unverified = List.of();
    }

    /**
     * Re-judge the running session's files against freshly-landed relay knowledge — the second
     * half of "never a session late": a verify answer that arrives after boot (slow relay, slow
     * scan) still clears a taint before, or even after, the player logs in. No-op without a server.
     */
    public static void rejudge() {
        if (!serverRunning) return;
        List<String> before = unverified;
        List<String> after = scanFiles();
        if (!before.equals(after)) {
            unverified = after;
            LOGGER.info("[DungeonTrain] Mod files re-judged with fresh relay knowledge: {} -> {}",
                before.isEmpty() ? "clean" : before, after.isEmpty() ? "clean" : after);
        }
    }

    /**
     * The hash half of the boot scan: every installed file whose SHA-1 is not approved. Waits
     * briefly for the scanner; an empty scan (not finished, or failed) means "nothing unverified"
     * — the same defensive direction as {@link #scan()}, because a scan that could not see the
     * files must not conclude that every file is fake.
     */
    static List<String> scanFiles() {
        try {
            return unverifiedFrom(ModFileScanner.current(SCAN_WAIT_MS), ApprovedModList.approvedHashes(),
                ApprovedModList.knownPackages());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not judge the installed mod files — assuming every file is "
                + "verified: {}", t.toString());
            return List.of();
        }
    }

    /**
     * Pure: which installed files are NOT on the approved-hash list, as display strings (sorted).
     * Package-visible for unit tests — no live scanner needed.
     */
    static List<String> unverifiedFrom(List<ModFileFingerprint> files, Set<String> approvedSha1s,
                                       Map<String, Set<String>> knownPackages) {
        List<String> found = new ArrayList<>();
        for (ModFileFingerprint f : files) {
            if (tierOf(f, approvedSha1s, knownPackages) == Tier.SUSPECT) found.add(f.display());
        }
        found.sort(String::compareTo);
        return List.copyOf(found);
    }

    /**
     * Pure: which tier is this file in?
     * <ul>
     *   <li>{@link Tier#VERIFIED} — its hash is approved.</li>
     *   <li>{@link Tier#PLAUSIBLE} — hash unknown, but every id it declares has known packages
     *       and the jar's packages intersect each id's — a fresh release or a GitHub build of a
     *       mod we approve. Not Free Play.</li>
     *   <li>{@link Tier#UNKNOWN} — hash unknown and some declared id has no package knowledge at
     *       all. Not Free Play (the player did nothing wrong; the catalogue is behind), but the
     *       relay queues it and the build refuses an approved id without knowledge.</li>
     *   <li>{@link Tier#SUSPECT} — it declares no ids, or its packages share nothing with a
     *       declared id's known set: code that is not the mod it claims to be. Free Play.</li>
     * </ul>
     * A resource-only mod (no classes) is known as an empty set: a jar with classes claiming it
     * is suspect, one without is plausible.
     */
    static Tier tierOf(ModFileFingerprint f, Set<String> approvedSha1s, Map<String, Set<String>> knownPackages) {
        if (ApprovedModList.isFileApproved(f, approvedSha1s)) return Tier.VERIFIED;
        if (f.modIds().isEmpty()) return Tier.SUSPECT;
        Set<String> jar = Set.copyOf(f.packages());
        boolean unknown = false;
        for (String id : f.modIds()) {
            if (ModFileScanner.EXEMPT_IDS.contains(id)) continue;
            Set<String> known = knownPackages.get(id);
            if (known == null) { unknown = true; continue; }
            if (known.isEmpty() ? !jar.isEmpty() : java.util.Collections.disjoint(jar, known)) return Tier.SUSPECT;
        }
        return unknown ? Tier.UNKNOWN : Tier.PLAUSIBLE;
    }

    /**
     * Live-state convenience for the client popup: the installed file declaring {@code modId}, if
     * that file is unverified right now; null when it is verified, unknown, or the scan is not done.
     */
    public static ModFileFingerprint unverifiedFileOf(String modId) {
        ModFileFingerprint f = ModFileScanner.fileOf(modId);
        return f != null && tierOf(f, ApprovedModList.approvedHashes(), ApprovedModList.knownPackages()) == Tier.SUSPECT
            ? f : null;
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
            if (!isUnsupported(e.getKey(), approvedIds, prefixes, revokedIds, cheatIds)) continue;
            found.add(e.getKey() + " v" + e.getValue());
        }
        found.sort(String::compareTo);
        return List.copyOf(found);
    }

    /**
     * Pure: is this installed mod one the Unsupported Mods screen should list — not approved (by id
     * or prefix, and not revoked) and NOT a known cheat mod, which keeps its own notice instead?
     */
    static boolean isUnsupported(String modId, Set<String> approvedIds, List<String> prefixes,
                                 Set<String> revokedIds, Set<String> cheatIds) {
        String id = ModIds.normalise(modId);
        if (id.isEmpty()) return false;
        if (cheatIds != null && cheatIds.contains(id)) return false;
        return !ApprovedModList.isApproved(id, approvedIds, prefixes, revokedIds);
    }

    /**
     * Live-state convenience for the client popup: is {@code modId} unsupported right now (judged
     * against baked ∪ cached/live relay approvals, minus revocations, minus the blacklist)?
     */
    public static boolean isUnsupported(String modId) {
        return isUnsupported(modId, ApprovedModList.approved(), ApprovedModList.prefixes(),
            ApprovedModList.revoked(), CheatModList.effective());
    }
}
