package games.brennan.dungeontrain.client.bugresponse;

import games.brennan.dungeontrain.client.bugresponse.BugResponse.Kind;
import games.brennan.dungeontrain.client.bugresponse.BugResponse.Result;
import games.brennan.dungeontrain.client.version.compare.ChangelogLedger;
import games.brennan.dungeontrain.client.version.compare.ChangelogTag;
import games.brennan.dungeontrain.client.version.compare.FullSemver;
import games.brennan.dungeontrain.client.version.compare.LedgerEntry;
import games.brennan.dungeontrain.client.version.compare.Platform;
import games.brennan.dungeontrain.client.version.compare.PlatformVersions;
import games.brennan.dungeontrain.client.version.compare.ReleaseEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BugResponseTest {

    private static FullSemver v(String s) {
        return FullSemver.parse(s).orElseThrow();
    }

    private static PlatformVersions listing(Platform p, String... versions) {
        return new PlatformVersions(p, Arrays.stream(versions)
                .map(s -> new ReleaseEntry(v(s), null, "")).toList());
    }

    private static LedgerEntry entry(String id, String releasedIn, Set<ChangelogTag> tags, Set<String> addresses) {
        return new LedgerEntry(id, v(releasedIn), "fix", "Title " + id, "", List.of(), tags, v(releasedIn), addresses);
    }

    // 0.1079: perf fix (lag via tag). 0.1080: train fix + a plain bug fix. 0.1081: lag fix via addresses.
    private static final ChangelogLedger LEDGER = new ChangelogLedger(List.of(
            entry("perf79", "0.1079.0", Set.of(ChangelogTag.PERFORMANCE), Set.of()),
            entry("train80", "0.1080.0", Set.of(ChangelogTag.FIX), Set.of("train_vanished")),
            entry("bug80", "0.1080.0", Set.of(ChangelogTag.FIX), Set.of()),
            entry("lag81", "0.1081.0", Set.of(ChangelogTag.FEATURE), Set.of("lag"))));

    private static final PlatformVersions MODRINTH =
            listing(Platform.MODRINTH, "0.1078.0", "0.1078.1", "0.1079.0", "0.1080.0", "0.1081.0", "0.1081.4");

    private static Result decide(BugIssue issue, boolean mp, String installed, Platform launcher,
                                 PlatformVersions curseforge) {
        return BugResponse.decide(new BugResponse.Input(issue, mp, Optional.of(v(installed)), launcher,
                Optional.of(MODRINTH), Optional.ofNullable(curseforge), Optional.of(LEDGER)));
    }

    private static List<String> ids(Result r) {
        return r.fixes().stream().map(f -> f.entry().id()).toList();
    }

    @Test
    @DisplayName("A1: fixes on the player's own launcher, no CurseForge line")
    void fixedOnOwnLauncher() {
        Result r = decide(BugIssue.LAG, false, "0.1078.0", Platform.MODRINTH, null);
        assertEquals(Kind.FIXED, r.kind());
        assertEquals(List.of("lag81", "perf79"), ids(r));
        assertFalse(r.curseforgeReview());
        assertEquals(3, r.releasesBehind(), "cascade ticks do not count");
        assertEquals(Optional.of(v("0.1081.4")), r.updateTarget());
    }

    @Test
    @DisplayName("A2: CurseForge has some fixes, Modrinth has more; Modrinth-only ones are flagged")
    void curseforgeBehindModrinth() {
        Result r = decide(BugIssue.LAG, false, "0.1078.0", Platform.CURSEFORGE,
                listing(Platform.CURSEFORGE, "0.1078.0", "0.1079.0", "0.1080.0"));
        assertEquals(Kind.FIXED, r.kind());
        assertEquals(List.of("lag81", "perf79"), ids(r));
        assertTrue(r.fixes().get(0).modrinthOnly());
        assertFalse(r.fixes().get(1).modrinthOnly());
        assertTrue(r.curseforgeReview());
        assertEquals(Optional.of(v("0.1081.4")), r.modrinthTarget());
        assertEquals(2, r.releasesBehind());
        assertEquals(Optional.of(v("0.1080.0")), r.updateTarget());
    }

    @Test
    @DisplayName("A3: on the newest CurseForge release, the fix is only on Modrinth")
    void onlyOnModrinth() {
        Result r = decide(BugIssue.LAG, false, "0.1080.0", Platform.CURSEFORGE,
                listing(Platform.CURSEFORGE, "0.1079.0", "0.1080.0"));
        assertEquals(Kind.FIXED, r.kind());
        assertEquals(List.of("lag81"), ids(r));
        assertTrue(r.curseforgeReview());
        assertFalse(r.behind());
        assertEquals(Optional.empty(), r.updateTarget());
    }

    @Test
    @DisplayName("train vanished in multiplayer with a newer fix shows the fix, not the known issue")
    void trainFixWins() {
        Result r = decide(BugIssue.TRAIN_VANISHED, true, "0.1079.0", Platform.MODRINTH, null);
        assertEquals(Kind.FIXED, r.kind());
        assertEquals(List.of("train80"), ids(r));
    }

    @Test
    @DisplayName("train vanished in multiplayer with no fix: known multiplayer issue; singleplayer: generic")
    void multiplayer() {
        assertEquals(Kind.MULTIPLAYER, decide(BugIssue.TRAIN_VANISHED, true, "0.1081.0", Platform.MODRINTH, null).kind());
        assertEquals(Kind.GENERIC, decide(BugIssue.TRAIN_VANISHED, false, "0.1081.0", Platform.MODRINTH, null).kind());
    }

    @Test
    @DisplayName("lag with no newer fix shows tips; up to date means no update line")
    void lagTips() {
        Result r = decide(BugIssue.LAG, false, "0.1081.0", Platform.MODRINTH, null);
        assertEquals(Kind.LAG_TIPS, r.kind());
        assertFalse(r.behind(), "0.1081.4 is a cascade tick, not a real release");
    }

    @Test
    @DisplayName("other: generic, counting the bug fixes the player is missing")
    void generic() {
        Result r = decide(BugIssue.OTHER, false, "0.1078.0", Platform.MODRINTH, null);
        assertEquals(Kind.GENERIC, r.kind());
        assertTrue(r.fixes().isEmpty());
        assertEquals(2, r.bugFixesMissed());
        assertEquals(3, r.releasesBehind());
    }

    @Test
    @DisplayName("no data at all degrades to the issue's fallback card")
    void offline() {
        Result r = BugResponse.decide(new BugResponse.Input(BugIssue.LAG, false, Optional.empty(), Platform.MODRINTH,
                Optional.empty(), Optional.empty(), Optional.empty()));
        assertEquals(Kind.LAG_TIPS, r.kind());
        assertFalse(r.behind());
    }

    @Test
    @DisplayName("ReleasesBehind ignores PATCH ticks and ahead builds")
    void behindCount() {
        assertEquals(0, ReleasesBehind.count(MODRINTH, v("0.1081.0")));
        assertEquals(0, ReleasesBehind.count(MODRINTH, v("0.1090.0")));
        assertEquals(1, ReleasesBehind.count(MODRINTH, v("0.1080.7")));
    }
}
