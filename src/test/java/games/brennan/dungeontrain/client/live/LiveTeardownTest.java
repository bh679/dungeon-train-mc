package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The stream teardown runs its steps in order and a failing step never skips the ones after it. */
class LiveTeardownTest {

    @Test
    void runsEveryStepInOrder() {
        List<String> ran = new ArrayList<>();
        LiveStreamController.runTeardown(List.of(
            () -> ran.add("encoder"), () -> ran.add("uploader"), () -> ran.add("relay"), () -> ran.add("dir")));
        assertEquals(List.of("encoder", "uploader", "relay", "dir"), ran);
    }

    @Test
    void aFailingStepDoesNotSkipTheRest() {
        List<String> ran = new ArrayList<>();
        LiveStreamController.runTeardown(List.of(
            () -> ran.add("encoder"),
            () -> { throw new IllegalStateException("upload failed"); },
            () -> ran.add("dir")));
        assertEquals(List.of("encoder", "dir"), ran);
    }
}
