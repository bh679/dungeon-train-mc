package games.brennan.dungeontrain.client.gl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SectionUploadFailures}: which errors count as upload failures, and the forgiveness budget. */
final class SectionUploadFailuresTest {

    @Test
    @DisplayName("a recorded upload error is found through the CompletionException the rebuild sees")
    void recordedThroughCompletionException() {
        SectionUploadFailures failures = new SectionUploadFailures(5, 60_000L);
        RuntimeException upload = new RuntimeException("upload");
        failures.record(upload);

        assertTrue(failures.isUploadFailure(upload));
        assertTrue(failures.isUploadFailure(new CompletionException(upload)));
    }

    @Test
    @DisplayName("an error the upload wrapper never saw is not an upload failure")
    void unrecordedIsNotUploadFailure() {
        SectionUploadFailures failures = new SectionUploadFailures(5, 60_000L);
        failures.record(new RuntimeException("upload"));

        assertFalse(failures.isUploadFailure(new RuntimeException("upload")));
        assertFalse(failures.isUploadFailure(new CompletionException(new IllegalStateException("compile"))));
        assertFalse(failures.isUploadFailure(null));
    }

    @Test
    @DisplayName("a cause chain that loops back on itself ends instead of spinning")
    void cyclicCauseChainTerminates() {
        SectionUploadFailures failures = new SectionUploadFailures(5, 60_000L);
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertFalse(failures.isUploadFailure(a));
    }

    @Test
    @DisplayName("five failures a minute are forgiven, the sixth crashes, and the budget refills as the window slides")
    void budgetExhaustsAndRefills() {
        SectionUploadFailures failures = new SectionUploadFailures(5, 60_000L);
        for (int i = 0; i < 5; i++) assertTrue(failures.tryForgive(1_000L * i), "failure " + (i + 1));
        assertFalse(failures.tryForgive(10_000L));
        assertEquals(5, failures.forgivenInWindow());

        // The first forgiveness (t=0) leaves the window at t=60 000; only that one slot comes back.
        assertTrue(failures.tryForgive(60_000L));
        assertFalse(failures.tryForgive(60_500L));
    }
}
