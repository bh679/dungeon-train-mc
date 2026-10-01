package games.brennan.dungeontrain.client.bugresponse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import games.brennan.dungeontrain.client.bugresponse.BugResponse.Kind;
import games.brennan.dungeontrain.client.bugresponse.BugResponse.Result;
import games.brennan.dungeontrain.client.version.compare.ChangelogLedger;
import games.brennan.dungeontrain.client.version.compare.ChangelogTag;
import games.brennan.dungeontrain.client.version.compare.FullSemver;
import games.brennan.dungeontrain.client.version.compare.LedgerEntry;
import games.brennan.dungeontrain.client.version.compare.Platform;
import games.brennan.dungeontrain.client.version.compare.PlatformVersions;
import games.brennan.dungeontrain.client.version.compare.ReleaseEntry;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat response for {@code /bug} and {@code /feedback} reports: each case runs the real
 * {@link BugResponse#decide} (the death-screen card's decision) and checks what
 * {@link BugResponseChat#lines} turns it into: which card strings, and which links.
 */
class BugResponseChatTest {

    private static final String KEY = "gui.dungeontrain.bug_response.";
    private static final String MODRINTH_PACK = "https://modrinth.com/modpack/bEFyz3ji";
    private static final String CURSEFORGE_PACK = "https://www.curseforge.com/projects/1556213";
    private static final String CHANGES = "RUN_COMMAND /dt-bug-response changes";

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

    // 0.1079: perf (lag via tag). 0.1080: a plain bug fix. 0.1081: lag fix via addresses.
    private static final ChangelogLedger LEDGER = new ChangelogLedger(List.of(
            entry("perf79", "0.1079.0", Set.of(ChangelogTag.PERFORMANCE), Set.of()),
            entry("bug80", "0.1080.0", Set.of(ChangelogTag.FIX), Set.of()),
            entry("lag81", "0.1081.0", Set.of(ChangelogTag.FEATURE), Set.of("lag"))));

    private static final PlatformVersions MODRINTH =
            listing(Platform.MODRINTH, "0.1078.0", "0.1079.0", "0.1080.0", "0.1081.0");

    private static Result decide(BugIssue issue, boolean mp, String installed, Platform launcher,
                                 PlatformVersions curseforge) {
        return BugResponse.decide(new BugResponse.Input(issue, mp, Optional.of(v(installed)), launcher,
                Optional.of(MODRINTH), Optional.ofNullable(curseforge), Optional.of(LEDGER)));
    }

    private static List<Component> lines(Result r, List<BugResponseChat.TipLine> tips) {
        return BugResponseChat.lines(r, tips, "en_us");
    }

    // ---- Component walking ----

    /** Every translation key in {@code lines}, siblings and arguments included, in order. */
    private static List<String> keys(List<Component> lines) {
        List<String> out = new ArrayList<>();
        for (Component c : lines) {
            walk(c, node -> {
                if (node.getContents() instanceof TranslatableContents t) out.add(t.getKey());
            });
        }
        return out;
    }

    /** Every click event in {@code lines}, as "ACTION value". */
    private static List<String> clicks(List<Component> lines) {
        List<String> out = new ArrayList<>();
        for (Component c : lines) {
            walk(c, node -> {
                ClickEvent e = node.getStyle().getClickEvent();
                if (e != null) out.add(e.getAction().name() + " " + e.getValue());
            });
        }
        return out;
    }

    private static String plain(Component c) {
        StringBuilder sb = new StringBuilder();
        walk(c, node -> {
            if (node.getContents() instanceof PlainTextContents p) sb.append(p.text());
        });
        return sb.toString();
    }

    private static void walk(Component c, Consumer<Component> visit) {
        visit.accept(c);
        if (c.getContents() instanceof TranslatableContents t) {
            for (Object arg : t.getArgs()) {
                if (arg instanceof Component a) walk(a, visit);
            }
        }
        for (Component s : c.getSiblings()) walk(s, visit);
    }

    // ---- Cases ----

    @Test
    @DisplayName("Fixed on Modrinth: fix rows, update link to the Modrinth pack, See changes command")
    void fixedModrinth() {
        Result r = decide(BugIssue.LAG, false, "0.1078.0", Platform.MODRINTH, null);
        assertEquals(Kind.FIXED, r.kind());
        List<Component> lines = lines(r, List.of());

        List<String> keys = keys(lines);
        assertEquals(BugResponseChat.PREFIX_KEY, keys.get(0));
        assertEquals(KEY + "fixed.title.lag", keys.get(1));
        assertTrue(keys.contains(KEY + "fixed.intro.lag"));
        assertTrue(keys.contains(KEY + "fixed.update"));
        assertFalse(keys.contains(KEY + "fixed.curseforge_review"));
        assertTrue(lines.stream().anyMatch(l -> plain(l).startsWith("• v0.1081.0: Title lag81")));
        assertTrue(lines.stream().anyMatch(l -> plain(l).startsWith("• v0.1079.0: Title perf79")));

        assertEquals(List.of("OPEN_URL " + MODRINTH_PACK, CHANGES), clicks(lines));
    }

    @Test
    @DisplayName("Fixed on CurseForge: the update link opens the CurseForge pack")
    void fixedCurseforge() {
        Result r = decide(BugIssue.LAG, false, "0.1078.0", Platform.CURSEFORGE,
                listing(Platform.CURSEFORGE, "0.1078.0", "0.1079.0", "0.1080.0", "0.1081.0"));
        assertFalse(r.curseforgeReview());
        assertEquals(List.of("OPEN_URL " + CURSEFORGE_PACK, CHANGES), clicks(lines(r, List.of())));
    }

    @Test
    @DisplayName("Fix only on Modrinth: review line, [Modrinth] label, Get vX on Modrinth link")
    void curseforgeUnderReview() {
        Result r = decide(BugIssue.LAG, false, "0.1080.0", Platform.CURSEFORGE,
                listing(Platform.CURSEFORGE, "0.1079.0", "0.1080.0"));
        assertTrue(r.curseforgeReview());
        List<Component> lines = lines(r, List.of());
        List<String> keys = keys(lines);
        assertTrue(keys.contains(KEY + "fixed.curseforge_review"));
        assertTrue(keys.contains(KEY + "fixed.tag.modrinth_only"));
        assertTrue(keys.contains(KEY + "button.get"));
        assertEquals(List.of("OPEN_URL " + MODRINTH_PACK, CHANGES), clicks(lines),
                "up to date on CurseForge: no Update link, only Get on Modrinth and See changes");
    }

    @Test
    @DisplayName("Train vanished in multiplayer: known-issue lines plus the server update line when behind")
    void multiplayerBehind() {
        Result r = decide(BugIssue.TRAIN_VANISHED, true, "0.1080.0", Platform.MODRINTH, null);
        assertEquals(Kind.MULTIPLAYER, r.kind());
        assertEquals(List.of(BugResponseChat.PREFIX_KEY, KEY + "mp.title", KEY + "mp.body", KEY + "mp.sent",
                KEY + "outdated.one", KEY + "outdated.server", BugResponseChat.UPDATE_KEY, KEY + "button.changes"),
                keys(lines(r, List.of())));
    }

    @Test
    @DisplayName("Train vanished in singleplayer is not the multiplayer card")
    void trainVanishedSingleplayer() {
        Result r = decide(BugIssue.TRAIN_VANISHED, false, "0.1081.0", Platform.MODRINTH, null);
        assertEquals(List.of(BugResponseChat.PREFIX_KEY, KEY + "sent"), keys(lines(r, List.of())));
    }

    @Test
    @DisplayName("Lag with no fix: tips become command links; the memory tip only shows its hint")
    void lagTips() {
        Result r = decide(BugIssue.LAG, false, "0.1081.0", Platform.MODRINTH, null);
        assertEquals(Kind.LAG_TIPS, r.kind());
        List<BugResponseChat.TipLine> tips = List.of(
                new BugResponseChat.TipLine("photos", Component.translatable(KEY + "tips.photos", 1080, 720),
                        Component.translatable(KEY + "tips.photos.button"), null),
                new BugResponseChat.TipLine("memory", Component.translatable(KEY + "tips.memory", "4", 6),
                        null, Component.translatable(KEY + "tips.memory.hint")));
        List<Component> lines = lines(r, tips);

        assertEquals(List.of(BugResponseChat.PREFIX_KEY, KEY + "tips.title", KEY + "tips.photos",
                KEY + "tips.photos.button", KEY + "tips.memory", KEY + "tips.memory.hint"), keys(lines));
        assertEquals(List.of("RUN_COMMAND /dt-bug-response tip photos"), clicks(lines));
    }

    @Test
    @DisplayName("Lag with no applicable tip: a plain thanks, not a heading over nothing")
    void lagWithoutTipsFallsBackToThanks() {
        Result r = decide(BugIssue.LAG, true, "0.1081.0", Platform.MODRINTH, null);
        assertEquals(Kind.LAG_TIPS, r.kind());
        assertEquals(List.of(BugResponseChat.PREFIX_KEY, KEY + "sent"), keys(lines(r, List.of())));
        assertEquals(KEY + "tips.title", BugResponseChat.lagTitleKey(true));
        assertEquals(KEY + "sent", BugResponseChat.lagTitleKey(false));
    }

    @Test
    @DisplayName("A long response is sent in two: headline now, update details and links later")
    void longResponseIsSplit() {
        Result r = decide(BugIssue.LAG, false, "0.1081.0", Platform.MODRINTH,
                null);
        Result behind = new Result(r.kind(), r.issue(), Optional.of(v("0.1079.0")), r.launcher(), r.fixes(),
                false, Optional.empty(), 2, Optional.of(v("0.1081.0")), 0);
        List<BugResponseChat.TipLine> tips = List.of("dh", "render_distance", "shaders", "photos", "carriages").stream()
                .map(id -> new BugResponseChat.TipLine(id, Component.literal(id), Component.literal("Open"), null))
                .toList();
        BugResponseChat.Messages m = BugResponseChat.messages(behind, tips, "en_us");

        assertEquals(6, m.now().size(), "title and five tips");
        assertEquals(List.of(BugResponseChat.PREFIX_KEY, KEY + "tips.title"), keys(m.now().subList(0, 1)));
        assertEquals(List.of(KEY + "outdated.other", KEY + "outdated.update", BugResponseChat.UPDATE_KEY,
                KEY + "button.changes"), keys(m.later()));
        assertEquals(BugResponseChat.lines(behind, tips, "en_us").size(), m.now().size() + m.later().size());
    }

    @Test
    @DisplayName("A short response arrives whole")
    void shortResponseIsNotSplit() {
        Result r = decide(BugIssue.TRAIN_VANISHED, true, "0.1080.0", Platform.MODRINTH, null);
        BugResponseChat.Messages m = BugResponseChat.messages(r, List.of(), "en_us");
        assertTrue(m.later().isEmpty());
        assertEquals(5, m.now().size());
    }

    @Test
    @DisplayName("Other, up to date: just the thanks, no links")
    void otherUpToDate() {
        Result r = decide(BugIssue.OTHER, false, "0.1081.0", Platform.MODRINTH, null);
        List<Component> lines = lines(r, List.of());
        assertEquals(1, lines.size());
        assertEquals(List.of(BugResponseChat.PREFIX_KEY, KEY + "sent"), keys(lines));
        assertTrue(clicks(lines).isEmpty());
    }

    @Test
    @DisplayName("Other, behind with a missed bug fix: the fixes count, Update and See changes")
    void otherBehind() {
        Result r = decide(BugIssue.OTHER, false, "0.1078.0", Platform.MODRINTH, null);
        assertEquals(Kind.GENERIC, r.kind());
        List<Component> lines = lines(r, List.of());
        assertTrue(keys(lines).contains(KEY + "outdated.other"));
        assertTrue(keys(lines).contains(KEY + "outdated.fixes.one"));
        assertEquals(List.of("OPEN_URL " + MODRINTH_PACK, CHANGES), clicks(lines));
    }

    @Test
    @DisplayName("Other with a derail comment classifies as the train issue, then answers as multiplayer")
    void otherCommentReusesClassifier() {
        BugIssue issue = BugIssueClassifier.classify("Other", "the train derailed", List.of("derail"));
        Result r = decide(issue, true, "0.1081.0", Platform.MODRINTH, null);
        assertEquals(Kind.MULTIPLAYER, r.kind());
        assertTrue(keys(lines(r, List.of())).contains(KEY + "mp.title"));
    }

    @Test
    @DisplayName("tipLines: a tip without an action is not offered as a link")
    void tipWithoutActionIsNotClickable() {
        List<BugResponseChat.TipLine> tips = BugResponseChat.tipLines(List.of(
                new LagTips.Tip("dh", Component.literal("dh"), Component.literal("Turn off"), null, null),
                new LagTips.Tip("render_distance", Component.literal("rd"), Component.literal("Video"), () -> { }, null)));
        assertNull(tips.get(0).button());
        assertEquals("Video", tips.get(1).button().getString());
    }

    @Test
    @DisplayName("en_us: the prefix exists and no bug-response string uses an em dash")
    void langHasPrefixAndNoEmDash() throws Exception {
        JsonObject lang = JsonParser.parseString(Files.readString(RepoPaths.langFile("en_us"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("[Dungeon Train]", lang.get(BugResponseChat.PREFIX_KEY).getAsString());
        for (Map.Entry<String, JsonElement> e : lang.entrySet()) {
            if (e.getKey().contains("bug_response")) {
                assertFalse(e.getValue().getAsString().contains("—"), e.getKey() + " uses an em dash");
            }
        }
    }
}
