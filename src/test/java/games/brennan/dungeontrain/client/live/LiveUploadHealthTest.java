package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveUploadHealthTest {

    @Test
    void utilisationIsPutTimeOverSegmentTime() {
        LiveUploadHealth h = new LiveUploadHealth(10);
        // 3.3 MB in 11 s: a 10 s segment that took longer than real time.
        h.uploaded(3_300_000, 11_000);
        LiveUploadHealth.Snapshot s = h.snapshot();
        assertEquals(1, s.samples());
        assertEquals(1.1, s.utilisation(), 1e-9);
        assertEquals(2400, s.throughputKbps());
    }

    @Test
    void averagesLeanOnTheNewestSamples() {
        LiveUploadHealth h = new LiveUploadHealth(10);
        h.uploaded(1_000_000, 1000);
        h.uploaded(1_000_000, 11_000);
        assertEquals(0.1 + LiveUploadHealth.ALPHA * (1.1 - 0.1), h.snapshot().utilisation(), 1e-9);
    }

    @Test
    void onlySlowFailuresCount() {
        LiveUploadHealth h = new LiveUploadHealth(10);
        h.failed(200);
        assertEquals(0, h.snapshot().samples(), "a dropped connection says nothing about speed");
        h.failed(20_000);
        assertEquals(1, h.snapshot().samples());
        assertEquals(2.0, h.snapshot().utilisation(), 1e-9);
        assertEquals(0, h.snapshot().throughputKbps());
        h.uploaded(1_000_000, 4000);
        assertEquals(2000, h.snapshot().throughputKbps(), "speed starts from its own first sample");
    }

    @Test
    void restartClearsTheAveragesButNotWhatIsRemembered() {
        LiveUploadHealth h = new LiveUploadHealth(10);
        for (int i = 0; i < LiveUploadHealth.MIN_SAMPLES_TO_LEARN; i++) h.uploaded(1_000_000, 2000);
        h.restart();
        assertEquals(0, h.snapshot().samples());
        assertEquals(4000, h.sustainedKbps());
    }

    @Test
    void tooShortAStreamRemembersNothing() {
        LiveUploadHealth h = new LiveUploadHealth(10);
        h.uploaded(1_000_000, 2000);
        assertEquals(0, h.sustainedKbps());
    }
}
