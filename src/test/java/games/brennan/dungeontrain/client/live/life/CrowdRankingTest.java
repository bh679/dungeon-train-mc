package games.brennan.dungeontrain.client.live.life;

import games.brennan.dungeontrain.client.live.life.CrowdRanking.Ranked;
import games.brennan.dungeontrain.client.live.life.LiveLifeStats.Author;
import games.brennan.dungeontrain.client.live.life.LiveLifeStats.Watcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrowdRankingTest {

    private static final String MOTH = "fedcba9876543210fedcba9876543210";
    private static final String ROOK = "0123456789abcdef0123456789abcdef";

    @Test
    void aChatterCanOutrankAQuietLongerWatcher() {
        List<Ranked> top = CrowdRanking.rank(
            List.of(new Watcher("Moth", MOTH, "game", 3000), new Watcher("Rook", ROOK, "game", 2950)),
            Map.of("Rook", 10), Map.of("Rook", new Author("game", ROOK)), 3);
        assertEquals("Rook", top.get(0).name());
        assertEquals(10, top.get(0).messages());
        assertEquals(2950 + 10 * CrowdRanking.MESSAGE_WEIGHT_S, top.get(0).score());
        assertEquals("Moth", top.get(1).name());
    }

    @Test
    void inGameViewersMergeByUuidEvenUnderAnotherName() {
        List<Ranked> top = CrowdRanking.rank(
            List.of(new Watcher(null, MOTH, "game", 100)),
            Map.of("Moth", 2), Map.of("Moth", new Author("game", MOTH.toUpperCase())), 3);
        assertEquals(1, top.size());
        assertEquals("Moth", top.get(0).name());
        assertEquals(100, top.get(0).seconds());
        assertEquals(2, top.get(0).messages());
    }

    @Test
    void aWebsiteViewerNamedByChatMergesByName() {
        List<Ranked> top = CrowdRanking.rank(
            List.of(new Watcher("Kestrel", null, "web", 600), new Watcher(null, null, "web", 620)),
            Map.of("kestrel", 3), Map.of("kestrel", new Author("web", null)), 3);
        assertEquals(2, top.size());
        assertEquals("Kestrel", top.get(0).name());
        assertEquals(3, top.get(0).messages());
        assertEquals(null, top.get(1).name(), "the unnamed website watcher stands alone");
    }

    @Test
    void twitchAndDiscordChattersRankWithNoWatchTime() {
        List<Ranked> top = CrowdRanking.rank(List.of(),
            Map.of("twitchfan", 4, "discordpal", 1),
            Map.of("twitchfan", new Author("twitch", null), "discordpal", new Author("discord", null)), 3);
        assertEquals("twitchfan", top.get(0).name());
        assertEquals("twitch", top.get(0).source());
        assertEquals(0, top.get(0).seconds());
        assertEquals("discord", top.get(1).source());
    }

    @Test
    void tiesGoToTheLongerWatcherAndThePodiumHoldsThree() {
        List<Ranked> top = CrowdRanking.rank(
            List.of(new Watcher("A", null, "web", 120), new Watcher("B", null, "web", 60),
                new Watcher("C", null, "web", 30), new Watcher("D", null, "web", 10)),
            Map.of("B", 1), Map.of(), 3);
        assertEquals(List.of("A", "B", "C"), top.stream().map(Ranked::name).toList());
    }

    @Test
    void anEmptyCrowdRanksNobody() {
        assertTrue(CrowdRanking.rank(List.of(), Map.of(), Map.of(), 3).isEmpty());
        assertTrue(CrowdRanking.podium(LiveLifeStats.empty("life")).isEmpty());
    }

    @Test
    void chatCountingRecordsWhereTheAuthorSpoke() {
        LiveLifeStats s = LiveLifeStats.empty("life")
            .chat(1, "Rook", "hi", new Author("game", ROOK)).chat(2, "Rook", "again", new Author("game", ROOK));
        assertEquals(new Author("game", ROOK), s.authorInfo().get("Rook"));
        assertEquals(2, CrowdRanking.podium(s).get(0).messages());
    }
}
