package games.brennan.dungeontrain.client.bugresponse;

import games.brennan.dungeontrain.client.version.compare.ChangelogLedger;
import games.brennan.dungeontrain.client.version.compare.ChangelogTag;
import games.brennan.dungeontrain.client.version.compare.FullSemver;
import games.brennan.dungeontrain.client.version.compare.LedgerEntry;
import games.brennan.dungeontrain.client.version.compare.Platform;
import games.brennan.dungeontrain.client.version.compare.PlatformVersions;
import games.brennan.dungeontrain.client.version.compare.ReleaseEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the death screen says back after a bug report. Pure: it is handed the issue, whether the
 * player is in multiplayer, the installed version, their launcher, both launchers' listings and the
 * changelog ledger, and decides which card to show and what goes on it. Missing data (offline, still
 * fetching) simply means fewer facts — never an error.
 *
 * <ul>
 *   <li>{@link Kind#FIXED} — a release newer than the installed one addresses the issue. Fixes on the
 *       player's own launcher are listed; a CurseForge player also sees fixes only Modrinth has yet,
 *       flagged, with the "still under review" line and a Modrinth button.</li>
 *   <li>{@link Kind#MULTIPLAYER} — a vanishing / derailing / duplicating train in multiplayer with no
 *       fix out: the known multiplayer issue.</li>
 *   <li>{@link Kind#LAG_TIPS} — lag with no fix out: things to try.</li>
 *   <li>{@link Kind#GENERIC} — anything else: thanks, plus how far behind they are.</li>
 * </ul>
 */
public final class BugResponse {

    /** Most fixes listed on the card; the newest win. */
    public static final int MAX_FIXES_SHOWN = 3;

    public enum Kind { FIXED, MULTIPLAYER, LAG_TIPS, GENERIC }

    /** One ledger entry that addresses the issue. {@code modrinthOnly}: not yet on the player's CurseForge. */
    public record Fix(LedgerEntry entry, boolean modrinthOnly) {}

    public record Input(BugIssue issue, boolean multiplayer, Optional<FullSemver> installed, Platform launcher,
                        Optional<PlatformVersions> modrinth, Optional<PlatformVersions> curseforge,
                        Optional<ChangelogLedger> ledger) {}

    /**
     * @param fixes            newest first, at most {@link #MAX_FIXES_SHOWN}; empty unless {@link Kind#FIXED}
     * @param curseforgeReview show the "CurseForge release is under review" line + Modrinth button
     * @param modrinthTarget   the Modrinth version to offer when {@code curseforgeReview}
     * @param releasesBehind   real releases newer than installed on the player's launcher
     * @param updateTarget     the version the player's launcher would update them to, when behind
     * @param bugFixesMissed   entries tagged Bug Fix in the releases they are behind on
     */
    public record Result(Kind kind, BugIssue issue, Optional<FullSemver> installed, Platform launcher,
                         List<Fix> fixes, boolean curseforgeReview, Optional<FullSemver> modrinthTarget,
                         int releasesBehind, Optional<FullSemver> updateTarget, int bugFixesMissed) {

        public boolean behind() {
            return releasesBehind > 0;
        }
    }

    private BugResponse() {}

    public static Result decide(Input in) {
        Optional<PlatformVersions> own = in.launcher() == Platform.CURSEFORGE ? in.curseforge() : in.modrinth();
        Optional<FullSemver> ownLatest = own.flatMap(PlatformVersions::latest).map(ReleaseEntry::version);

        int behind = 0;
        Optional<FullSemver> updateTarget = Optional.empty();
        if (in.installed().isPresent() && own.isPresent()) {
            behind = ReleasesBehind.count(own.get(), in.installed().get());
            updateTarget = ReleasesBehind.updateTarget(own.get(), in.installed().get());
        }

        List<Fix> fixes = new ArrayList<>();
        boolean curseforgeReview = false;
        Optional<FullSemver> modrinthTarget = Optional.empty();
        int bugFixesMissed = 0;

        if (in.installed().isPresent() && in.ledger().isPresent()) {
            FullSemver installed = in.installed().get();
            ChangelogLedger ledger = in.ledger().get();

            if (ownLatest.isPresent()) {
                for (LedgerEntry e : ledger.entriesReleasedBetween(installed, ownLatest.get())) {
                    if (addresses(e, in.issue())) fixes.add(new Fix(e, false));
                    if (updateTarget.isPresent() && e.tags().contains(ChangelogTag.FIX)) bugFixesMissed++;
                }
            }

            // A CurseForge player may be held back by CurseForge's review: anything Modrinth has
            // that their own launcher does not is still worth telling them about.
            if (in.launcher() == Platform.CURSEFORGE) {
                Optional<FullSemver> mrLatest = in.modrinth().flatMap(PlatformVersions::latest).map(ReleaseEntry::version);
                if (mrLatest.isPresent()) {
                    FullSemver floor = ownLatest.filter(v -> v.isNewerThan(installed)).orElse(installed);
                    List<Fix> modrinthOnly = new ArrayList<>();
                    for (LedgerEntry e : ledger.entriesReleasedBetween(floor, mrLatest.get())) {
                        if (addresses(e, in.issue())) modrinthOnly.add(new Fix(e, true));
                    }
                    if (!modrinthOnly.isEmpty()) {
                        curseforgeReview = true;
                        modrinthTarget = mrLatest;
                        fixes.addAll(0, modrinthOnly); // newer than anything on CurseForge
                    }
                }
            }
        }

        Kind kind;
        if (!fixes.isEmpty()) {
            kind = Kind.FIXED;
        } else if (in.issue() == BugIssue.TRAIN_VANISHED && in.multiplayer()) {
            kind = Kind.MULTIPLAYER;
        } else if (in.issue() == BugIssue.LAG) {
            kind = Kind.LAG_TIPS;
        } else {
            kind = Kind.GENERIC;
        }

        List<Fix> shown = fixes.size() > MAX_FIXES_SHOWN ? List.copyOf(fixes.subList(0, MAX_FIXES_SHOWN)) : List.copyOf(fixes);
        return new Result(kind, in.issue(), in.installed(), in.launcher(), shown, curseforgeReview, modrinthTarget,
                behind, updateTarget, bugFixesMissed);
    }

    /**
     * Whether {@code e} addresses {@code issue}: it lists the issue in {@code addresses}, or — for lag —
     * it is a performance change, so performance work from before the field existed still counts.
     */
    static boolean addresses(LedgerEntry e, BugIssue issue) {
        Optional<String> id = issue.ledgerId();
        if (id.isEmpty()) return false;
        if (e.addresses().contains(id.get())) return true;
        return issue == BugIssue.LAG && e.tags().contains(ChangelogTag.PERFORMANCE);
    }
}
